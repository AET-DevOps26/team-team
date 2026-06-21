package com.team.bank.account;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/accounts")
public class AccountController {

  private final AccountRepository accountRepository;
  private final RestTemplate restTemplate;
  private final String transactionServiceUrl;

  @SuppressFBWarnings("EI_EXPOSE_REP2")
  public AccountController(
      AccountRepository accountRepository,
      RestTemplate restTemplate,
      @Value("${services.transaction.url}") String transactionServiceUrl) {
    this.accountRepository = accountRepository;
    this.restTemplate = restTemplate;
    this.transactionServiceUrl = transactionServiceUrl;
  }

  @GetMapping("/{accountId}")
  public AccountSummary getAccount(@PathVariable UUID accountId) {
    Account account =
        accountRepository
            .findById(accountId)
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Account not found"));
    return new AccountSummary(
        account.getId(),
        account.getCustomerName(),
        account.getBalance(),
        account.getCreditLimit(),
        utilizationRate(account.getBalance(), account.getCreditLimit()));
  }

  /**
   * Credit utilization as balance / credit limit, the fraction of the credit limit in use. Returns
   * 0 when no positive credit limit is set (avoids division by zero). Replaces a previously
   * hardcoded constant so the figure reflects the account's real balance.
   */
  private static BigDecimal utilizationRate(BigDecimal balance, BigDecimal creditLimit) {
    if (balance == null || creditLimit == null || creditLimit.signum() <= 0) {
      return BigDecimal.ZERO;
    }
    return balance.divide(creditLimit, 4, RoundingMode.HALF_UP);
  }

  @GetMapping("/{accountId}/trend")
  public List<BalancePoint> getBalanceTrend(@PathVariable UUID accountId) {
    Account account =
        accountRepository
            .findById(accountId)
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Account not found"));

    BigDecimal currentBalance = account.getBalance();
    YearMonth currentMonth = YearMonth.now(ZoneId.systemDefault());

    // Fetch transactions from transaction-service
    String url = transactionServiceUrl + "/api/transactions/" + accountId;
    TransactionItem[] transactions;
    try {
      transactions = restTemplate.getForObject(url, TransactionItem[].class);
    } catch (Exception e) {
      transactions = null;
    }

    // If no transactions, return single point with current month and balance
    if (transactions == null || transactions.length == 0) {
      String monthName = currentMonth.getMonth().getDisplayName(TextStyle.SHORT, Locale.ENGLISH);
      return List.of(new BalancePoint(monthName, currentBalance));
    }

    // Build list of last 6 months (index 0 = oldest, index 5 = current)
    List<YearMonth> months = new ArrayList<>();
    for (int i = 5; i >= 0; i--) {
      months.add(currentMonth.minusMonths(i));
    }

    // Compute monthly net change for each of the 6 months
    // CREDIT = +amount, DEBIT = -amount
    BigDecimal[] netChanges = new BigDecimal[6];
    Arrays.fill(netChanges, BigDecimal.ZERO);

    for (TransactionItem tx : transactions) {
      if (tx.createdAt() == null) {
        continue;
      }
      YearMonth txMonth = YearMonth.from(tx.createdAt());
      int idx = months.indexOf(txMonth);
      if (idx < 0) {
        // Transaction outside the 6-month window; ignore
        continue;
      }
      BigDecimal change =
          "CREDIT".equalsIgnoreCase(tx.direction()) ? tx.amount() : tx.amount().negate();
      netChanges[idx] = netChanges[idx].add(change);
    }

    // Walk backward from current balance to reconstruct ending balances
    // months[5] (current month) ending balance = currentBalance
    BigDecimal[] balances = new BigDecimal[6];
    balances[5] = currentBalance;
    for (int i = 4; i >= 0; i--) {
      // balance[i] = balance[i+1] - netChange[i+1]
      balances[i] = balances[i + 1].subtract(netChanges[i + 1]);
    }

    // Build result list in chronological order
    List<BalancePoint> result = new ArrayList<>();
    for (int i = 0; i < 6; i++) {
      String monthName = months.get(i).getMonth().getDisplayName(TextStyle.SHORT, Locale.ENGLISH);
      result.add(new BalancePoint(monthName, balances[i]));
    }

    return result;
  }

  @GetMapping("/health")
  public Map<String, String> health() {
    return Map.of("status", "UP", "service", "account-service");
  }
}
