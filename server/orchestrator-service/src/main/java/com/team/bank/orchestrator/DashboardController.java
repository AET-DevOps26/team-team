package com.team.bank.orchestrator;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api")
public class DashboardController {

  private static final Logger log = LoggerFactory.getLogger(DashboardController.class);

  private final WebClient webClient;

  @Value("${services.account.url}")
  private String accountServiceUrl;

  @Value("${services.transaction.url}")
  private String transactionServiceUrl;

  @Value("${services.genai.url}")
  private String genaiServiceUrl;

  @Value("${services.banking.url}")
  private String bankingServiceUrl;

  public DashboardController(WebClient webClient) {
    this.webClient = webClient.mutate().build();
  }

  @GetMapping(
      value = {"", "/"},
      produces = MediaType.APPLICATION_JSON_VALUE)
  public Map<String, Object> apiIndex() {
    return Map.of(
        "service",
        "orchestrator-service",
        "status",
        "UP",
        "endpoints",
        List.of("GET /api/health", "GET /api/dashboard/{accountId}", "POST /api/chat"));
  }

  @GetMapping(value = "/dashboard/{accountId}", produces = MediaType.APPLICATION_JSON_VALUE)
  public DashboardResponse dashboard(@PathVariable UUID accountId) {
    AccountSummary account =
        webClient
            .get()
            .uri(accountServiceUrl + "/api/accounts/{accountId}", accountId)
            .retrieve()
            .bodyToMono(AccountSummary.class)
            .block();

    if (account == null) {
      throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Failed to retrieve account");
    }

    BalancePoint[] trend =
        webClient
            .get()
            .uri(accountServiceUrl + "/api/accounts/{accountId}/trend", accountId)
            .retrieve()
            .bodyToMono(BalancePoint[].class)
            .block();

    ExpenseSlice[] expenses =
        webClient
            .get()
            .uri(transactionServiceUrl + "/api/transactions/{accountId}/expenses", accountId)
            .retrieve()
            .bodyToMono(ExpenseSlice[].class)
            .block();

    SummaryRequest summaryRequest =
        new SummaryRequest(
            account,
            trend == null ? List.of() : List.of(trend),
            expenses == null ? List.of() : List.of(expenses));

    SummaryResponse summary = null;
    try {
      summary =
          webClient
              .post()
              .uri(genaiServiceUrl + "/summarize")
              .bodyValue(summaryRequest)
              .retrieve()
              .bodyToMono(SummaryResponse.class)
              .block();
    } catch (RuntimeException e) {
      // genai-service unavailable, continue without summary
      log.warn("genai-service unavailable, continuing without summary", e);
    }

    ConnectionStatus connectionStatus = null;
    try {
      connectionStatus =
          webClient
              .get()
              .uri(bankingServiceUrl + "/api/banking/status/{accountId}", accountId)
              .retrieve()
              .bodyToMono(ConnectionStatus.class)
              .block();
    } catch (RuntimeException e) {
      // banking-service unavailable, continue without it
      log.warn("banking-service unavailable, continuing without connection status", e);
    }

    return new DashboardResponse(
        account,
        trend == null ? List.of() : List.of(trend),
        expenses == null ? List.of() : List.of(expenses),
        summary == null ? "No summary available." : summary.summary(),
        connectionStatus);
  }

  @PostMapping(value = "/chat", produces = MediaType.APPLICATION_JSON_VALUE)
  public ChatResponse chat(@RequestBody ChatRequest request) {
    if (request == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "request body is required");
    }
    boolean hasMessages = request.messages() != null && !request.messages().isEmpty();
    boolean hasSingle = StringUtils.hasText(request.message());
    if (!hasMessages && !hasSingle) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "message or messages is required");
    }
    ChatResponse response = null;
    try {
      response =
          webClient
              .post()
              .uri(genaiServiceUrl + "/chat")
              .bodyValue(request)
              .retrieve()
              .bodyToMono(ChatResponse.class)
              .block();
    } catch (RuntimeException e) {
      // genai-service unavailable or returned an error; fall back to a safe response
      log.warn("genai-service unavailable, returning fallback chat response", e);
    }
    return response == null
        ? new ChatResponse("I could not process that request.", null)
        : response;
  }

  // Thin proxies so the browser reaches banking-service through the orchestrator (single origin /
  // CORS).
  @GetMapping("/banking/banks")
  public Object listBanks(@RequestParam String country) {
    return webClient
        .get()
        .uri(bankingServiceUrl + "/api/banking/banks?country={country}", country)
        .retrieve()
        .bodyToMono(Object.class)
        .block();
  }

  @PostMapping("/banking/connect")
  public Object connectBank(@RequestBody Map<String, Object> request) {
    return webClient
        .post()
        .uri(bankingServiceUrl + "/api/banking/connect")
        .bodyValue(request)
        .retrieve()
        .bodyToMono(Object.class)
        .block();
  }

  @PostMapping("/banking/callback")
  public Object handleCallback(@RequestBody Map<String, String> body) {
    return webClient
        .post()
        .uri(bankingServiceUrl + "/api/banking/callback")
        .bodyValue(body)
        .retrieve()
        .bodyToMono(Object.class)
        .block();
  }

  @GetMapping("/banking/status/{accountId}")
  public Object getConnectionStatus(@PathVariable UUID accountId) {
    return webClient
        .get()
        .uri(bankingServiceUrl + "/api/banking/status/{accountId}", accountId)
        .retrieve()
        .bodyToMono(Object.class)
        .block();
  }

  @PostMapping("/banking/sync/{accountId}")
  public Object syncAccount(@PathVariable UUID accountId) {
    return webClient
        .post()
        .uri(bankingServiceUrl + "/api/banking/sync/{accountId}", accountId)
        .retrieve()
        .bodyToMono(Object.class)
        .block();
  }

  @GetMapping("/health")
  public Map<String, String> health() {
    return Map.of("status", "UP", "service", "orchestrator-service");
  }
}
