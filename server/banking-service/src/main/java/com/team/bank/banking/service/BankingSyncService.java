package com.team.bank.banking.service;

import com.team.bank.banking.client.EnableBankingClient;
import com.team.bank.banking.model.Account;
import com.team.bank.banking.model.AccountRepository;
import com.team.bank.banking.model.BankingConnection;
import com.team.bank.banking.model.BankingConnectionRepository;
import com.team.bank.banking.model.Transaction;
import com.team.bank.banking.model.TransactionRepository;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Pulls the latest balance and transactions for a linked account from Enable Banking and writes
 * them into the shared {@code accounts}/{@code transactions} tables. Each {@link BankingConnection}
 * is one linked bank; the single profile {@code accounts} row holds the aggregate across all of a
 * user's ACTIVE connections. Invoked once a connection becomes ACTIVE and on demand via the
 * controller's sync endpoint.
 */
@Service
public class BankingSyncService {

  private static final Logger log = LoggerFactory.getLogger(BankingSyncService.class);
  private static final String REPORTING_CURRENCY = "EUR";

  private final EnableBankingClient ebClient;
  private final AccountRepository accountRepository;
  private final TransactionRepository transactionRepository;
  private final BankingConnectionRepository bankingConnectionRepository;

  @SuppressFBWarnings("EI_EXPOSE_REP2")
  public BankingSyncService(
      EnableBankingClient ebClient,
      AccountRepository accountRepository,
      TransactionRepository transactionRepository,
      BankingConnectionRepository bankingConnectionRepository) {
    this.ebClient = ebClient;
    this.accountRepository = accountRepository;
    this.transactionRepository = transactionRepository;
    this.bankingConnectionRepository = bankingConnectionRepository;
  }

  /**
   * Syncs one linked bank: stores its balance/currency on the connection row, replaces its
   * transactions, then recomputes the profile account's aggregate. Safe to call repeatedly.
   */
  @SuppressWarnings("unchecked")
  public void syncAccount(BankingConnection connection) {
    String externalUid = connection.getExternalAccountUid();
    // Without an external account UID every Enable Banking call would hit /accounts/null/...
    // Skip the sync (and don't touch the connection) so it isn't falsely marked as synced.
    if (externalUid == null || externalUid.isBlank()) {
      log.warn(
          "Skipping sync for connection {} (bank: {}): no external account UID",
          connection.getId(),
          connection.getBankName());
      return;
    }

    syncBalance(connection, externalUid);
    syncTransactions(connection, externalUid);

    connection.setUpdatedAt(LocalDateTime.now(ZoneId.systemDefault()));
    bankingConnectionRepository.save(connection);

    recomputeAggregate(connection.getAccountId());

    log.info(
        "Synced connection {} (bank: {}, balance: {} {})",
        connection.getId(),
        connection.getBankName(),
        connection.getBalance(),
        connection.getCurrency());
  }

  @SuppressWarnings("unchecked")
  private void syncBalance(BankingConnection connection, String externalUid) {
    Map<String, Object> balanceResponse = ebClient.getBalances(externalUid);
    if (balanceResponse == null) {
      return;
    }
    List<Map<String, Object>> balances =
        (List<Map<String, Object>>) balanceResponse.get("balances");
    if (balances == null || balances.isEmpty()) {
      return;
    }
    // Banks report several balance types; prefer the closing booked balance (CLBD)
    // over interim ones, falling back to whatever comes first.
    Map<String, Object> chosen =
        balances.stream()
            .filter(b -> "CLBD".equalsIgnoreCase(String.valueOf(field(b, "balance_type"))))
            .findFirst()
            .orElse(balances.get(0));
    Map<String, Object> balanceAmount = (Map<String, Object>) field(chosen, "balance_amount");
    if (balanceAmount == null || balanceAmount.get("amount") == null) {
      return;
    }
    try {
      connection.setBalance(new BigDecimal(balanceAmount.get("amount").toString()));
    } catch (NumberFormatException e) {
      log.warn("Could not parse balance '{}'", balanceAmount.get("amount"));
      return;
    }
    Object currency = balanceAmount.get("currency");
    if (currency != null) {
      connection.setCurrency(currency.toString());
    }
  }

  @SuppressWarnings("unchecked")
  private void syncTransactions(BankingConnection connection, String externalUid) {
    Map<String, Object> txResponse = ebClient.getTransactions(externalUid);
    if (txResponse == null) {
      return;
    }
    List<Map<String, Object>> transactions =
        (List<Map<String, Object>>) txResponse.get("transactions");
    if (transactions == null) {
      return;
    }

    // Replace-by-connection: Enable Banking returns the full recent window on every call, so
    // clearing this connection's rows and re-inserting keeps repeated syncs idempotent without a
    // stable external transaction id (and never drops genuinely repeated payments).
    transactionRepository.deleteByConnectionId(connection.getId());

    for (Map<String, Object> tx : transactions) {
      // transaction_amount is a nested object ({amount, currency}) just like balance_amount, so
      // read the inner "amount" rather than stringifying the whole map.
      BigDecimal txAmount = parseAmount(field(tx, "transaction_amount"));
      if (txAmount == null) {
        continue;
      }
      String creditDebitIndicator = String.valueOf(field(tx, "credit_debit_indicator"));
      String direction =
          "CRDT".equalsIgnoreCase(creditDebitIndicator)
                  || "CREDIT".equalsIgnoreCase(creditDebitIndicator)
              ? "CREDIT"
              : "DEBIT";

      String counterparty = counterpartyName(tx, direction);

      Transaction newTx = new Transaction();
      newTx.setId(UUID.randomUUID());
      newTx.setAccountId(connection.getAccountId());
      newTx.setConnectionId(connection.getId());
      newTx.setBankName(connection.getBankName());
      newTx.setCounterparty(counterparty);
      newTx.setCategory(describe(tx, counterparty));
      newTx.setAmount(txAmount);
      newTx.setDirection(direction);
      newTx.setCreatedAt(parseTransactionDate(tx));
      transactionRepository.save(newTx);
    }
  }

