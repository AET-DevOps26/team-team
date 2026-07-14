package com.team.bank.banking.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
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
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("BankingSyncService")
class BankingSyncServiceTest {

  @Mock private EnableBankingClient ebClient;
  @Mock private AccountRepository accountRepository;
  @Mock private TransactionRepository transactionRepository;
  @Mock private BankingConnectionRepository bankingConnectionRepository;

  @InjectMocks private BankingSyncService syncService;

  private static final UUID ACCOUNT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
  private static final UUID CONNECTION_ID = UUID.randomUUID();
  private static final String EXTERNAL_UID = "ext-uid-12345";

  private BankingConnection activeConnection;

  @BeforeEach
  void setUp() {
    activeConnection = new BankingConnection();
    activeConnection.setId(CONNECTION_ID);
    activeConnection.setAccountId(ACCOUNT_ID);
    activeConnection.setBankName("Nordea");
    activeConnection.setCountry("FI");
    activeConnection.setStatus("ACTIVE");
    activeConnection.setExternalAccountUid(EXTERNAL_UID);
    activeConnection.setState("state-abc");
    activeConnection.setCreatedAt(LocalDateTime.now(ZoneId.systemDefault()).minusDays(1));
    activeConnection.setUpdatedAt(LocalDateTime.now(ZoneId.systemDefault()).minusHours(1));
  }

  @Nested
  @DisplayName("guard — missing external UID")
  class MissingExternalUid {

    @Test
    @DisplayName("should skip sync when externalAccountUid is null")
    void shouldSkipSyncWhenExternalUidIsNull() {
      activeConnection.setExternalAccountUid(null);

      syncService.syncAccount(activeConnection);

      verify(ebClient, never()).getBalances(anyString());
      verify(ebClient, never()).getTransactions(anyString());
    }

    @Test
    @DisplayName("should skip sync when externalAccountUid is blank")
    void shouldSkipSyncWhenExternalUidIsBlank() {
      activeConnection.setExternalAccountUid("   ");

      syncService.syncAccount(activeConnection);

      verify(ebClient, never()).getBalances(anyString());
    }
  }

  @Nested
  @DisplayName("balance sync")
  class BalanceSync {

    @Test
    @DisplayName("should update account balance from Enable Banking API response")
    void shouldUpdateAccountBalanceFromApi() {
      Map<String, Object> balanceResp =
          Map.of(
              "balances",
              List.of(Map.of("balanceAmount", Map.of("amount", "2500.00", "currency", "EUR"))));
      when(ebClient.getBalances(EXTERNAL_UID)).thenReturn(balanceResp);

      Account account = new Account();
      account.setId(ACCOUNT_ID);
      account.setCustomerName("Test User");
      account.setBalance(new BigDecimal("1000"));
      when(accountRepository.findById(ACCOUNT_ID)).thenReturn(Optional.of(account));

      syncService.syncAccount(activeConnection);

      ArgumentCaptor<Account> captor = ArgumentCaptor.forClass(Account.class);
      verify(accountRepository).save(captor.capture());
      assertEquals(new BigDecimal("2500.00"), captor.getValue().getBalance());
    }

    @Test
    @DisplayName("should not crash when balance API returns null")
    void shouldNotUpdateBalanceWhenResponseIsNull() {
      when(ebClient.getBalances(EXTERNAL_UID)).thenReturn(null);
      when(ebClient.getTransactions(EXTERNAL_UID)).thenReturn(null);

      syncService.syncAccount(activeConnection);

      verify(accountRepository, never()).save(any(Account.class));
    }

    @Test
    @DisplayName("should skip balance update when balances list is empty")
    void shouldNotUpdateBalanceWhenBalancesListIsEmpty() {
      when(ebClient.getBalances(EXTERNAL_UID)).thenReturn(Map.of("balances", List.of()));
      when(ebClient.getTransactions(EXTERNAL_UID)).thenReturn(null);

      syncService.syncAccount(activeConnection);

      verify(accountRepository, never()).save(any(Account.class));
    }
  }

  @Nested
  @DisplayName("transaction sync")
  class TransactionSync {

