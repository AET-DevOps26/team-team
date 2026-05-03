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

    public DashboardController(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
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

        SummaryResponse summary = restTemplate.postForObject(
            genaiServiceUrl + "/summarize",
            summaryRequest,
            SummaryResponse.class
        );

        return new DashboardResponse(account, trend == null ? List.of() : List.of(trend), expenses == null ? List.of() : List.of(expenses),
            summary == null ? "No summary available." : summary.summary());
    }

    @PostMapping(value = "/chat", produces = MediaType.APPLICATION_JSON_VALUE)
    public ChatResponse chat(@RequestBody ChatRequest request) {
        if (request == null || !StringUtils.hasText(request.message())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "message is required");
        }
        ChatResponse response = restTemplate.postForObject(genaiServiceUrl + "/chat", request, ChatResponse.class);
        return response == null ? new ChatResponse("I could not process that request.") : response;
    }

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "UP", "service", "orchestrator-service");
    }
}
