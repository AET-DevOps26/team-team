package com.team.bank.banking.model;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BankingConnectionRepository extends JpaRepository<BankingConnection, UUID> {

  Optional<BankingConnection> findByState(String state);

  List<BankingConnection> findByAccountIdAndStatus(UUID accountId, String status);

  List<BankingConnection> findByAccountId(UUID accountId);
}