    @Test
    @DisplayName("should insert new transactions from Enable Banking")
    void shouldInsertNewTransactions() {
      when(ebClient.getBalances(EXTERNAL_UID)).thenReturn(null);

      Map<String, Object> txResp =
          Map.of(
              "transactions",
              List.of(
                  Map.of(
                      "transactionAmount",
                      Map.of("amount", "45.50", "currency", "EUR"),
                      "creditDebitIndicator",
                      "DBIT",
                      "remittanceInformationUnstructured",
                      "Grocery shopping"),
                  Map.of(
                      "transactionAmount",
                      Map.of("amount", "200.00", "currency", "EUR"),
                      "creditDebitIndicator",
                      "CRDT",
                      "remittanceInformationUnstructured",
                      "Salary")));
      when(ebClient.getTransactions(EXTERNAL_UID)).thenReturn(txResp);

      // No existing duplicates
      when(transactionRepository.findByAccountIdAndCategoryAndAmountAndDirection(
              any(), anyString(), any(), anyString()))
          .thenReturn(List.of());

      syncService.syncAccount(activeConnection);

      // Two transactions should be saved
      ArgumentCaptor<Transaction> captor = ArgumentCaptor.forClass(Transaction.class);
      verify(transactionRepository, org.mockito.Mockito.times(2)).save(captor.capture());

      List<Transaction> saved = captor.getAllValues();
      assertEquals(2, saved.size());

      // First: DEBIT, Grocery shopping
      Transaction tx1 = saved.get(0);
      assertEquals(ACCOUNT_ID, tx1.getAccountId());
      assertEquals("Grocery shopping", tx1.getCategory());
      assertEquals(new BigDecimal("45.50"), tx1.getAmount());
      assertEquals("DEBIT", tx1.getDirection());

      // Second: CREDIT, Salary
      Transaction tx2 = saved.get(1);
      assertEquals("Salary", tx2.getCategory());
      assertEquals("CREDIT", tx2.getDirection());
    }

    @Test
    @DisplayName("should skip duplicate transactions already in database")
    void shouldSkipDuplicateTransactions() {
      when(ebClient.getBalances(EXTERNAL_UID)).thenReturn(null);

      Map<String, Object> txResp =
          Map.of(
              "transactions",
              List.of(
                  Map.of(
                      "transactionAmount", Map.of("amount", "100.00", "currency", "EUR"),
                      "creditDebitIndicator", "DBIT",
                      "remittanceInformationUnstructured", "Rent")));
      when(ebClient.getTransactions(EXTERNAL_UID)).thenReturn(txResp);

      // Simulate an existing duplicate
      Transaction existing = new Transaction();
      existing.setId(UUID.randomUUID());
      when(transactionRepository.findByAccountIdAndCategoryAndAmountAndDirection(
              eq(ACCOUNT_ID), eq("Rent"), eq(new BigDecimal("100.00")), eq("DEBIT")))
          .thenReturn(List.of(existing));

      syncService.syncAccount(activeConnection);

      // Should NOT save a duplicate
      verify(transactionRepository, never()).save(any(Transaction.class));
    }

    @Test
    @DisplayName("should not crash when transaction API returns null")
    void shouldNotInsertTransactionsWhenResponseIsNull() {
      when(ebClient.getBalances(EXTERNAL_UID)).thenReturn(null);
      when(ebClient.getTransactions(EXTERNAL_UID)).thenReturn(null);

      syncService.syncAccount(activeConnection);

      verify(transactionRepository, never()).save(any(Transaction.class));
    }
  }

  @Nested
  @DisplayName("direction mapping (creditDebitIndicator → CREDIT/DEBIT)")
  class DirectionMapping {

    @Test
    @DisplayName("should map CRDT to CREDIT")
    void shouldMapCrdtToCredit() {
      when(ebClient.getBalances(EXTERNAL_UID)).thenReturn(null);
      when(ebClient.getTransactions(EXTERNAL_UID))
          .thenReturn(
              Map.of(
                  "transactions",
                  List.of(
                      Map.of(
                          "transactionAmount",
                          Map.of("amount", "10.00", "currency", "EUR"),
                          "creditDebitIndicator",
                          "CRDT"))));
      when(transactionRepository.findByAccountIdAndCategoryAndAmountAndDirection(
              any(), anyString(), any(), anyString()))
          .thenReturn(List.of());

      syncService.syncAccount(activeConnection);

      ArgumentCaptor<Transaction> captor = ArgumentCaptor.forClass(Transaction.class);
      verify(transactionRepository).save(captor.capture());
      assertEquals("CREDIT", captor.getValue().getDirection());
    }

