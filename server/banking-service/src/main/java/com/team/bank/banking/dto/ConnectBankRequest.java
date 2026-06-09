package com.team.bank.banking.dto;

import java.util.UUID;

/** Request body for {@code POST /api/banking/connect}: which local account to link, and at which bank. */
public record ConnectBankRequest(String bankName, String country, UUID accountId) {
}
