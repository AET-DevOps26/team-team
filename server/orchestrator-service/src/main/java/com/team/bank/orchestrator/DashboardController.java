package com.team.bank.orchestrator;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api")
public class DashboardController {

  private final WebClient webClient;

  @Value("${services.account.url}")
  private String accountServiceUrl;

  @Value("${services.transaction.url}")
  private String transactionServiceUrl;

  @Value("${services.genai.url}")
  private String genaiServiceUrl;

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

    SummaryResponse summary =
        webClient
            .post()
            .uri(genaiServiceUrl + "/summarize")
            .bodyValue(summaryRequest)
            .retrieve()
            .bodyToMono(SummaryResponse.class)
            .block();

    return new DashboardResponse(
        account,
        trend == null ? List.of() : List.of(trend),
        expenses == null ? List.of() : List.of(expenses),
        summary == null ? "No summary available." : summary.summary());
  }

  @PostMapping(value = "/chat", produces = MediaType.APPLICATION_JSON_VALUE)
  public ChatResponse chat(@RequestBody ChatRequest request) {
    if (request == null || !StringUtils.hasText(request.message())) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "message is required");
    }
    ChatResponse response =
        webClient
            .post()
            .uri(genaiServiceUrl + "/chat")
            .bodyValue(request)
            .retrieve()
            .bodyToMono(ChatResponse.class)
            .block();
    return response == null ? new ChatResponse("I could not process that request.") : response;
  }

  @GetMapping("/health")
  public Map<String, String> health() {
    return Map.of("status", "UP", "service", "orchestrator-service");
  }
}
