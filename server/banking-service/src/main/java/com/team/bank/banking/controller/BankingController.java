package com.team.bank.banking.controller;

import com.team.bank.banking.client.EnableBankingClient;
import com.team.bank.banking.config.EnableBankingConfig;
import com.team.bank.banking.dto.ConnectBankRequest;
import com.team.bank.banking.dto.ConnectionStatus;
import com.team.bank.banking.model.BankingConnection;
import com.team.bank.banking.model.BankingConnectionRepository;
import com.team.bank.banking.service.AccountOwnershipService;
import com.team.bank.banking.service.BankingSyncService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
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
 * bank's hosted authorization URL; the user authorizes there and is redirected back, at which
 * point {@code POST /callback} exchanges the code for a session, marks the connection ACTIVE
 * and runs an initial sync. The random {@code state} token ties the asynchronous callback back
 * to the originating connection. {@code /status} and {@code /sync} act on an already-linked
 * account.
 */
@RestController
@RequestMapping("/api/banking")
public class BankingController {

    private final EnableBankingClient ebClient;
    private final EnableBankingConfig ebConfig;
    private final BankingConnectionRepository connectionRepository;
    private final BankingSyncService syncService;
    private final AccountOwnershipService accountOwnershipService;

    public BankingController(EnableBankingClient ebClient,
                             EnableBankingConfig ebConfig,
                             BankingConnectionRepository connectionRepository,
                             BankingSyncService syncService,
                             AccountOwnershipService accountOwnershipService) {
        this.ebClient = ebClient;
        this.ebConfig = ebConfig;
        this.connectionRepository = connectionRepository;
        this.syncService = syncService;
        this.accountOwnershipService = accountOwnershipService;
    }

    @GetMapping("/banks")
    public ResponseEntity<List<Map<String, Object>>> listBanks(@RequestParam String country) {
        List<Map<String, Object>> banks = ebClient.listBanks(country);
        List<Map<String, Object>> result = banks.stream()
            .map(bank -> Map.<String, Object>of(
                "name", bank.getOrDefault("name", ""),
                "country", bank.getOrDefault("country", country)
            ))
            .toList();
        return ResponseEntity.ok(result);
    }

    @PostMapping("/connect")
    public ResponseEntity<Map<String, String>> connect(@RequestBody ConnectBankRequest request, @AuthenticationPrincipal UserDetails user) {
        // Security fix: Verify account ownership to prevent IDOR
        if (!accountOwnershipService.verifyOwnership(request.accountId(), user)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        
        String state = UUID.randomUUID().toString();
        LocalDateTime now = LocalDateTime.now();

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

        String authUrl = ebClient.initiateAuth(
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
        String externalUid = accounts != null && !accounts.isEmpty()
            ? (String) accounts.get(0).get("uid")
            : null;

        // Without an external account UID we can't sync, so don't mark the connection ACTIVE.
        if (externalUid == null || externalUid.isBlank()) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).build();
        }

        connection.setSessionId(sessionId);
        connection.setExternalAccountUid(externalUid);
        connection.setStatus("ACTIVE");
        connection.setUpdatedAt(LocalDateTime.now());
        connectionRepository.save(connection);

        syncService.syncAccount(connection);

        return ResponseEntity.ok(new ConnectionStatus(
            connection.getStatus(), connection.getBankName(), connection.getCountry()));
    }

    @GetMapping("/status/{accountId}")
    public ResponseEntity<ConnectionStatus> status(@PathVariable UUID accountId, @AuthenticationPrincipal UserDetails user) {
        // Security fix: Verify account ownership to prevent IDOR
        if (!accountOwnershipService.verifyOwnership(accountId, user)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        
        List<BankingConnection> connections = connectionRepository.findByAccountId(accountId);
        if (connections.isEmpty()) {
            return ResponseEntity.ok(new ConnectionStatus("NONE", null, null));
        }
        BankingConnection connection = connections.get(0);
        return ResponseEntity.ok(new ConnectionStatus(
            connection.getStatus(), connection.getBankName(), connection.getCountry()));
    }

    @PostMapping("/sync/{accountId}")
    public ResponseEntity<ConnectionStatus> sync(@PathVariable UUID accountId, @AuthenticationPrincipal UserDetails user) {
        // Security fix: Verify account ownership to prevent IDOR
        if (!accountOwnershipService.verifyOwnership(accountId, user)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        
        Optional<BankingConnection> optConnection =
            connectionRepository.findByAccountIdAndStatus(accountId, "ACTIVE");
        if (optConnection.isEmpty()) {
            return ResponseEntity.badRequest().build();
        }
        BankingConnection connection = optConnection.get();
        syncService.syncAccount(connection);
        return ResponseEntity.ok(new ConnectionStatus(
            connection.getStatus(), connection.getBankName(), connection.getCountry()));
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of("status", "UP", "service", "banking-service"));
    }
}
