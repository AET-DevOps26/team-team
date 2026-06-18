package com.team.bank.banking.client;

import com.team.bank.banking.config.EnableBankingConfig;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

/**
 * Thin client over the Enable Banking REST API. Every request carries a freshly signed, short-lived
 * JWT (see {@link EnableBankingJwtSigner}) as a bearer token. Responses are returned as
 * loosely-typed maps because Enable Banking's PSD2 payloads vary by bank and endpoint; callers pick
 * out the fields they need.
 */
@Component
public class EnableBankingClient {

  private final RestTemplate restTemplate;
  private final EnableBankingJwtSigner jwtSigner;
  private final EnableBankingConfig config;

  @SuppressFBWarnings("EI_EXPOSE_REP2")
  public EnableBankingClient(
      RestTemplate restTemplate, EnableBankingJwtSigner jwtSigner, EnableBankingConfig config) {
    this.restTemplate = restTemplate;
    this.jwtSigner = jwtSigner;
    this.config = config;
  }

  private HttpHeaders authHeaders() {
    HttpHeaders headers = new HttpHeaders();
    headers.setBearerAuth(jwtSigner.sign());
    return headers;
  }

  @SuppressWarnings("unchecked")
  public List<Map<String, Object>> listBanks(String country) {
    String url = config.getBaseUrl() + "/aspsps?country=" + country;
    HttpEntity<Void> entity = new HttpEntity<>(authHeaders());
    ResponseEntity<Map<String, Object>> response =
        restTemplate.exchange(url, HttpMethod.GET, entity, new ParameterizedTypeReference<>() {});
    Map<String, Object> body = response.getBody();
    if (body == null) {
      return List.of();
    }
    Object aspsps = body.get("aspsps");
    if (aspsps instanceof List<?>) {
      return (List<Map<String, Object>>) aspsps;
    }
    return List.of();
  }

  @SuppressWarnings("unchecked")
  public String initiateAuth(String bankName, String country, String redirectUrl, String state) {
    // Map.of below rejects null values, so bail out early on missing input; the controller
    // treats a null return as "could not start authorization".
    if (bankName == null
        || bankName.isBlank()
        || country == null
        || country.isBlank()
        || redirectUrl == null
        || redirectUrl.isBlank()
        || state == null
        || state.isBlank()) {
      return null;
    }

    String url = config.getBaseUrl() + "/auth";
    String validUntil = Instant.now().plus(90, ChronoUnit.DAYS).toString();
    Map<String, Object> body =
        Map.of(
            "access",
            Map.of("valid_until", validUntil),
            "aspsp",
            Map.of("name", bankName, "country", country),
            "state",
            state,
            "redirect_url",
            redirectUrl,
            "psu_type",
            "personal");
    HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, authHeaders());
    ResponseEntity<Map<String, Object>> response =
        restTemplate.exchange(url, HttpMethod.POST, entity, new ParameterizedTypeReference<>() {});
    Map<String, Object> responseBody = response.getBody();
    return responseBody != null ? (String) responseBody.get("url") : null;
  }

  @SuppressWarnings("unchecked")
  public Map<String, Object> createSession(String code) {
    String url = config.getBaseUrl() + "/sessions";
    Map<String, Object> body = Map.of("code", code);
    HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, authHeaders());
    ResponseEntity<Map<String, Object>> response =
        restTemplate.exchange(url, HttpMethod.POST, entity, new ParameterizedTypeReference<>() {});
    return response.getBody();
  }

  @SuppressWarnings("unchecked")
  public Map<String, Object> getBalances(String accountUid) {
    String url = config.getBaseUrl() + "/accounts/" + accountUid + "/balances";
    HttpEntity<Void> entity = new HttpEntity<>(authHeaders());
    ResponseEntity<Map<String, Object>> response =
        restTemplate.exchange(url, HttpMethod.GET, entity, new ParameterizedTypeReference<>() {});
    return response.getBody();
  }

  @SuppressWarnings("unchecked")
  public Map<String, Object> getTransactions(String accountUid) {
    String url = config.getBaseUrl() + "/accounts/" + accountUid + "/transactions";
    HttpEntity<Void> entity = new HttpEntity<>(authHeaders());
    ResponseEntity<Map<String, Object>> response =
        restTemplate.exchange(url, HttpMethod.GET, entity, new ParameterizedTypeReference<>() {});
    return response.getBody();
  }
}
