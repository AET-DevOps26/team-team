package com.team.bank.banking.dto;

import java.util.UUID;

public record ConnectBankRequest(String bankName, String country, UUID accountId) {
}
