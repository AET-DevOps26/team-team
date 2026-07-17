package com.team.bank.transaction;

import java.math.BigDecimal;
import java.util.List;

public record ExpenseSlice(
    String category,
    BigDecimal percentage,
    BigDecimal amount,
    int count,
    List<String> topMerchants) {}
