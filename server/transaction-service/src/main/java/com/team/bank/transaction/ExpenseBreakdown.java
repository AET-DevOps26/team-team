package com.team.bank.transaction;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Aggregates DEBIT transactions into the expense breakdown: per category the share of total
 * spending, the absolute amount, the purchase count and the top merchants by spend. Transfers
 * (own-account and wallet money movement) are excluded — they are not spending and would dwarf
 * every real category. Transactions the sync couldn't categorize are folded into "Other", which
 * always sorts last so it never crowds out the categories that explain themselves.
 */
final class ExpenseBreakdown {

  private static final BigDecimal HUNDRED = new BigDecimal("100");

  private ExpenseBreakdown() {}

  static List<ExpenseSlice> compute(List<Transaction> transactions) {
    List<Transaction> debits =
        transactions.stream()
            .filter(tx -> "DEBIT".equalsIgnoreCase(tx.getDirection()))
            .filter(tx -> !"Transfers".equals(tx.getCategory()))
            .toList();

    BigDecimal total =
        debits.stream().map(Transaction::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);

    if (total.compareTo(BigDecimal.ZERO) == 0) {
      return List.of();
    }

    Map<String, List<Transaction>> grouped =
        debits.stream().collect(Collectors.groupingBy(ExpenseBreakdown::displayCategory));

    return grouped.entrySet().stream()
        .map(entry -> slice(entry.getKey(), entry.getValue(), total))
        .sorted(
            Comparator.comparing((ExpenseSlice s) -> "Other".equals(s.category()))
                .thenComparing(ExpenseSlice::percentage, Comparator.reverseOrder())
                .thenComparing(ExpenseSlice::amount, Comparator.reverseOrder()))
        .toList();
  }

  private static ExpenseSlice slice(String category, List<Transaction> txs, BigDecimal total) {
    BigDecimal amount =
        txs.stream().map(Transaction::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
    BigDecimal percent = amount.multiply(HUNDRED).divide(total, 0, RoundingMode.HALF_UP);
    List<String> topMerchants =
        txs.stream()
            .filter(tx -> tx.getCounterparty() != null && !tx.getCounterparty().isBlank())
            .collect(
                Collectors.groupingBy(
                    Transaction::getCounterparty,
                    Collectors.reducing(BigDecimal.ZERO, Transaction::getAmount, BigDecimal::add)))
            .entrySet()
            .stream()
            .sorted(Map.Entry.<String, BigDecimal>comparingByValue().reversed())
            .limit(2)
            .map(Map.Entry::getKey)
            .toList();
    return new ExpenseSlice(category, percent, amount, txs.size(), topMerchants);
  }

  /** Blank and legacy "Uncategorized" categories fold into "Other". */
  private static String displayCategory(Transaction tx) {
    String category = tx.getCategory();
    if (category == null || category.isBlank() || "Uncategorized".equals(category)) {
      return "Other";
    }
    return category;
  }
}
