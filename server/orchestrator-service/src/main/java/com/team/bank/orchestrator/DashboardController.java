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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api")
public class DashboardController {

    private final RestTemplate restTemplate;

    @Value("${services.account.url}")
    private String accountServiceUrl;

    @Value("${services.transaction.url}")
    private String transactionServiceUrl;

    @Value("${services.genai.url}")
    private String genaiServiceUrl;

    @Value("${services.banking.url}")
    private String bankingServiceUrl;

    public DashboardController(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    @GetMapping(value = {"", "/"}, produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> apiIndex() {
        return Map.of(
            "service", "orchestrator-service",
            "status", "UP",
            "endpoints", List.of(
                "GET /api/health",
                "GET /api/dashboard/{accountId}",
                "POST /api/chat"
            )
        );
    }

    @GetMapping(value = "/dashboard/{accountId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public DashboardResponse dashboard(@PathVariable UUID accountId) {
        AccountSummary account = restTemplate.getForObject(
            accountServiceUrl + "/api/accounts/" + accountId,
            AccountSummary.class
        );

        if (account == null) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Failed to retrieve account");
        }

        BalancePoint[] trend = restTemplate.getForObject(
            accountServiceUrl + "/api/accounts/" + accountId + "/trend",
            BalancePoint[].class
        );

        ExpenseSlice[] expenses = restTemplate.getForObject(
            transactionServiceUrl + "/api/transactions/" + accountId + "/expenses",
            ExpenseSlice[].class
        );

        SummaryRequest summaryRequest = new SummaryRequest(account, trend == null ? List.of() : List.of(trend), expenses == null ? List.of() : List.of(expenses));

        SummaryResponse summary = null;
        try {
            summary = restTemplate.postForObject(
                genaiServiceUrl + "/summarize",
                summaryRequest,
                SummaryResponse.class
            );
        } catch (Exception e) {
            // genai-service unavailable, continue without summary
        }

        ConnectionStatus connectionStatus = null;
        try {
            connectionStatus = restTemplate.getForObject(
                bankingServiceUrl + "/api/banking/status/" + accountId,
                ConnectionStatus.class);
        } catch (Exception e) {
            // banking-service unavailable, continue without it
        }

        return new DashboardResponse(account, trend == null ? List.of() : List.of(trend), expenses == null ? List.of() : List.of(expenses),
            summary == null ? "No summary available." : summary.summary(), connectionStatus);
    }

    @PostMapping(value = "/chat", produces = MediaType.APPLICATION_JSON_VALUE)
    public ChatResponse chat(@RequestBody ChatRequest request) {
        if (request == null || !StringUtils.hasText(request.message())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "message is required");
        }
        ChatResponse response = restTemplate.postForObject(genaiServiceUrl + "/chat", request, ChatResponse.class);
        return response == null ? new ChatResponse("I could not process that request.") : response;
    }

    // Thin proxies so the browser reaches banking-service through the orchestrator (single origin / CORS).
    @GetMapping("/banking/banks")
    public Object listBanks(@RequestParam String country) {
        return restTemplate.getForObject(
            bankingServiceUrl + "/api/banking/banks?country=" + country, Object.class);
    }

    @PostMapping("/banking/connect")
    public Object connectBank(@RequestBody Map<String, Object> request) {
        return restTemplate.postForObject(
            bankingServiceUrl + "/api/banking/connect", request, Object.class);
    }

    @PostMapping("/banking/callback")
    public Object handleCallback(@RequestBody Map<String, String> body) {
        return restTemplate.postForObject(
            bankingServiceUrl + "/api/banking/callback", body, Object.class);
    }

    @GetMapping("/banking/status/{accountId}")
    public Object getConnectionStatus(@PathVariable UUID accountId) {
        return restTemplate.getForObject(
            bankingServiceUrl + "/api/banking/status/" + accountId, Object.class);
    }

    @PostMapping("/banking/sync/{accountId}")
    public Object syncAccount(@PathVariable UUID accountId) {
        return restTemplate.postForObject(
            bankingServiceUrl + "/api/banking/sync/" + accountId, null, Object.class);
    }

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "UP", "service", "orchestrator-service");
    }
}
