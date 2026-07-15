package com.team.bank.orchestrator;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;

/**
 * Minimal GitHub OAuth login. No Spring Security, no DB — just three endpoints and an in-memory
 * session map. The SPA drives the flow: it hits /login to get the GitHub authorize URL, GitHub
 * bounces back to the SPA with ?code&state, the SPA POSTs those to /callback, and we return an
 * opaque bearer token that gates subsequent API calls.
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

  private static final Logger log = LoggerFactory.getLogger(AuthController.class);
  private static final Duration STATE_TTL = Duration.ofMinutes(10);
  private static final int MAX_PENDING_STATES = 1000;
  private static final String BEARER_PREFIX = "Bearer ";
  private static final String GITHUB_AUTHORIZE = "https://github.com/login/oauth/authorize";
  private static final String GITHUB_TOKEN = "https://github.com/login/oauth/access_token";
  private static final String GITHUB_USER = "https://api.github.com/user";
  private static final String GITHUB_EMAILS = "https://api.github.com/user/emails";

  private final WebClient webClient;
  private final UserRepository userRepository;

  @Value("${github.client-id:}")
  private String clientId;

  @Value("${github.client-secret:}")
  private String clientSecret;

  @Value("${github.redirect-uri:}")
  private String redirectUri;

  private final SecureRandom random = new SecureRandom();

  // state -> creation instant. Consumed on callback; also TTL-pruned to bound memory.
  private final ConcurrentHashMap<String, Instant> pendingStates = new ConcurrentHashMap<>();
  // opaque bearer token -> authenticated app user (from the users table).
  private final ConcurrentHashMap<String, AppUser> sessions = new ConcurrentHashMap<>();

  @SuppressFBWarnings("EI_EXPOSE_REP2")
  public AuthController(WebClient webClient, UserRepository userRepository) {
    // `.mutate().build()` gives us our own WebClient instance (DashboardController does the same);
    // UserRepository is a Spring-managed singleton so storing the reference is fine — suppress
    // the resulting EI_EXPOSE_REP2 warning like the other controllers do for their repositories.
    this.webClient = webClient.mutate().build();
    this.userRepository = userRepository;
  }

  @GetMapping(value = "/github/login", produces = MediaType.APPLICATION_JSON_VALUE)
  public LoginStartResponse start() {
    requireConfigured();
    pruneStates();

    String state = randomToken();
    pendingStates.put(state, Instant.now());

    String authUrl =
        GITHUB_AUTHORIZE
            + "?client_id="
            + urlEncode(clientId)
            + "&redirect_uri="
            + urlEncode(redirectUri)
            + "&scope="
            + urlEncode("read:user user:email")
            + "&state="
            + urlEncode(state)
            + "&allow_signup=true";
    return new LoginStartResponse(authUrl, state);
  }

  @PostMapping(
      value = "/github/callback",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  public AuthSession callback(@RequestBody CallbackRequest body) {
    requireConfigured();
    if (body == null || !StringUtils.hasText(body.code()) || !StringUtils.hasText(body.state())) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "code and state are required");
    }

    // Single-use state: remove and check TTL. Prevents replay and CSRF.
    Instant issued = pendingStates.remove(body.state());
    if (issued == null || Duration.between(issued, Instant.now()).compareTo(STATE_TTL) > 0) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid or expired state");
    }

    GitHubTokenResponse tokenResponse = exchangeCodeForToken(body.code());
    if (tokenResponse == null
        || tokenResponse.accessToken() == null
        || tokenResponse.accessToken().isBlank()) {
      String detail =
          tokenResponse != null && tokenResponse.errorDescription() != null
              ? tokenResponse.errorDescription()
              : "GitHub did not return an access token";
      log.warn("GitHub token exchange failed: {}", detail);
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "GitHub token exchange failed");
    }

    GitHubUser ghUser = fetchGithubUser(tokenResponse.accessToken());
    if (ghUser == null) {
      throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Failed to fetch GitHub user");
    }

    // GitHub only puts `email` on /user if the user made it public. Fall back to /user/emails
    // (needs the user:email scope, which /login already requests) and pick the primary verified
    // one so we always end up with a real address in the DB when available.
    String email = ghUser.email();
    if (email == null || email.isBlank()) {
      email = fetchPrimaryEmail(tokenResponse.accessToken());
    }

    String[] nameParts = splitName(ghUser.name(), ghUser.login());
    AppUser user =
        userRepository.upsert(
            ghUser.id(), ghUser.login(), nameParts[0], nameParts[1], email, ghUser.avatarUrl());

    String sessionToken = UUID.randomUUID().toString();
    sessions.put(sessionToken, user);
    log.info(
        "Signed in user login={} githubId={} firstName={} lastName={} email={}",
        user.login(),
        user.githubId(),
        user.firstName(),
        user.lastName(),
        user.email());
    return new AuthSession(sessionToken, user);
  }

  @GetMapping(value = "/me", produces = MediaType.APPLICATION_JSON_VALUE)
  public AppUser me(
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authHeader) {
    AppUser user = lookupSession(authHeader);
    if (user == null) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "not signed in");
    }
    return user;
  }

  @PostMapping(value = "/logout", produces = MediaType.APPLICATION_JSON_VALUE)
  public Map<String, String> logout(
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authHeader) {
    String token = extractToken(authHeader);
    if (token != null) {
      sessions.remove(token);
    }
    return Map.of("status", "ok");
  }

  // --- helpers ---------------------------------------------------------------

  /** Sessions can be looked up by other controllers (e.g. to scope data per user) later. */
  AppUser lookupSession(String authHeader) {
    String token = extractToken(authHeader);
    if (token == null) {
      return null;
    }
    return sessions.get(token);
  }

  private void requireConfigured() {
    if (!StringUtils.hasText(clientId)
        || !StringUtils.hasText(clientSecret)
        || !StringUtils.hasText(redirectUri)) {
      throw new ResponseStatusException(
          HttpStatus.SERVICE_UNAVAILABLE,
          "GitHub OAuth is not configured (set GITHUB_CLIENT_ID / GITHUB_CLIENT_SECRET / GITHUB_REDIRECT_URI).");
    }
  }

  private GitHubTokenResponse exchangeCodeForToken(String code) {
    String form =
        "client_id="
            + urlEncode(clientId)
            + "&client_secret="
            + urlEncode(clientSecret)
            + "&code="
            + urlEncode(code)
            + "&redirect_uri="
            + urlEncode(redirectUri);
    try {
      return webClient
          .post()
          .uri(GITHUB_TOKEN)
          .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
          .contentType(MediaType.APPLICATION_FORM_URLENCODED)
          .bodyValue(form)
          .retrieve()
          .bodyToMono(GitHubTokenResponse.class)
          .block();
    } catch (RuntimeException e) {
      log.warn("GitHub token endpoint call failed", e);
      throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "GitHub token endpoint failed");
    }
  }

  private GitHubUser fetchGithubUser(String accessToken) {
    try {
      return webClient
          .get()
          .uri(GITHUB_USER)
          .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
          .header(HttpHeaders.ACCEPT, "application/vnd.github+json")
          .header("X-GitHub-Api-Version", "2022-11-28")
          .retrieve()
          .bodyToMono(GitHubUser.class)
          .block();
    } catch (RuntimeException e) {
      log.warn("GitHub /user call failed", e);
      return null;
    }
  }

  /**
   * Prefer the primary verified email, then any verified email, then whatever GitHub returned
   * first. Null if the token can't read emails or the account has none.
   */
  private String fetchPrimaryEmail(String accessToken) {
    try {
      GitHubEmail[] emails =
          webClient
              .get()
              .uri(GITHUB_EMAILS)
              .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
              .header(HttpHeaders.ACCEPT, "application/vnd.github+json")
              .header("X-GitHub-Api-Version", "2022-11-28")
              .retrieve()
              .bodyToMono(GitHubEmail[].class)
              .block();
      if (emails == null || emails.length == 0) {
        return null;
      }
      for (GitHubEmail e : emails) {
        if (e.primary() && e.verified()) {
          return e.email();
        }
      }
      for (GitHubEmail e : emails) {
        if (e.verified()) {
          return e.email();
        }
      }
      return emails[0].email();
    } catch (RuntimeException e) {
      log.warn("GitHub /user/emails call failed", e);
      return null;
    }
  }

  /**
   * GitHub's `name` is one string ("John Doe"). Split on the first whitespace so we can store
   * first/last separately; if the account has no name set, fall back to the login as the first name
   * so the DB always has something human-readable.
   */
  static String[] splitName(String fullName, String login) {
    String cleaned = fullName == null ? "" : fullName.trim();
    if (cleaned.isEmpty()) {
      return new String[] {login, null};
    }
    int space = cleaned.indexOf(' ');
    if (space < 0) {
      return new String[] {cleaned, null};
    }
    return new String[] {cleaned.substring(0, space), cleaned.substring(space + 1).trim()};
  }

  private String extractToken(String authHeader) {
    if (authHeader == null || !authHeader.startsWith(BEARER_PREFIX)) {
      return null;
    }
    String token = authHeader.substring(BEARER_PREFIX.length()).trim();
    return token.isEmpty() ? null : token;
  }

  private String randomToken() {
    byte[] buf = new byte[24];
    random.nextBytes(buf);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
  }

  private static String urlEncode(String s) {
    return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
  }

  /**
   * Drop expired states, and if the map is unusually large, wipe it. Bounds memory without
   * requiring a background scheduler.
   */
  private void pruneStates() {
    Instant now = Instant.now();
    pendingStates
        .entrySet()
        .removeIf(e -> Duration.between(e.getValue(), now).compareTo(STATE_TTL) > 0);
    if (pendingStates.size() > MAX_PENDING_STATES) {
      pendingStates.clear();
    }
  }
}
