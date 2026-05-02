package com.team.bank.account;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AccountSummaryTest {

    @Test
    void shouldKeepUtilizationRatePrecision() {
        AccountSummary summary = new AccountSummary(
            UUID.randomUUID(),
            "Customer",
            new BigDecimal("100.00"),
            new BigDecimal("200.00"),
            new BigDecimal("0.500")
        );

        assertEquals(new BigDecimal("0.500"), summary.utilizationRate());
    }
}
