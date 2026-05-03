package com.team.bank.account;

import java.math.BigDecimal;
import java.util.UUID;

public record AccountSummary(
    UUID accountId,
    String customerName,
    BigDecimal totalBalance,
    BigDecimal totalCreditLimit,
    BigDecimal utilizationRate
) {
}
