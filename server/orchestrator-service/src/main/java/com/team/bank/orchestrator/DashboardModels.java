package com.team.bank.orchestrator;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

record AccountSummary(
    UUID accountId,
    String customerName,
    BigDecimal totalBalance,
    BigDecimal totalCreditLimit,
    BigDecimal utilizationRate) {}

record BalancePoint(String month, BigDecimal balance) {}

record ExpenseSlice(
    String category,
    BigDecimal percentage,
    BigDecimal amount,
    Integer count,
    List<String> topMerchants) {}

record SummaryRequest(
    AccountSummary account,
    List<BalancePoint> trend,
    List<ExpenseSlice> expenses,
    List<BankConnection> connections,
    MonthlyFlow monthlyFlow) {}

record SummaryResponse(String summary) {}

record ConnectionStatus(String status, String bankName, String country) {}

/** One linked bank account in the aggregated roster. */
record BankConnection(
    String status,
    String bankName,
    String country,
    String accountName,
    BigDecimal balance,
    String currency) {}

/** A transaction as served by transaction-service (and forwarded to the dashboard feed). */
record TransactionItem(
    UUID id,
    String category,
    BigDecimal amount,
    String direction,
    String bankName,
    String counterparty,
    LocalDateTime createdAt) {}

/** This month's money in/out/net, aggregated across all linked banks. */
record MonthlyFlow(String month, BigDecimal income, BigDecimal spending, BigDecimal net) {}

/** Total spending (debits) attributed to one linked bank, for the multi-bank breakdown. */
record BankSpend(String bankName, BigDecimal spending) {}

record DashboardResponse(
    AccountSummary account,
    List<BalancePoint> trend,
    List<ExpenseSlice> expenses,
    String aiSummary,
    ConnectionStatus connectionStatus,
    List<BankConnection> connections,
    List<TransactionItem> transactions,
    MonthlyFlow monthlyFlow,
    List<BankSpend> spendByBank) {}

record ChatMessage(String role, String content) {}

record ChatContext(
    AccountSummary account,
    List<BalancePoint> trend,
    List<ExpenseSlice> expenses,
    List<BankConnection> connections,
    List<TransactionItem> transactions,
    MonthlyFlow monthlyFlow,
    List<BankSpend> spendByBank) {}

record ChatRequest(String message, List<ChatMessage> messages, ChatContext context) {}

record ChatResponse(String reply, String reasoning) {}
