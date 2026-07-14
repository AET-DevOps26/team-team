package com.team.bank.banking.dto;

import java.math.BigDecimal;

/**
 * One linked bank account in the multi-bank roster: its status plus the per-bank balance/currency
 * and display name synced from Enable Banking. Returned by {@code GET /api/banking/connections/*}.
 */
public record ConnectionInfo(
    String status,
    String bankName,
    String country,
    String accountName,
    BigDecimal balance,
    String currency) {}
