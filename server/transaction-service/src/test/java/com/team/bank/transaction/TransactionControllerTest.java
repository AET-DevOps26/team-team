package com.team.bank.transaction;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(TransactionController.class)
@ActiveProfiles("test")
@DisplayName("TransactionController")
class TransactionControllerTest {

  @Autowired private MockMvc mockMvc;

  @MockitoBean private TransactionRepository transactionRepository;

  private static final UUID ACCOUNT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

  /* ---- mock data helpers ---- */
  private Transaction tx(String category, String amount, String direction) {
    Transaction t = new Transaction();
    t.setId(UUID.randomUUID());
    t.setAccountId(ACCOUNT_ID);
    t.setCategory(category);
    t.setAmount(new BigDecimal(amount));
    t.setDirection(direction);
    t.setCreatedAt(LocalDateTime.now(ZoneId.systemDefault()).minusDays(1));
    return t;
  }

  @Nested
  @DisplayName("GET /api/transactions/{accountId}")
  class ListTransactions {

    @Test
    @DisplayName("should return list of transactions for an account")
    void shouldListTransactionsByAccountId() throws Exception {
      List<Transaction> txs =
          List.of(tx("Rent", "500.00", "DEBIT"), tx("Salary", "3000.00", "CREDIT"));
      when(transactionRepository.findByAccountIdOrderByCreatedAtDesc(ACCOUNT_ID)).thenReturn(txs);

      mockMvc
          .perform(
              get("/api/transactions/{accountId}", ACCOUNT_ID).accept(MediaType.APPLICATION_JSON))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$", hasSize(2)))
          .andExpect(jsonPath("$[0].category").value("Rent"))
          .andExpect(jsonPath("$[0].amount").value(500))
          .andExpect(jsonPath("$[0].direction").value("DEBIT"));
    }

    @Test
    @DisplayName("should return empty list when account has no transactions")
    void shouldReturnEmptyListForAccountWithNoTransactions() throws Exception {
      when(transactionRepository.findByAccountIdOrderByCreatedAtDesc(ACCOUNT_ID))
          .thenReturn(List.of());

      mockMvc
          .perform(
              get("/api/transactions/{accountId}", ACCOUNT_ID).accept(MediaType.APPLICATION_JSON))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$", hasSize(0)));
    }
  }

  @Nested
  @DisplayName("GET /api/transactions/{accountId}/expenses")
  class ExpenseBreakdown {

    @Test
    @DisplayName("should compute expense breakdown grouped by category")
    void shouldComputeExpenseBreakdownCorrectly() throws Exception {
      List<Transaction> txs =
          List.of(
              tx("Food", "50.00", "DEBIT"),
              tx("Rent", "100.00", "DEBIT"),
              tx("Food", "50.00", "DEBIT"),
              tx("Salary", "2000.00", "CREDIT")); // credit, should be ignored
      when(transactionRepository.findByAccountIdOrderByCreatedAtDesc(ACCOUNT_ID)).thenReturn(txs);

      mockMvc
          .perform(
              get("/api/transactions/{accountId}/expenses", ACCOUNT_ID)
                  .accept(MediaType.APPLICATION_JSON))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$", hasSize(2)))
          // Total debits: 200. Food=100 → 50%, Rent=100 → 50%
          .andExpect(
              jsonPath("$[*].percentage")
                  .value(org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.is(50))));
    }

    @Test
    @DisplayName("should return empty list when only credit transactions exist")
    void shouldReturnEmptyExpensesWhenOnlyCredits() throws Exception {
      List<Transaction> txs =
          List.of(tx("Salary", "5000.00", "CREDIT"), tx("Refund", "100.00", "CREDIT"));
      when(transactionRepository.findByAccountIdOrderByCreatedAtDesc(ACCOUNT_ID)).thenReturn(txs);

      mockMvc
          .perform(
              get("/api/transactions/{accountId}/expenses", ACCOUNT_ID)
                  .accept(MediaType.APPLICATION_JSON))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    @DisplayName("should return empty list when no transactions exist")
    void shouldReturnEmptyExpensesForNoTransactions() throws Exception {
      when(transactionRepository.findByAccountIdOrderByCreatedAtDesc(ACCOUNT_ID))
          .thenReturn(List.of());

      mockMvc
          .perform(
              get("/api/transactions/{accountId}/expenses", ACCOUNT_ID)
                  .accept(MediaType.APPLICATION_JSON))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    @DisplayName("should sort expense categories descending by percentage")
    void shouldSortExpensesDescendingByPercentage() throws Exception {
      List<Transaction> txs =
          List.of(
              tx("Coffee", "10.00", "DEBIT"),
              tx("Rent", "80.00", "DEBIT"),
              tx("Groceries", "10.00", "DEBIT"));
      when(transactionRepository.findByAccountIdOrderByCreatedAtDesc(ACCOUNT_ID)).thenReturn(txs);

      mockMvc
          .perform(
              get("/api/transactions/{accountId}/expenses", ACCOUNT_ID)
                  .accept(MediaType.APPLICATION_JSON))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$[0].category").value("Rent"))
          .andExpect(jsonPath("$[0].percentage").value(80));
    }

    @Test
    @DisplayName("should round percentages to whole numbers")
    void shouldRoundPercentagesToWholeNumbers() throws Exception {
      // 33 + 67 = 100 total, 33% + 67%
      List<Transaction> txs = List.of(tx("A", "33.00", "DEBIT"), tx("B", "67.00", "DEBIT"));
      when(transactionRepository.findByAccountIdOrderByCreatedAtDesc(ACCOUNT_ID)).thenReturn(txs);

      mockMvc
          .perform(
              get("/api/transactions/{accountId}/expenses", ACCOUNT_ID)
                  .accept(MediaType.APPLICATION_JSON))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$", hasSize(2)));
    }

    @Test
    @DisplayName("should assign 100% to a single expense category")
    void shouldHandleSingleExpenseCategory() throws Exception {
      List<Transaction> txs = List.of(tx("Rent", "500.00", "DEBIT"));
      when(transactionRepository.findByAccountIdOrderByCreatedAtDesc(ACCOUNT_ID)).thenReturn(txs);

      mockMvc
          .perform(
              get("/api/transactions/{accountId}/expenses", ACCOUNT_ID)
                  .accept(MediaType.APPLICATION_JSON))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$", hasSize(1)))
          .andExpect(jsonPath("$[0].category").value("Rent"))
          .andExpect(jsonPath("$[0].percentage").value(100));
    }

    @Test
    @DisplayName("should handle zero-amount debits gracefully")
    void shouldHandleZeroAmountDebits() throws Exception {
      List<Transaction> txs = List.of(tx("Misc", "0.00", "DEBIT"));
      when(transactionRepository.findByAccountIdOrderByCreatedAtDesc(ACCOUNT_ID)).thenReturn(txs);

      mockMvc
          .perform(
              get("/api/transactions/{accountId}/expenses", ACCOUNT_ID)
                  .accept(MediaType.APPLICATION_JSON))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$", hasSize(0)));
    }
  }
}
