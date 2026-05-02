package com.team.bank.orchestrator;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

record AccountSummary(
    UUID accountId,
    String customerName,
    BigDecimal totalBalance,
    BigDecimal totalCreditLimit,
    BigDecimal utilizationRate
) {}

record BalancePoint(String month, BigDecimal balance) {}

record ExpenseSlice(String category, BigDecimal percentage) {}

record SummaryRequest(AccountSummary account, List<BalancePoint> trend, List<ExpenseSlice> expenses) {}

record SummaryResponse(String summary) {}

record DashboardResponse(
    AccountSummary account,
    List<BalancePoint> trend,
    List<ExpenseSlice> expenses,
    String aiSummary
) {}

record ChatRequest(String message) {}

record ChatResponse(String reply) {}
