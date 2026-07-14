package com.team.bank.banking.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.team.bank.banking.client.EnableBankingClient;
import com.team.bank.banking.model.Account;
import com.team.bank.banking.model.AccountRepository;
import com.team.bank.banking.model.BankingConnection;
import com.team.bank.banking.model.BankingConnectionRepository;
import com.team.bank.banking.model.Transaction;
import com.team.bank.banking.model.TransactionRepository;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class BankingSyncServiceTest {

  private static final UUID ACCOUNT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

  private BankingConnection connection(String bank, String uid, BigDecimal balance) {
    BankingConnection c = new BankingConnection();
    c.setId(UUID.randomUUID());
    c.setAccountId(ACCOUNT_ID);
    c.setBankName(bank);
    c.setExternalAccountUid(uid);
    c.setStatus("ACTIVE");
    c.setBalance(balance);
    c.setCurrency(balance == null ? null : "EUR");
    c.setUpdatedAt(LocalDateTime.now(ZoneId.systemDefault()));
    return c;
  }

  // Enable Banking serializes snake_case (e.g. {"balance_amount": {"currency": "EUR",
  // "amount": "95.29"}, "balance_type": "ITBD"}).
  private Map<String, Object> balance(String amount, String currency, String type) {
    return Map.of(
        "balance_amount", Map.of("amount", amount, "currency", currency), "balance_type", type);
  }

  private Map<String, Object> tx(String amount, String indicator, String desc, String bookingDate) {
    return Map.of(
        "transaction_amount",
        Map.of("amount", amount, "currency", "EUR"),
        "credit_debit_indicator",
        indicator,
        "remittance_information",
        List.of(desc),
        "booking_date",
        bookingDate);
  }

  @Test
  void syncStoresBalanceReplacesTransactionsAndRecomputesAggregate() {
    EnableBankingClient eb = mock(EnableBankingClient.class);
    AccountRepository accounts = mock(AccountRepository.class);
    TransactionRepository transactions = mock(TransactionRepository.class);
    BankingConnectionRepository connections = mock(BankingConnectionRepository.class);

    BankingConnection conn = connection("N26", "uid-1", null);
    Account account = new Account();
    account.setId(ACCOUNT_ID);
    account.setCustomerName("My accounts");
    account.setBalance(BigDecimal.ZERO);

    // Several balance types: the closing booked (CLBD) one must win over interim (ITBD).
    when(eb.getBalances("uid-1"))
        .thenReturn(
            Map.of(
                "balances",
                List.of(balance("55.18", "EUR", "ITBD"), balance("1234.56", "EUR", "CLBD"))));
    when(eb.getTransactions("uid-1"))
        .thenReturn(
            Map.of(
                "transactions",
                List.of(
                    tx("10.00", "DBIT", "REWE SAGT DANKE", "2026-07-01"),
                    tx("2000.00", "CRDT", "SALARY", "2026-06-15"))));
    when(accounts.findById(ACCOUNT_ID)).thenReturn(java.util.Optional.of(account));
    // Aggregate re-reads the active connections; conn now carries its synced balance.
    when(connections.findByAccountIdAndStatus(ACCOUNT_ID, "ACTIVE")).thenReturn(List.of(conn));

    new BankingSyncService(eb, accounts, transactions, connections).syncAccount(conn);

    // Per-connection balance + currency stored.
    assertThat(conn.getBalance()).isEqualByComparingTo("1234.56");
    assertThat(conn.getCurrency()).isEqualTo("EUR");

    // Replace-by-connection: old rows cleared before inserting the fetched window.
    verify(transactions).deleteByConnectionId(conn.getId());

    ArgumentCaptor<Transaction> saved = ArgumentCaptor.forClass(Transaction.class);
    verify(transactions, times(2)).save(saved.capture());
    List<Transaction> rows = saved.getAllValues();
    assertThat(rows).allSatisfy(t -> assertThat(t.getConnectionId()).isEqualTo(conn.getId()));
    assertThat(rows).allSatisfy(t -> assertThat(t.getBankName()).isEqualTo("N26"));
    Transaction debit = rows.get(0);
    assertThat(debit.getDirection()).isEqualTo("DEBIT");
    assertThat(debit.getCategory()).isEqualTo("REWE SAGT DANKE");
    // Booking date drives created_at (not now()), so the trend buckets by the real month.
    assertThat(debit.getCreatedAt().getMonthValue()).isEqualTo(7);
    assertThat(rows.get(1).getDirection()).isEqualTo("CREDIT");
    assertThat(rows.get(1).getCreatedAt().getMonthValue()).isEqualTo(6);

    // Aggregate account reflects the single active bank's balance.
    assertThat(account.getBalance()).isEqualByComparingTo("1234.56");
    assertThat(account.getCustomerName()).isEqualTo("N26");
  }

  @Test
  void aggregateSumsMultipleActiveBanksInSameCurrency() {
    EnableBankingClient eb = mock(EnableBankingClient.class);
    AccountRepository accounts = mock(AccountRepository.class);
    TransactionRepository transactions = mock(TransactionRepository.class);
    BankingConnectionRepository connections = mock(BankingConnectionRepository.class);

    BankingConnection syncing = connection("N26", "uid-1", null);
    BankingConnection other = connection("Revolut", "uid-2", new BigDecimal("500.00"));
    other.setAccountName("Revolut Personal");
    Account account = new Account();
    account.setId(ACCOUNT_ID);
    account.setBalance(BigDecimal.ZERO);

    when(eb.getBalances("uid-1"))
        .thenReturn(Map.of("balances", List.of(balance("1000.00", "EUR", "CLBD"))));
    when(eb.getTransactions("uid-1")).thenReturn(Map.of("transactions", List.of()));
    when(accounts.findById(ACCOUNT_ID)).thenReturn(java.util.Optional.of(account));
    when(connections.findByAccountIdAndStatus(ACCOUNT_ID, "ACTIVE"))
        .thenReturn(List.of(syncing, other));

    new BankingSyncService(eb, accounts, transactions, connections).syncAccount(syncing);

    assertThat(account.getBalance()).isEqualByComparingTo("1500.00");
  }

  @Test
  void fallsBackToCounterpartyNameAndCamelCaseKeys() {
    EnableBankingClient eb = mock(EnableBankingClient.class);
    AccountRepository accounts = mock(AccountRepository.class);
    TransactionRepository transactions = mock(TransactionRepository.class);
    BankingConnectionRepository connections = mock(BankingConnectionRepository.class);

    BankingConnection conn = connection("N26", "uid-1", null);
    Account account = new Account();
    account.setId(ACCOUNT_ID);
    account.setBalance(BigDecimal.ZERO);

    // camelCase balance (Berlin-Group style payloads) must still parse via the fallback.
    when(eb.getBalances("uid-1"))
        .thenReturn(
            Map.of(
                "balances",
                List.of(Map.of("balanceAmount", Map.of("amount", "77.00", "currency", "EUR")))));
    // No remittance info: the creditor name is the best available description for a debit.
    when(eb.getTransactions("uid-1"))
        .thenReturn(
            Map.of(
                "transactions",
                List.of(
                    Map.of(
                        "transaction_amount",
                        Map.of("amount", "3.03", "currency", "EUR"),
                        "credit_debit_indicator",
                        "DBIT",
                        "creditor",
                        Map.of("name", "Ella Mäkinen"),
                        "booking_date",
                        "2026-06-30"))));
    when(accounts.findById(ACCOUNT_ID)).thenReturn(java.util.Optional.of(account));
    when(connections.findByAccountIdAndStatus(ACCOUNT_ID, "ACTIVE")).thenReturn(List.of(conn));

    new BankingSyncService(eb, accounts, transactions, connections).syncAccount(conn);

    assertThat(conn.getBalance()).isEqualByComparingTo("77.00");
    ArgumentCaptor<Transaction> saved = ArgumentCaptor.forClass(Transaction.class);
    verify(transactions).save(saved.capture());
    assertThat(saved.getValue().getCategory()).isEqualTo("Ella Mäkinen");
  }

  @Test
  void skipsSyncWhenExternalUidMissing() {
    EnableBankingClient eb = mock(EnableBankingClient.class);
    AccountRepository accounts = mock(AccountRepository.class);
    TransactionRepository transactions = mock(TransactionRepository.class);
    BankingConnectionRepository connections = mock(BankingConnectionRepository.class);

    BankingConnection conn = connection("N26", null, null);

    new BankingSyncService(eb, accounts, transactions, connections).syncAccount(conn);

    verify(eb, times(0)).getBalances(any());
    verify(transactions, times(0)).deleteByConnectionId(any());
    verify(accounts, times(0)).save(any());
  }
}
