package com.team.bank.transaction;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class ExpenseSliceTest {

  @Test
  void shouldStoreAllFields() {
    ExpenseSlice slice =
        new ExpenseSlice(
            "Utilities", new BigDecimal("40"), new BigDecimal("120.50"), 3, List.of("SWM"));
    assertEquals(new BigDecimal("40"), slice.percentage());
    assertEquals(new BigDecimal("120.50"), slice.amount());
    assertEquals(3, slice.count());
    assertEquals(List.of("SWM"), slice.topMerchants());
  }
}
