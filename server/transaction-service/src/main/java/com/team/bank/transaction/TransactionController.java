package com.team.bank.transaction;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/transactions")
public class TransactionController {

    private final TransactionRepository transactionRepository;

    public TransactionController(TransactionRepository transactionRepository) {
        this.transactionRepository = transactionRepository;
    }

    @GetMapping("/{accountId}")
    public List<TransactionItem> list(@PathVariable UUID accountId) {
        return transactionRepository.findByAccountIdOrderByCreatedAtDesc(accountId)
            .stream()
            .map(tx -> new TransactionItem(tx.getId(), tx.getCategory(), tx.getAmount(), tx.getDirection(), tx.getCreatedAt()))
            .toList();
    }

    @GetMapping("/{accountId}/expenses")
    public List<ExpenseSlice> expenseBreakdown(@PathVariable UUID accountId) {
        List<Transaction> debits = transactionRepository.findByAccountIdOrderByCreatedAtDesc(accountId)
            .stream()
            .filter(tx -> "DEBIT".equalsIgnoreCase(tx.getDirection()))
            .toList();

        BigDecimal total = debits.stream()
            .map(Transaction::getAmount)
            .reduce(BigDecimal.ZERO, BigDecimal::add);

        if (total.compareTo(BigDecimal.ZERO) == 0) {
            return List.of();
        }

        Map<String, BigDecimal> grouped = debits.stream()
            .collect(Collectors.groupingBy(Transaction::getCategory,
                Collectors.reducing(BigDecimal.ZERO, Transaction::getAmount, BigDecimal::add)));

        return grouped.entrySet().stream()
            .map(entry -> {
                BigDecimal percent = entry.getValue()
                    .multiply(new BigDecimal("100"))
                    .divide(total, 0, RoundingMode.HALF_UP);
                return new ExpenseSlice(entry.getKey(), percent);
            })
            .sorted((a, b) -> b.percentage().compareTo(a.percentage()))
            .toList();
    }
}
