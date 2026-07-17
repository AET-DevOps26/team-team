package com.team.bank.transaction;

import java.util.List;
import java.util.UUID;
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
    return transactionRepository.findByAccountIdOrderByCreatedAtDesc(accountId).stream()
        .map(
            tx ->
                new TransactionItem(
                    tx.getId(),
                    tx.getCategory(),
                    tx.getAmount(),
                    tx.getDirection(),
                    tx.getBankName(),
                    tx.getCounterparty(),
                    tx.getCreatedAt()))
        .toList();
  }

  @GetMapping("/{accountId}/expenses")
  public List<ExpenseSlice> expenseBreakdown(@PathVariable UUID accountId) {
    return ExpenseBreakdown.compute(
        transactionRepository.findByAccountIdOrderByCreatedAtDesc(accountId));
  }
}
