package com.team.bank.account;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/accounts")
public class AccountController {

    private final AccountRepository accountRepository;

    public AccountController(AccountRepository accountRepository) {
        this.accountRepository = accountRepository;
    }

    @GetMapping("/{accountId}")
    public AccountSummary getAccount(@PathVariable UUID accountId) {
        Account account = accountRepository.findById(accountId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Account not found"));
        return new AccountSummary(
            account.getId(),
            account.getCustomerName(),
            account.getBalance(),
            account.getCreditLimit(),
            new BigDecimal("0.152")
        );
    }

    @GetMapping("/{accountId}/trend")
    public List<BalancePoint> getBalanceTrend(@PathVariable UUID accountId) {
        if (!accountRepository.existsById(accountId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Account not found");
        }
        return List.of(
            new BalancePoint("Jan", new BigDecimal("24800")),
            new BalancePoint("Feb", new BigDecimal("26800")),
            new BalancePoint("Mar", new BigDecimal("22100")),
            new BalancePoint("Apr", new BigDecimal("30100")),
            new BalancePoint("May", new BigDecimal("26100")),
            new BalancePoint("Jun", new BigDecimal("27900"))
        );
    }

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "UP", "service", "account-service");
    }
}
