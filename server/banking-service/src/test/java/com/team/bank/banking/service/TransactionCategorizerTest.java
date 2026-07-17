package com.team.bank.banking.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TransactionCategorizerTest {

  @Test
  @DisplayName("matches merchant keywords in the counterparty name")
  void matchesCounterparty() {
    assertEquals(
        "Groceries",
        TransactionCategorizer.categorize("DEBIT", "REWE Muenchen/Schw", null, null, null));
    assertEquals(
        "Transport", TransactionCategorizer.categorize("DEBIT", "VOI DE", null, null, null));
    assertEquals(
        "Subscriptions",
        TransactionCategorizer.categorize("DEBIT", "CLAUDE.AI SUBSCRIPTION", null, null, null));
  }

  @Test
  @DisplayName("matches keywords in remittance text when there is no counterparty")
  void matchesRemittance() {
    assertEquals(
        "Groceries",
        TransactionCategorizer.categorize("DEBIT", null, "Grocery shopping", null, null));
  }

  @Test
  @DisplayName("city names do not trigger unrelated categories")
  void cityNamesAreNotDining() {
    assertEquals(
        "Shopping",
        TransactionCategorizer.categorize("DEBIT", "GALERIA MÜNCHEN-SCHWA", null, null, null));
  }

  @Test
  @DisplayName("truncated Gaststätte names and wallet top-ups categorize sensibly")
  void truncatedAndWalletMerchants() {
    assertEquals(
        "Dining & Cafes",
        TransactionCategorizer.categorize("DEBIT", "Wilhelm & Lehmann Gast", null, null, null));
    assertEquals(
        "Transfers",
        TransactionCategorizer.categorize("DEBIT", "Revolut**7647*", null, null, null));
  }

  @Test
  @DisplayName("falls back to the merchant category code, then the bank transaction code")
  void fallsBackToCodes() {
    assertEquals(
        "Dining & Cafes",
        TransactionCategorizer.categorize("DEBIT", "Unknown Diner XY", null, "5812", null));
    assertEquals(
        "Transfers", TransactionCategorizer.categorize("DEBIT", null, "To savings", null, "ICDT"));
    assertEquals("Other", TransactionCategorizer.categorize("DEBIT", null, null, null, null));
  }

  @Test
  @DisplayName("credits are Income, or Refund when the text hints at one")
  void credits() {
    assertEquals("Income", TransactionCategorizer.categorize("CREDIT", null, "Salary", null, null));
    assertEquals("Refund", TransactionCategorizer.categorize("CREDIT", "PAYPAL", null, null, null));
  }
}
