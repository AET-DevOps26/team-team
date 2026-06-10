package com.team.bank.banking.model;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TransactionRepository extends JpaRepository<Transaction, UUID> {

    List<Transaction> findByAccountIdAndCategoryAndAmountAndDirection(
        UUID accountId, String category, BigDecimal amount, String direction);
}
