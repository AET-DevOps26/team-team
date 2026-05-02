package com.team.bank.transaction;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

public record TransactionItem(
    UUID id,
    String category,
    BigDecimal amount,
    String direction,
    LocalDateTime createdAt
) {
}
