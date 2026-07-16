package com.team.bank.account;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

@DataJpaTest
@ActiveProfiles("test")
@DisplayName("AccountRepository")
class AccountRepositoryTest {

  @Autowired private AccountRepository accountRepository;

  private static final UUID ACCOUNT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

  private Account buildAccount(String name, String balance, String creditLimit) {
    Account a = new Account();
    a.setId(ACCOUNT_ID);
    a.setCustomerName(name);
    a.setAccountType("CHECKING");
    a.setBalance(new BigDecimal(balance));
    a.setCreditLimit(new BigDecimal(creditLimit));
    a.setUpdatedAt(LocalDateTime.now(ZoneId.systemDefault()));
    return a;
  }

  @Test
  @DisplayName("should find account by ID after persisting")
  void shouldFindById() {
    Account account = buildAccount("Alice", "500.00", "1000.00");
    accountRepository.save(account);

    Optional<Account> found = accountRepository.findById(ACCOUNT_ID);
    assertTrue(found.isPresent());
    assertEquals("Alice", found.get().getCustomerName());
  }

  @Test
  @DisplayName("should return empty optional for unknown account ID")
  void shouldReturnEmptyForUnknownId() {
    UUID unknownId = UUID.randomUUID();
    Optional<Account> found = accountRepository.findById(unknownId);
    assertTrue(found.isEmpty());
  }

  @Test
  @DisplayName("should persist all account fields correctly")
  void shouldPersistAllFieldsCorrectly() {
    Account account = buildAccount("Bob", "2500.00", "5000.00");
    accountRepository.save(account);

    Optional<Account> found = accountRepository.findById(ACCOUNT_ID);
    assertTrue(found.isPresent());
    Account persisted = found.get();
    assertEquals(ACCOUNT_ID, persisted.getId());
    assertEquals("Bob", persisted.getCustomerName());
    assertEquals("CHECKING", persisted.getAccountType());
    assertEquals(new BigDecimal("2500.00"), persisted.getBalance());
    assertEquals(new BigDecimal("5000.00"), persisted.getCreditLimit());
    assertNotNull(persisted.getUpdatedAt());
  }
}
