package com.team.bank.orchestrator;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.TextStyle;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api")
public class DashboardController {

  private static final Logger log = LoggerFactory.getLogger(DashboardController.class);
  private static final int RECENT_TX_LIMIT = 10;

  private final WebClient webClient;
  private final AuthController authController;

  @Value("${services.account.url}")
  private String accountServiceUrl;

  @Value("${services.transaction.url}")
  private String transactionServiceUrl;

  @Value("${services.genai.url}")
  private String genaiServiceUrl;

  @Value("${services.banking.url}")
  private String bankingServiceUrl;

  public DashboardController(WebClient webClient, AuthController authController) {
    this.webClient = webClient.mutate().build();
    this.authController = authController;
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
  public DashboardResponse dashboard(
      @PathVariable UUID accountId,
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authHeader) {
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

    // If the signed-in user is viewing their OWN aggregate account, replace whatever the accounts
    // table has in customer_name (banking-service overwrites it from bank data on each sync — see
    // BankingSyncService#recomputeAggregate) with the user's actual profile name so the greeting
    // in the AI summary and the UI addresses the real person, not the last-synced bank account
    // holder. Demo account and cross-user views keep whatever customerName the DB returned.
    AppUser signedInUser = authController.lookupSession(authHeader);
    if (signedInUser != null && accountId.equals(signedInUser.accountId())) {
      String preferredName = displayName(signedInUser);
      if (!preferredName.isBlank() && !preferredName.equals(account.customerName())) {
        account =
            new AccountSummary(
                account.accountId(),
                preferredName,
                account.totalBalance(),
                account.totalCreditLimit(),
                account.utilizationRate());
      }
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

    TransactionItem[] allTx =
        webClient
            .get()
            .uri(transactionServiceUrl + "/api/transactions/{accountId}", accountId)
            .retrieve()
            .bodyToMono(TransactionItem[].class)
            .block();
    List<TransactionItem> transactions =
        allTx == null ? List.of() : List.of(allTx).stream().limit(RECENT_TX_LIMIT).toList();
    MonthlyFlow monthlyFlow = monthlyFlow(allTx);
    List<BankSpend> spendByBank = spendByBank(allTx);

    ConnectionStatus connectionStatus = null;
    BankConnection[] connections = null;
    try {
      connectionStatus =
          webClient
              .get()
              .uri(bankingServiceUrl + "/api/banking/status/{accountId}", accountId)
              .retrieve()
              .bodyToMono(ConnectionStatus.class)
              .block();
      connections =
          webClient
              .get()
              .uri(bankingServiceUrl + "/api/banking/connections/{accountId}", accountId)
              .retrieve()
              .bodyToMono(BankConnection[].class)
              .block();
    } catch (RuntimeException e) {
      // banking-service unavailable, continue without it
      log.warn("banking-service unavailable, continuing without connection status", e);
    }

    SummaryRequest summaryRequest =
        new SummaryRequest(
            account,
            trend == null ? List.of() : List.of(trend),
            expenses == null ? List.of() : List.of(expenses),
            connections == null ? List.of() : List.of(connections),
            monthlyFlow);

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

    return new DashboardResponse(
        account,
        trend == null ? List.of() : List.of(trend),
        expenses == null ? List.of() : List.of(expenses),
        summary == null ? "No summary available." : summary.summary(),
        connectionStatus,
        connections == null ? List.of() : List.of(connections),
        transactions,
        monthlyFlow,
        spendByBank);
  }

  /** "First Last" from the users table, falling back to the GitHub login when both are blank. */
  private static String displayName(AppUser user) {
    StringBuilder sb = new StringBuilder();
    if (StringUtils.hasText(user.firstName())) {
      sb.append(user.firstName().trim());
    }
    if (StringUtils.hasText(user.lastName())) {
      if (sb.length() > 0) {
        sb.append(' ');
      }
      sb.append(user.lastName().trim());
    }
    if (sb.length() == 0) {
      sb.append(user.login());
    }
    return sb.toString();
  }

  /** This month's income (credits), spending (debits) and net across all linked banks. */
  private static MonthlyFlow monthlyFlow(TransactionItem[] txs) {
    YearMonth now = YearMonth.now(ZoneId.systemDefault());
    BigDecimal income = BigDecimal.ZERO;
    BigDecimal spending = BigDecimal.ZERO;
    if (txs != null) {
      for (TransactionItem tx : txs) {
        if (tx.createdAt() == null
            || tx.amount() == null
            || !YearMonth.from(tx.createdAt()).equals(now)) {
          continue;
        }
        if ("CREDIT".equalsIgnoreCase(tx.direction())) {
          income = income.add(tx.amount());
        } else {
          spending = spending.add(tx.amount());
        }
      }
    }
    String month = now.getMonth().getDisplayName(TextStyle.SHORT, Locale.ENGLISH);
    return new MonthlyFlow(month, income, spending, income.subtract(spending));
  }

  /** Total debits attributed to each linked bank, most-spent first. */
  private static List<BankSpend> spendByBank(TransactionItem[] txs) {
    if (txs == null) {
      return List.of();
    }
    Map<String, BigDecimal> byBank = new LinkedHashMap<>();
    for (TransactionItem tx : txs) {
      if (tx.amount() == null || !"DEBIT".equalsIgnoreCase(tx.direction())) {
        continue;
      }
      String bank = tx.bankName() != null ? tx.bankName() : "Unknown";
      byBank.merge(bank, tx.amount(), BigDecimal::add);
    }
    return byBank.entrySet().stream()
        .map(e -> new BankSpend(e.getKey(), e.getValue()))
        .sorted((a, b) -> b.spending().compareTo(a.spending()))
        .toList();
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
