package com.team.bank.transaction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("Expense breakdown algorithm")
class ExpenseBreakdownTest {

  private static Transaction tx(String category, String amount, String direction) {
    return tx(category, amount, direction, null);
  }

  private static Transaction tx(
      String category, String amount, String direction, String counterparty) {
    Transaction t = new Transaction();
    t.setCategory(category);
    t.setAmount(new BigDecimal(amount));
    t.setDirection(direction);
    t.setCounterparty(counterparty);
    return t;
  }

  @Nested
  @DisplayName("empty / edge inputs")
  class EmptyEdgeCases {

    @Test
    @DisplayName("should return empty list when transaction list is empty")
    void shouldHandleEmptyTransactionList() {
      assertTrue(ExpenseBreakdown.compute(List.of()).isEmpty());
    }

    @Test
    @DisplayName("should return empty list when only credit transactions exist")
    void shouldIgnoreCreditTransactions() {
      List<Transaction> txs =
          List.of(tx("Income", "5000", "CREDIT"), tx("Refund", "100", "CREDIT"));
      assertTrue(ExpenseBreakdown.compute(txs).isEmpty());
    }

    @Test
    @DisplayName("should return empty list when total debit amount is zero")
    void shouldReturnEmptyWhenTotalIsZero() {
      List<Transaction> txs = List.of(tx("Food", "0", "DEBIT"));
      assertTrue(ExpenseBreakdown.compute(txs).isEmpty());
    }
  }

  @Nested
  @DisplayName("grouping and percentage calculation")
  class GroupingAndPercentage {

    @Test
    @DisplayName("should group multiple transactions in the same category")
    void shouldGroupMultipleTransactionsSameCategory() {
      List<Transaction> txs =
          List.of(tx("Food", "30", "DEBIT"), tx("Food", "20", "DEBIT"), tx("Rent", "50", "DEBIT"));

      List<ExpenseSlice> result = ExpenseBreakdown.compute(txs);

      assertEquals(2, result.size());
      ExpenseSlice food =
          result.stream().filter(e -> "Food".equals(e.category())).findFirst().orElseThrow();
      assertEquals(new BigDecimal("50"), food.percentage());
      assertEquals(new BigDecimal("50"), food.amount());
      assertEquals(2, food.count());
    }

    @Test
    @DisplayName("should sort categories descending by percentage")
    void shouldSortExpensesDescendingByPercentage() {
      List<Transaction> txs =
          List.of(
              tx("Coffee", "10", "DEBIT"), tx("Rent", "80", "DEBIT"), tx("Food", "10", "DEBIT"));

      List<ExpenseSlice> result = ExpenseBreakdown.compute(txs);

      assertEquals(3, result.size());
      assertEquals("Rent", result.get(0).category());
      assertTrue(result.get(0).percentage().compareTo(result.get(1).percentage()) >= 0);
      assertTrue(result.get(1).percentage().compareTo(result.get(2).percentage()) >= 0);
    }

    @Test
    @DisplayName("should assign 100% when there is only a single expense category")
    void shouldHandleSingleExpenseCategory() {
      List<ExpenseSlice> result = ExpenseBreakdown.compute(List.of(tx("Rent", "500", "DEBIT")));

      assertEquals(1, result.size());
      assertEquals("Rent", result.get(0).category());
      assertEquals(new BigDecimal("100"), result.get(0).percentage());
    }
  }

  @Nested
  @DisplayName("rounding and precision")
  class RoundingAndPrecision {

    @Test
    @DisplayName("should round percentages to whole numbers using HALF_UP")
    void shouldRoundPercentagesToWholeNumbers() {
      List<Transaction> txs = List.of(tx("A", "33", "DEBIT"), tx("B", "67", "DEBIT"));

      List<ExpenseSlice> result = ExpenseBreakdown.compute(txs);

      long sum =
          result.stream().map(ExpenseSlice::percentage).mapToLong(BigDecimal::longValue).sum();
      assertTrue(sum >= 99 && sum <= 100, "sum of percentages should be ~100, but was " + sum);
    }

    @Test
    @DisplayName("should handle very small transaction amounts")
    void shouldHandleVerySmallAmounts() {
      List<Transaction> txs = List.of(tx("A", "0.01", "DEBIT"), tx("B", "0.01", "DEBIT"));

      List<ExpenseSlice> result = ExpenseBreakdown.compute(txs);

      assertEquals(2, result.size());
      assertEquals(new BigDecimal("50"), result.get(0).percentage());
      assertEquals(new BigDecimal("50"), result.get(1).percentage());
    }
  }

  @Nested
  @DisplayName("Other handling and merchants")
  class OtherAndMerchants {

    @Test
    @DisplayName("should fold null, blank and legacy Uncategorized categories into Other")
    void shouldFoldUnknownIntoOther() {
      List<Transaction> txs =
          List.of(
              tx(null, "10", "DEBIT"),
              tx("", "10", "DEBIT"),
              tx("Uncategorized", "10", "DEBIT"),
              tx("Food", "5", "DEBIT"));

      List<ExpenseSlice> result = ExpenseBreakdown.compute(txs);

      assertEquals(2, result.size());
      ExpenseSlice other =
          result.stream().filter(e -> "Other".equals(e.category())).findFirst().orElseThrow();
      assertEquals(new BigDecimal("30"), other.amount());
      assertEquals(3, other.count());
    }

    @Test
    @DisplayName("should exclude Transfers from the breakdown entirely")
    void shouldExcludeTransfers() {
      List<Transaction> txs = List.of(tx("Transfers", "900", "DEBIT"), tx("Food", "100", "DEBIT"));

      List<ExpenseSlice> result = ExpenseBreakdown.compute(txs);

      assertEquals(1, result.size());
      assertEquals("Food", result.get(0).category());
      assertEquals(new BigDecimal("100"), result.get(0).percentage());
    }

    @Test
    @DisplayName("should sort Other last even when it is the largest slice")
    void shouldSortOtherLast() {
      List<Transaction> txs =
          List.of(tx("Other", "90", "DEBIT"), tx("Food", "5", "DEBIT"), tx("Rent", "5", "DEBIT"));

      List<ExpenseSlice> result = ExpenseBreakdown.compute(txs);

      assertEquals(3, result.size());
      assertEquals("Other", result.get(2).category());
    }

    @Test
    @DisplayName("should list the top two merchants by spend, skipping missing counterparties")
    void shouldListTopMerchantsBySpend() {
      List<Transaction> txs =
          List.of(
              tx("Groceries", "30", "DEBIT", "REWE"),
              tx("Groceries", "25", "DEBIT", "REWE"),
              tx("Groceries", "40", "DEBIT", "ALDI"),
              tx("Groceries", "10", "DEBIT", "EDEKA"),
              tx("Groceries", "10", "DEBIT", null));

      List<ExpenseSlice> result = ExpenseBreakdown.compute(txs);

      assertEquals(1, result.size());
      assertEquals(List.of("REWE", "ALDI"), result.get(0).topMerchants());
      assertEquals(5, result.get(0).count());
    }
  }
}
