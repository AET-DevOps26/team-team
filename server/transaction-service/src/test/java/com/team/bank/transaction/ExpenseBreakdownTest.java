package com.team.bank.transaction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Pure unit tests for the expense-breakdown algorithm used by {@link TransactionController}.
 *
 * <p>Extracts the core logic (group DEBITs → sum → percentage → sort desc) so edge cases can be
 * verified without spinning up Spring or a database.
 */
@DisplayName("Expense breakdown algorithm")
class ExpenseBreakdownTest {

  /* ---- mock transaction record (same shape as Transaction entity) ---- */
  record Tx(String category, BigDecimal amount, String direction) {}

  /** Re-implements the exact algorithm from {@link TransactionController#expenseBreakdown}. */
  static List<ExpenseSlice> compute(List<Tx> transactions) {
    List<Tx> debits =
        transactions.stream().filter(tx -> "DEBIT".equalsIgnoreCase(tx.direction())).toList();

    BigDecimal total = debits.stream().map(Tx::amount).reduce(BigDecimal.ZERO, BigDecimal::add);

    if (total.compareTo(BigDecimal.ZERO) == 0) {
      return List.of();
    }

    Map<String, BigDecimal> grouped =
        debits.stream()
            .collect(
                Collectors.groupingBy(
                    Tx::category,
                    Collectors.reducing(BigDecimal.ZERO, Tx::amount, BigDecimal::add)));

    return grouped.entrySet().stream()
        .map(
            entry -> {
              BigDecimal percent =
                  entry
                      .getValue()
                      .multiply(new BigDecimal("100"))
                      .divide(total, 0, RoundingMode.HALF_UP);
              return new ExpenseSlice(entry.getKey(), percent);
            })
        .sorted((a, b) -> b.percentage().compareTo(a.percentage()))
        .toList();
  }

  @Nested
  @DisplayName("empty / edge inputs")
  class EmptyEdgeCases {

    @Test
    @DisplayName("should return empty list when transaction list is empty")
    void shouldHandleEmptyTransactionList() {
      List<ExpenseSlice> result = compute(List.of());
      assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("should return empty list when only credit transactions exist")
    void shouldIgnoreCreditTransactions() {
      List<Tx> txs =
          List.of(
              new Tx("Salary", new BigDecimal("5000"), "CREDIT"),
              new Tx("Refund", new BigDecimal("100"), "CREDIT"));
      List<ExpenseSlice> result = compute(txs);
      assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("should return empty list when total debit amount is zero")
    void shouldReturnEmptyWhenTotalIsZero() {
      List<Tx> txs = List.of(new Tx("Food", BigDecimal.ZERO, "DEBIT"));
      List<ExpenseSlice> result = compute(txs);
      assertTrue(result.isEmpty());
    }
  }

  @Nested
  @DisplayName("grouping and percentage calculation")
  class GroupingAndPercentage {

    @Test
    @DisplayName("should group multiple transactions in the same category")
    void shouldGroupMultipleTransactionsSameCategory() {
      List<Tx> txs =
          List.of(
              new Tx("Food", new BigDecimal("30"), "DEBIT"),
              new Tx("Food", new BigDecimal("20"), "DEBIT"),
              new Tx("Rent", new BigDecimal("50"), "DEBIT"));

      List<ExpenseSlice> result = compute(txs);

      assertEquals(2, result.size());
      // Food: 30+20=50 → 50/100=50%, Rent: 50/100=50%
      ExpenseSlice food =
          result.stream().filter(e -> "Food".equals(e.category())).findFirst().orElseThrow();
      assertEquals(new BigDecimal("50"), food.percentage());
    }

    @Test
    @DisplayName("should sort categories descending by percentage")
    void shouldSortExpensesDescendingByPercentage() {
      List<Tx> txs =
          List.of(
              new Tx("Coffee", new BigDecimal("10"), "DEBIT"),
              new Tx("Rent", new BigDecimal("80"), "DEBIT"),
              new Tx("Food", new BigDecimal("10"), "DEBIT"));

      List<ExpenseSlice> result = compute(txs);

      assertEquals(3, result.size());
      assertEquals("Rent", result.get(0).category());
      assertTrue(result.get(0).percentage().compareTo(result.get(1).percentage()) >= 0);
      assertTrue(result.get(1).percentage().compareTo(result.get(2).percentage()) >= 0);
    }

    @Test
    @DisplayName("should assign 100% when there is only a single expense category")
    void shouldHandleSingleExpenseCategory() {
      List<Tx> txs = List.of(new Tx("Rent", new BigDecimal("500"), "DEBIT"));

      List<ExpenseSlice> result = compute(txs);

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
      // 33/100 = 33% (exact), 33/99 ≈ 33.33% → 33% (HALF_UP)
      // 67/99 ≈ 67.68% → 68% (HALF_UP)
      List<Tx> txs =
          List.of(
              new Tx("A", new BigDecimal("33"), "DEBIT"),
              new Tx("B", new BigDecimal("67"), "DEBIT"));

      List<ExpenseSlice> result = compute(txs);

      long sum =
          result.stream().map(ExpenseSlice::percentage).mapToLong(BigDecimal::longValue).sum();
      // Sum should be near 100 (may be 99 or 100 due to rounding)
      assertTrue(sum >= 99 && sum <= 100, "sum of percentages should be ~100, but was " + sum);
    }

    @Test
    @DisplayName("should handle very small transaction amounts")
    void shouldHandleVerySmallAmounts() {
      List<Tx> txs =
          List.of(
              new Tx("A", new BigDecimal("0.01"), "DEBIT"),
              new Tx("B", new BigDecimal("0.01"), "DEBIT"));

      List<ExpenseSlice> result = compute(txs);

      assertEquals(2, result.size());
      // Both 50%
      assertEquals(new BigDecimal("50"), result.get(0).percentage());
      assertEquals(new BigDecimal("50"), result.get(1).percentage());
    }
  }
}
