package com.team.bank.account;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link AccountController#utilizationRate(BigDecimal, BigDecimal)}.
 *
 * <p>Uses reflection to access the private static helper since it's package-private logic that must
 * not divide by zero and must handle null inputs gracefully.
 */
class UtilizationRateTest {

  private static BigDecimal invokeUtilizationRate(BigDecimal balance, BigDecimal creditLimit)
      throws ReflectiveOperationException {
    Method method =
        AccountController.class.getDeclaredMethod(
            "utilizationRate", BigDecimal.class, BigDecimal.class);
    method.setAccessible(true);
    return (BigDecimal) method.invoke(null, balance, creditLimit);
  }

  @Test
  @DisplayName("should return zero when balance is null")
  void shouldReturnZeroWhenBalanceIsNull() throws ReflectiveOperationException {
    BigDecimal result = invokeUtilizationRate(null, new BigDecimal("1000"));
    assertEquals(BigDecimal.ZERO, result);
  }

  @Test
  @DisplayName("should return zero when creditLimit is null")
  void shouldReturnZeroWhenCreditLimitIsNull() throws ReflectiveOperationException {
    BigDecimal result = invokeUtilizationRate(new BigDecimal("500"), null);
    assertEquals(BigDecimal.ZERO, result);
  }

  @Test
  @DisplayName("should return zero when creditLimit is zero (no division by zero)")
  void shouldReturnZeroWhenCreditLimitIsZero() throws ReflectiveOperationException {
    BigDecimal result = invokeUtilizationRate(new BigDecimal("500"), BigDecimal.ZERO);
    assertEquals(BigDecimal.ZERO, result);
  }

  @Test
  @DisplayName("should return zero when creditLimit is negative")
  void shouldReturnZeroWhenCreditLimitIsNegative() throws ReflectiveOperationException {
    BigDecimal result = invokeUtilizationRate(new BigDecimal("500"), new BigDecimal("-1"));
    assertEquals(BigDecimal.ZERO, result);
  }

  @Test
  @DisplayName("should compute correct ratio for normal balance and credit limit")
  void shouldComputeCorrectRateForNormalValues() throws ReflectiveOperationException {
    // balance 300 / limit 1000 = 0.3000
    BigDecimal result = invokeUtilizationRate(new BigDecimal("300"), new BigDecimal("1000"));
    assertEquals(new BigDecimal("0.3000"), result);
  }

  @Test
  @DisplayName("should compute ratio with four-decimal HALF_UP precision")
  void shouldComputeRateWithFourDecimalPrecision() throws ReflectiveOperationException {
    // 1 / 3 = 0.3333… → HALF_UP → 0.3333
    BigDecimal result = invokeUtilizationRate(BigDecimal.ONE, new BigDecimal("3"));
    assertEquals(new BigDecimal("0.3333"), result);
  }

  @Test
  @DisplayName("should compute utilization when balance exceeds credit limit")
  void shouldComputeWhenBalanceExceedsCreditLimit() throws ReflectiveOperationException {
    // balance 2000 / limit 1000 = 2.0000 (over-utilized)
    BigDecimal result = invokeUtilizationRate(new BigDecimal("2000"), new BigDecimal("1000"));
    assertEquals(new BigDecimal("2.0000"), result);
  }
}
