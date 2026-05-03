package com.team.bank.transaction;

import java.math.BigDecimal;

public record ExpenseSlice(String category, BigDecimal percentage) {
}
