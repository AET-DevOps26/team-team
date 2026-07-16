package com.team.bank.banking.model;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.annotation.Transactional;

public interface TransactionRepository extends JpaRepository<Transaction, UUID> {

  /**
   * Removes all transactions previously synced for one linked bank. Sync is replace-by-connection:
   * we delete this connection's rows then re-insert the full window Enable Banking returns, which
   * makes repeated syncs idempotent without needing a stable external transaction id.
   */
  @Modifying
  @Transactional
  void deleteByConnectionId(UUID connectionId);

  List<Transaction> findByAccountIdAndCategoryAndAmountAndDirection(
      UUID accountId, String category, BigDecimal amount, String direction);
}
