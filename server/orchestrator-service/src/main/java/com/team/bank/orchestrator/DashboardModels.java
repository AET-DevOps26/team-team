package com.team.bank.orchestrator;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

record AccountSummary(
    UUID accountId,
    String customerName,
    BigDecimal totalBalance,
    BigDecimal totalCreditLimit,
    BigDecimal utilizationRate) {}

record BalancePoint(String month, BigDecimal balance) {}

record ExpenseSlice(String category, BigDecimal percentage) {}

record SummaryRequest(
    AccountSummary account, List<BalancePoint> trend, List<ExpenseSlice> expenses) {}

record SummaryResponse(String summary) {}

record ConnectionStatus(String status, String bankName, String country) {}

record DashboardResponse(
    AccountSummary account,
    List<BalancePoint> trend,
    List<ExpenseSlice> expenses,
    String aiSummary,
    ConnectionStatus connectionStatus) {}

record ChatMessage(String role, String content) {}

record ChatContext(
    AccountSummary account,
    List<BalancePoint> trend,
    List<ExpenseSlice> expenses,
    ConnectionStatus connection) {}

record ChatRequest(String message, List<ChatMessage> messages, ChatContext context) {}

record ChatResponse(String reply, String reasoning) {}
