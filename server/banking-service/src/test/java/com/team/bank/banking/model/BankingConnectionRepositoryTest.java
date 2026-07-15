package com.team.bank.banking.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

@DataJpaTest
@ActiveProfiles("test")
@DisplayName("BankingConnectionRepository")
class BankingConnectionRepositoryTest {

  @Autowired private BankingConnectionRepository connectionRepository;

  private static final UUID ACCOUNT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

  private BankingConnection buildConnection(String state, String status, LocalDateTime updatedAt) {
    BankingConnection c = new BankingConnection();
    c.setId(UUID.randomUUID());
    c.setAccountId(ACCOUNT_ID);
    c.setBankName("Nordea");
    c.setCountry("FI");
    c.setState(state);
    c.setStatus(status);
    c.setSessionId("session-" + state);
    c.setExternalAccountUid("ext-uid-" + state);
    c.setCreatedAt(LocalDateTime.now(ZoneId.systemDefault()).minusDays(1));
    c.setUpdatedAt(updatedAt);
    c.setValidUntil(LocalDateTime.now(ZoneId.systemDefault()).plusDays(90));
    return c;
  }

  @Test
  @DisplayName("should find connection by state token")
  void shouldFindByState() {
    BankingConnection conn =
        buildConnection("state-abc-123", "PENDING", LocalDateTime.now(ZoneId.systemDefault()));
    connectionRepository.save(conn);

    Optional<BankingConnection> found = connectionRepository.findByState("state-abc-123");
    assertTrue(found.isPresent());
    assertEquals("Nordea", found.get().getBankName());
    assertEquals("PENDING", found.get().getStatus());
  }

  @Test
  @DisplayName("should find connection by account ID and status")
  void shouldFindByAccountIdAndStatus() {
    BankingConnection conn =
        buildConnection("state-xyz", "ACTIVE", LocalDateTime.now(ZoneId.systemDefault()));
    connectionRepository.save(conn);

    List<BankingConnection> found =
        connectionRepository.findByAccountIdAndStatus(ACCOUNT_ID, "ACTIVE");
    assertFalse(found.isEmpty());
    assertEquals("ACTIVE", found.get(0).getStatus());
  }

  @Test
  @DisplayName("should not find connection when status differs")
  void shouldNotFindWhenStatusDiffers() {
    BankingConnection conn =
        buildConnection("state-pending", "PENDING", LocalDateTime.now(ZoneId.systemDefault()));
    connectionRepository.save(conn);

    List<BankingConnection> found =
        connectionRepository.findByAccountIdAndStatus(ACCOUNT_ID, "ACTIVE");
    assertTrue(found.isEmpty());
  }

  @Test
  @DisplayName("should find all connections for an account")
  void shouldFindByAccountId() {
    BankingConnection c1 =
        buildConnection(
            "state-1", "PENDING", LocalDateTime.now(ZoneId.systemDefault()).minusHours(2));
    BankingConnection c2 =
        buildConnection("state-2", "ACTIVE", LocalDateTime.now(ZoneId.systemDefault()));
    connectionRepository.saveAll(List.of(c1, c2));

    List<BankingConnection> result = connectionRepository.findByAccountId(ACCOUNT_ID);
    assertEquals(2, result.size());
  }

  @Test
  @DisplayName("should persist all fields correctly")
  void shouldPersistAllFields() {
    BankingConnection conn =
        buildConnection("state-full", "ACTIVE", LocalDateTime.now(ZoneId.systemDefault()));
    connectionRepository.save(conn);

    Optional<BankingConnection> found = connectionRepository.findByState("state-full");
    assertTrue(found.isPresent());
    BankingConnection persisted = found.get();
    assertEquals(ACCOUNT_ID, persisted.getAccountId());
    assertEquals("Nordea", persisted.getBankName());
    assertEquals("FI", persisted.getCountry());
    assertEquals("ACTIVE", persisted.getStatus());
    assertEquals("session-state-full", persisted.getSessionId());
    assertEquals("ext-uid-state-full", persisted.getExternalAccountUid());
    assertNotNull(persisted.getCreatedAt());
    assertNotNull(persisted.getUpdatedAt());
    assertNotNull(persisted.getValidUntil());
  }
}
