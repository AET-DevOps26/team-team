package com.team.bank.transaction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

@DataJpaTest
@ActiveProfiles("test")
@DisplayName("TransactionRepository")
class TransactionRepositoryTest {

  @Autowired private TransactionRepository transactionRepository;

  private static final UUID ACCOUNT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

  private Transaction tx(String category, String amount, String direction, int daysAgo) {
    Transaction t = new Transaction();
    t.setId(UUID.randomUUID());
    t.setAccountId(ACCOUNT_ID);
    t.setCategory(category);
    t.setAmount(new BigDecimal(amount));
    t.setDirection(direction);
    t.setCreatedAt(LocalDateTime.now(ZoneId.systemDefault()).minusDays(daysAgo));
    return t;
  }

  @Test
  @DisplayName("should find transactions ordered by createdAt descending")
  void shouldFindByAccountIdOrderByCreatedAtDesc() {
    Transaction oldest = tx("Rent", "500.00", "DEBIT", 3);
    Transaction newest = tx("Coffee", "5.00", "DEBIT", 1);
    Transaction middle = tx("Food", "30.00", "DEBIT", 2);

    transactionRepository.saveAll(List.of(oldest, newest, middle));

    List<Transaction> result =
        transactionRepository.findByAccountIdOrderByCreatedAtDesc(ACCOUNT_ID);
    assertEquals(3, result.size());
    // Should be newest first
    assertEquals("Coffee", result.get(0).getCategory());
    assertEquals("Food", result.get(1).getCategory());
    assertEquals("Rent", result.get(2).getCategory());
  }

  @Test
  @DisplayName("should return empty list for unknown account ID")
  void shouldReturnEmptyForUnknownAccountId() {
    List<Transaction> result =
        transactionRepository.findByAccountIdOrderByCreatedAtDesc(UUID.randomUUID());
    assertTrue(result.isEmpty());
  }

  @Test
  @DisplayName("should persist all transaction fields correctly")
  void shouldPersistAllTransactionFields() {
    Transaction t = tx("Salary", "3000.00", "CREDIT", 0);
    transactionRepository.save(t);

    List<Transaction> result =
        transactionRepository.findByAccountIdOrderByCreatedAtDesc(ACCOUNT_ID);
    assertEquals(1, result.size());
    Transaction persisted = result.get(0);
    assertEquals(ACCOUNT_ID, persisted.getAccountId());
    assertEquals("Salary", persisted.getCategory());
    assertEquals(new BigDecimal("3000.00"), persisted.getAmount());
    assertEquals("CREDIT", persisted.getDirection());
    assertNotNull(persisted.getCreatedAt());
  }
}
