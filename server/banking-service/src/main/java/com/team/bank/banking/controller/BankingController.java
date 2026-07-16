package com.team.bank.banking.controller;

import com.team.bank.banking.client.EnableBankingClient;
import com.team.bank.banking.config.EnableBankingConfig;
import com.team.bank.banking.dto.ConnectBankRequest;
import com.team.bank.banking.dto.ConnectionInfo;
import com.team.bank.banking.dto.ConnectionStatus;
import com.team.bank.banking.model.Account;
import com.team.bank.banking.model.AccountRepository;
import com.team.bank.banking.model.BankingConnection;
import com.team.bank.banking.model.BankingConnectionRepository;
import com.team.bank.banking.service.BankingSyncService;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST entry point for linking a bank account through Enable Banking.
 *
 * <p>The linking flow is: {@code POST /connect} persists a PENDING connection and returns the
 * bank's hosted authorization URL; the user authorizes there and is redirected back, at which point
 * {@code POST /callback} exchanges the code for a session, marks the connection ACTIVE and runs an
 * initial sync. The random {@code state} token ties the asynchronous callback back to the
 * originating connection. {@code /status} and {@code /sync} act on an already-linked account.
 */
@RestController
@RequestMapping("/api/banking")
public class BankingController {

  private final EnableBankingClient ebClient;
  private final EnableBankingConfig ebConfig;
  private final BankingConnectionRepository connectionRepository;
  private final AccountRepository accountRepository;
  private final BankingSyncService syncService;

  public BankingController(
      EnableBankingClient ebClient,
      EnableBankingConfig ebConfig,
      BankingConnectionRepository connectionRepository,
      AccountRepository accountRepository,
      BankingSyncService syncService) {
    this.ebClient = ebClient;
    this.ebConfig = ebConfig;
    this.connectionRepository = connectionRepository;
    this.accountRepository = accountRepository;
    this.syncService = syncService;
  }

  @GetMapping("/banks")
  public ResponseEntity<List<Map<String, Object>>> listBanks(@RequestParam String country) {
    List<Map<String, Object>> banks = ebClient.listBanks(country);
    List<Map<String, Object>> result =
        banks.stream()
            .map(
                bank ->
                    Map.<String, Object>of(
                        "name",
                        bank.getOrDefault("name", ""),
                        "country",
                        bank.getOrDefault("country", country)))
            .toList();
    return ResponseEntity.ok(result);
  }

  @PostMapping("/connect")
  public ResponseEntity<Map<String, String>> connect(@RequestBody ConnectBankRequest request) {
    String state = UUID.randomUUID().toString();
    LocalDateTime now = LocalDateTime.now(ZoneId.systemDefault());

    // The profile account is the aggregate the dashboard loads; it must exist before we can link a
    // bank (banking_connections.account_id FKs to accounts.id). Materialize a zeroed anchor if this
    // is the first connect for the account; sync later overwrites its balance/name with real data.
    ensureAnchorAccount(request.accountId(), now);

    BankingConnection connection = new BankingConnection();
    connection.setId(UUID.randomUUID());
    connection.setAccountId(request.accountId());
    connection.setBankName(request.bankName());
    connection.setCountry(request.country());
    connection.setState(state);
    connection.setStatus("PENDING");
    connection.setCreatedAt(now);
    connection.setUpdatedAt(now);
    connectionRepository.save(connection);

    String authUrl =
        ebClient.initiateAuth(
            request.bankName(), request.country(), ebConfig.getRedirectUrl(), state);

    return ResponseEntity.ok(Map.of("authUrl", authUrl));
  }

  @SuppressWarnings("unchecked")
  @PostMapping("/callback")
  public ResponseEntity<ConnectionStatus> callback(@RequestBody Map<String, String> body) {
    String code = body.get("code");
    String state = body.get("state");

    // Both are required to complete the handshake; createSession(code) would NPE on a null code.
    if (code == null || code.isBlank() || state == null || state.isBlank()) {
      return ResponseEntity.badRequest().build();
    }

    Optional<BankingConnection> optConnection = connectionRepository.findByState(state);
    if (optConnection.isEmpty()) {
      return ResponseEntity.badRequest().build();
    }

    BankingConnection connection = optConnection.get();
    Map<String, Object> session = ebClient.createSession(code);
    if (session == null) {
      // Upstream returned no session body; leave the connection PENDING.
      return ResponseEntity.status(HttpStatus.BAD_GATEWAY).build();
    }

    String sessionId = (String) session.get("session_id");
    List<Map<String, Object>> accounts = (List<Map<String, Object>>) session.get("accounts");
    Map<String, Object> firstAccount =
        accounts != null && !accounts.isEmpty() ? accounts.get(0) : null;
    String externalUid = firstAccount != null ? (String) firstAccount.get("uid") : null;

    // Without an external account UID we can't sync, so don't mark the connection ACTIVE.
    if (externalUid == null || externalUid.isBlank()) {
      return ResponseEntity.status(HttpStatus.BAD_GATEWAY).build();
    }

    connection.setSessionId(sessionId);
    connection.setExternalAccountUid(externalUid);
    connection.setAccountName(resolveAccountName(firstAccount, connection.getBankName()));
    Object currency = firstAccount.get("currency");
    if (currency != null) {
      connection.setCurrency(currency.toString());
    }
    connection.setStatus("ACTIVE");
    connection.setUpdatedAt(LocalDateTime.now(ZoneId.systemDefault()));
    connectionRepository.save(connection);

    syncService.syncAccount(connection);

    return ResponseEntity.ok(
        new ConnectionStatus(
            connection.getStatus(), connection.getBankName(), connection.getCountry()));
  }

