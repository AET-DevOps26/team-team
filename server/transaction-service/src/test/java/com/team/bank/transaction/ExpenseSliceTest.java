package com.team.bank.transaction;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class ExpenseSliceTest {

    @Test
    void shouldStorePercentage() {
        ExpenseSlice slice = new ExpenseSlice("Utilities", new BigDecimal("40"));
        assertEquals(new BigDecimal("40"), slice.percentage());
    }
}
