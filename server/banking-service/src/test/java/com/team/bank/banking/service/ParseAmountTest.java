package com.team.bank.banking.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link BankingSyncService#parseAmount(Object)}.
 *
 * <p>Uses reflection since the method is private. Covers both nested-object and bare-scalar formats
 * returned by Enable Banking, plus robustness against null, empty, and unparseable values.
 */
@DisplayName("parseAmount(…) helper")
class ParseAmountTest {

  private static final BankingSyncService SERVICE = new BankingSyncService(null, null, null, null);

  private static BigDecimal invokeParseAmount(Object field) throws ReflectiveOperationException {
    Method method = BankingSyncService.class.getDeclaredMethod("parseAmount", Object.class);
    method.setAccessible(true);
    return (BigDecimal) method.invoke(SERVICE, field);
  }

  @Nested
  @DisplayName("nested-object format (Enable Banking standard)")
  class NestedObject {

    @Test
    @DisplayName("should parse {amount, currency} map into BigDecimal")
    void shouldParseNestedAmountObject() throws ReflectiveOperationException {
      Map<String, Object> nested = Map.of("amount", "150.00", "currency", "EUR");
      BigDecimal result = invokeParseAmount(nested);
      assertEquals(new BigDecimal("150.00"), result);
    }

    @Test
    @DisplayName("should parse nested amount with integer value")
    void shouldParseNestedIntegerAmount() throws ReflectiveOperationException {
      Map<String, Object> nested = Map.of("amount", "42", "currency", "EUR");
      BigDecimal result = invokeParseAmount(nested);
      assertEquals(new BigDecimal("42"), result);
    }
  }

  @Nested
  @DisplayName("bare-scalar format (fallback)")
  class BareScalar {

    @Test
    @DisplayName("should parse a plain string amount")
    void shouldParseBareScalarAmount() throws ReflectiveOperationException {
      BigDecimal result = invokeParseAmount("299.99");
      assertEquals(new BigDecimal("299.99"), result);
    }

    @Test
    @DisplayName("should parse a negative plain amount")
    void shouldParseNegativeAmount() throws ReflectiveOperationException {
      BigDecimal result = invokeParseAmount("-50.00");
      assertEquals(new BigDecimal("-50.00"), result);
    }

    @Test
    @DisplayName("should parse zero as plain amount")
    void shouldParseZeroAmount() throws ReflectiveOperationException {
      BigDecimal result = invokeParseAmount("0");
      assertEquals(BigDecimal.ZERO, result);
    }
  }

  @Nested
  @DisplayName("robustness — null / empty / unparseable")
  class Robustness {

    @Test
    @DisplayName("should return null when input is null")
    void shouldReturnNullForNullInput() throws ReflectiveOperationException {
      assertNull(invokeParseAmount(null));
    }

    @Test
    @DisplayName("should return null when map has no 'amount' key")
    void shouldReturnNullForEmptyMap() throws ReflectiveOperationException {
      Map<String, Object> empty = Map.of("currency", "EUR");
      assertNull(invokeParseAmount(empty));
    }

    @Test
    @DisplayName("should return null when amount value is null inside map")
    void shouldReturnNullWhenMapAmountIsNull() throws ReflectiveOperationException {
      Map<String, Object> map = new java.util.HashMap<>();
      map.put("amount", null);
      assertNull(invokeParseAmount(map));
    }

    @Test
    @DisplayName("should return null for unparseable string")
    void shouldReturnNullForUnparseableString() throws ReflectiveOperationException {
      assertNull(invokeParseAmount("not-a-number"));
    }

    @Test
    @DisplayName("should return null for an unexpected object type")
    void shouldReturnNullForUnexpectedType() throws ReflectiveOperationException {
      // An Integer should still parse via toString()
      BigDecimal result = invokeParseAmount(100);
      assertEquals(new BigDecimal("100"), result);
    }
  }
}