  @GetMapping("/status/{accountId}")
  public ResponseEntity<ConnectionStatus> status(@PathVariable UUID accountId) {
    List<BankingConnection> connections = connectionRepository.findByAccountId(accountId);
    if (connections.isEmpty()) {
      return ResponseEntity.ok(new ConnectionStatus("NONE", null, null));
    }
    // Prefer an ACTIVE connection over stale PENDING rows from earlier, abandoned
    // OAuth attempts; otherwise surface the most recently updated one.
    BankingConnection connection =
        connections.stream()
            .filter(c -> "ACTIVE".equals(c.getStatus()))
            .max(java.util.Comparator.comparing(BankingConnection::getUpdatedAt))
            .orElseGet(
                () ->
                    connections.stream()
                        .max(java.util.Comparator.comparing(BankingConnection::getUpdatedAt))
                        .orElse(connections.get(0)));
    return ResponseEntity.ok(
        new ConnectionStatus(
            connection.getStatus(), connection.getBankName(), connection.getCountry()));
  }

  @PostMapping("/sync/{accountId}")
  public ResponseEntity<ConnectionStatus> sync(@PathVariable UUID accountId) {
    List<BankingConnection> active =
        connectionRepository.findByAccountIdAndStatus(accountId, "ACTIVE");
    if (active.isEmpty()) {
      return ResponseEntity.badRequest().build();
    }
    // Re-sync every linked bank; each syncAccount recomputes the shared aggregate.
    active.forEach(syncService::syncAccount);
    BankingConnection primary =
        active.stream()
            .max(Comparator.comparing(BankingConnection::getUpdatedAt))
            .orElse(active.get(0));
    return ResponseEntity.ok(
        new ConnectionStatus(primary.getStatus(), primary.getBankName(), primary.getCountry()));
  }

  /** All linked banks for the account (the multi-bank roster the dashboard renders). */
  @GetMapping("/connections/{accountId}")
  public ResponseEntity<List<ConnectionInfo>> connections(@PathVariable UUID accountId) {
    List<ConnectionInfo> active =
        connectionRepository.findByAccountIdAndStatus(accountId, "ACTIVE").stream()
            .sorted(Comparator.comparing(BankingConnection::getUpdatedAt).reversed())
            .map(
                c ->
                    new ConnectionInfo(
                        c.getStatus(),
                        c.getBankName(),
                        c.getCountry(),
                        c.getAccountName(),
                        c.getBalance(),
                        c.getCurrency()))
            .collect(Collectors.toList());
    return ResponseEntity.ok(active);
  }

  @GetMapping("/health")
  public ResponseEntity<Map<String, String>> health() {
    return ResponseEntity.ok(Map.of("status", "UP", "service", "banking-service"));
  }

  /** Creates the zeroed profile/anchor account row if it does not already exist. */
  private void ensureAnchorAccount(UUID accountId, LocalDateTime now) {
    if (accountRepository.existsById(accountId)) {
      return;
    }
    Account account = new Account();
    account.setId(accountId);
    account.setCustomerName("My accounts");
    account.setAccountType("AGGREGATE");
    account.setBalance(BigDecimal.ZERO);
    account.setCreditLimit(BigDecimal.ZERO);
    account.setUpdatedAt(now);
    try {
      accountRepository.save(account);
    } catch (org.springframework.dao.DataIntegrityViolationException e) {
      // Another concurrent request created the anchor row first.
    }
  }

  /** Enable Banking account display name (name/product), falling back to the bank name. */
  private static String resolveAccountName(Map<String, Object> account, String bankName) {
    if (account != null) {
      Object name = account.get("name");
      if (name == null) {
        name = account.get("product");
      }
      if (name != null && !name.toString().isBlank()) {
        return name.toString();
      }
    }
    return bankName;
  }
}