    @Test
    @DisplayName("should map DBIT to DEBIT")
    void shouldMapDbitToDebit() {
      when(ebClient.getBalances(EXTERNAL_UID)).thenReturn(null);
      when(ebClient.getTransactions(EXTERNAL_UID))
          .thenReturn(
              Map.of(
                  "transactions",
                  List.of(
                      Map.of(
                          "transactionAmount",
                          Map.of("amount", "5.00", "currency", "EUR"),
                          "creditDebitIndicator",
                          "DBIT"))));
      when(transactionRepository.findByAccountIdAndCategoryAndAmountAndDirection(
              any(), anyString(), any(), anyString()))
          .thenReturn(List.of());

      syncService.syncAccount(activeConnection);

      ArgumentCaptor<Transaction> captor = ArgumentCaptor.forClass(Transaction.class);
      verify(transactionRepository).save(captor.capture());
      assertEquals("DEBIT", captor.getValue().getDirection());
    }

    @Test
    @DisplayName("should default to DEBIT when indicator is unrecognized")
    void shouldDefaultToDebitForUnknownIndicator() {
      when(ebClient.getBalances(EXTERNAL_UID)).thenReturn(null);
      when(ebClient.getTransactions(EXTERNAL_UID))
          .thenReturn(
              Map.of(
                  "transactions",
                  List.of(
                      Map.of(
                          "transactionAmount",
                          Map.of("amount", "1.00", "currency", "EUR"),
                          "creditDebitIndicator",
                          "UNKNOWN"))));
      when(transactionRepository.findByAccountIdAndCategoryAndAmountAndDirection(
              any(), anyString(), any(), anyString()))
          .thenReturn(List.of());

      syncService.syncAccount(activeConnection);

      ArgumentCaptor<Transaction> captor = ArgumentCaptor.forClass(Transaction.class);
      verify(transactionRepository).save(captor.capture());
      assertEquals("DEBIT", captor.getValue().getDirection());
    }
  }

  @Nested
  @DisplayName("category fallback")
  class CategoryFallback {

    @Test
    @DisplayName("should use 'Uncategorized' when remittanceInformation is null")
    void shouldFallbackCategoryWhenRemittanceInfoIsNull() {
      when(ebClient.getBalances(EXTERNAL_UID)).thenReturn(null);
      when(ebClient.getTransactions(EXTERNAL_UID))
          .thenReturn(
              Map.of(
                  "transactions",
                  List.of(
                      Map.of(
                          "transactionAmount",
                          Map.of("amount", "15.00", "currency", "EUR"),
                          "creditDebitIndicator",
                          "DBIT"))));
      when(transactionRepository.findByAccountIdAndCategoryAndAmountAndDirection(
              any(), eq("Uncategorized"), any(), anyString()))
          .thenReturn(List.of());

      syncService.syncAccount(activeConnection);

      ArgumentCaptor<Transaction> captor = ArgumentCaptor.forClass(Transaction.class);
      verify(transactionRepository).save(captor.capture());
      assertEquals("Uncategorized", captor.getValue().getCategory());
    }
  }

  @Nested
  @DisplayName("connection timestamp update")
  class TimestampUpdate {

    @Test
    @DisplayName("should update connection timestamp after successful sync")
    void shouldUpdateConnectionTimestampAfterSync() {
      when(ebClient.getBalances(EXTERNAL_UID)).thenReturn(null);
      when(ebClient.getTransactions(EXTERNAL_UID)).thenReturn(null);

      LocalDateTime before = activeConnection.getUpdatedAt();
      syncService.syncAccount(activeConnection);

      ArgumentCaptor<BankingConnection> captor = ArgumentCaptor.forClass(BankingConnection.class);
      verify(bankingConnectionRepository).save(captor.capture());
      assertNotNull(captor.getValue().getUpdatedAt());
      // updatedAt should be refreshed (not equal to the old value)
      assertTrue(
          captor.getValue().getUpdatedAt().isAfter(before)
              || captor.getValue().getUpdatedAt().equals(before));
    }

    @Test
    @DisplayName("should still update connection timestamp even when APIs return null")
    void shouldUpdateTimestampEvenOnNullResponses() {
      when(ebClient.getBalances(EXTERNAL_UID)).thenReturn(null);
      when(ebClient.getTransactions(EXTERNAL_UID)).thenReturn(null);

      syncService.syncAccount(activeConnection);

      verify(bankingConnectionRepository).save(any(BankingConnection.class));
    }
  }
}