  /**
   * Reads an Enable Banking response field. The API serializes snake_case; the camelCase fallback
   * keeps us tolerant of the Berlin-Group style some bank payloads use.
   */
  private static Object field(Map<String, Object> source, String snakeCase) {
    Object value = source.get(snakeCase);
    if (value != null) {
      return value;
    }
    StringBuilder camel = new StringBuilder();
    boolean upper = false;
    for (int i = 0; i < snakeCase.length(); i++) {
      char c = snakeCase.charAt(i);
      if (c == '_') {
        upper = true;
      } else {
        camel.append(upper ? Character.toUpperCase(c) : c);
        upper = false;
      }
    }
    return source.get(camel.toString());
  }

  /**
   * Human-readable description for a transaction: the bank's remittance info when present (Enable
   * Banking sends it as a list of lines), otherwise the already-resolved counterparty name.
   */
  private static String describe(Map<String, Object> tx, String counterparty) {
    Object remittance = field(tx, "remittance_information");
    if (remittance instanceof List<?> lines && !lines.isEmpty()) {
      String joined =
          lines.stream()
              .filter(l -> l != null && !l.toString().isBlank())
              .map(Object::toString)
              .reduce((a, b) -> a + " " + b)
              .orElse("");
      if (!joined.isBlank()) {
        return joined;
      }
    } else if (remittance != null && !remittance.toString().isBlank()) {
      return remittance.toString();
    }
    return counterparty != null ? counterparty : "Uncategorized";
  }

  /**
   * The transaction's counterparty name: creditor for money going out (DEBIT), debtor for money
   * coming in (CREDIT). Null when the bank doesn't provide one.
   */
  private static String counterpartyName(Map<String, Object> tx, String direction) {
    Object party = "DEBIT".equals(direction) ? tx.get("creditor") : tx.get("debtor");
    if (party instanceof Map<?, ?> cp && cp.get("name") != null) {
      String name = cp.get("name").toString();
      if (!name.isBlank()) {
        return name;
      }
    }
    return null;
  }

  /**
   * Recomputes the profile account's aggregate from its ACTIVE connections: balance = sum per
   * currency (single currency written as-is; mixed currencies fall back to the EUR subtotal since
   * there is no FX service), and customer name = the most recently synced connection's account name
   * (falling back to its bank name).
   */
  private void recomputeAggregate(UUID accountId) {
    Account account = accountRepository.findById(accountId).orElse(null);
    if (account == null) {
      return;
    }
    List<BankingConnection> active =
        bankingConnectionRepository.findByAccountIdAndStatus(accountId, "ACTIVE");

    Map<String, BigDecimal> byCurrency = new LinkedHashMap<>();
    for (BankingConnection c : active) {
      if (c.getBalance() == null) {
        continue;
      }
      String currency = c.getCurrency() != null ? c.getCurrency() : REPORTING_CURRENCY;
      byCurrency.merge(currency, c.getBalance(), BigDecimal::add);
    }

    BigDecimal total;
    if (byCurrency.isEmpty()) {
      total = BigDecimal.ZERO;
    } else if (byCurrency.size() == 1) {
      total = byCurrency.values().iterator().next();
    } else {
      total = byCurrency.getOrDefault(REPORTING_CURRENCY, BigDecimal.ZERO);
      log.warn(
          "Account {} has balances in multiple currencies {}; aggregate uses the {} subtotal only",
          accountId,
          byCurrency.keySet(),
          REPORTING_CURRENCY);
    }
    account.setBalance(total);

    active.stream()
        .max(Comparator.comparing(BankingConnection::getUpdatedAt))
        .ifPresent(
            primary ->
                account.setCustomerName(
                    primary.getAccountName() != null && !primary.getAccountName().isBlank()
                        ? primary.getAccountName()
                        : primary.getBankName()));

    account.setUpdatedAt(LocalDateTime.now(ZoneId.systemDefault()));
    accountRepository.save(account);
  }

  /**
   * Uses the Enable Banking booking date (falling back to value date) as the transaction timestamp
   * so the dashboard trend buckets transactions into the month they actually occurred. Dates are
   * plain {@code yyyy-MM-dd} strings; falls back to now() when absent/unparseable so one odd row
   * can't abort the whole sync.
   */
  private LocalDateTime parseTransactionDate(Map<String, Object> tx) {
    Object date = field(tx, "booking_date");
    if (date == null) {
      date = field(tx, "value_date");
    }
    if (date != null) {
      try {
        return LocalDate.parse(date.toString()).atStartOfDay();
      } catch (DateTimeParseException e) {
        log.warn("Could not parse transaction date '{}', using now()", date);
      }
    }
    return LocalDateTime.now(ZoneId.systemDefault());
  }

  /**
   * Extracts a monetary value from an Enable Banking amount field, which may be either a nested
   * object ({@code {amount, currency}}) or a bare scalar. Returns null when no usable amount is
   * present so callers can skip the record instead of failing the whole sync.
   */
  @SuppressWarnings("unchecked")
  private BigDecimal parseAmount(Object amountField) {
    Object raw =
        amountField instanceof Map
            ? ((Map<String, Object>) amountField).get("amount")
            : amountField;
    if (raw == null) {
      return null;
    }
    try {
      return new BigDecimal(raw.toString());
    } catch (NumberFormatException e) {
      log.warn("Could not parse transaction amount '{}'", raw);
      return null;
    }
  }
}
