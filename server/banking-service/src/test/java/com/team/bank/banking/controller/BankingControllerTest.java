package com.team.bank.banking.controller;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.team.bank.banking.client.EnableBankingClient;
import com.team.bank.banking.config.EnableBankingConfig;
import com.team.bank.banking.model.AccountRepository;
import com.team.bank.banking.model.BankingConnection;
import com.team.bank.banking.model.BankingConnectionRepository;
import com.team.bank.banking.service.BankingSyncService;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(BankingController.class)
@ActiveProfiles("test")
@DisplayName("BankingController")
class BankingControllerTest {

  @Autowired private MockMvc mockMvc;

  @MockitoBean private EnableBankingClient ebClient;
  @MockitoBean private EnableBankingConfig ebConfig;
  @MockitoBean private BankingConnectionRepository connectionRepository;
  @MockitoBean private AccountRepository accountRepository;
  @MockitoBean private BankingSyncService syncService;

  private static final UUID ACCOUNT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

  @BeforeEach
  void setUp() {
    when(ebConfig.getRedirectUrl()).thenReturn("http://localhost:5173/callback");
  }

  /* ---- mock data helpers ---- */
  private BankingConnection connection(String status, LocalDateTime updatedAt) {
    BankingConnection c = new BankingConnection();
    c.setId(UUID.randomUUID());
    c.setAccountId(ACCOUNT_ID);
    c.setBankName("Nordea");
    c.setCountry("FI");
    c.setState(UUID.randomUUID().toString());
    c.setStatus(status);
    c.setExternalAccountUid("ext-uid-123");
    c.setCreatedAt(LocalDateTime.now(ZoneId.systemDefault()).minusDays(1));
    c.setUpdatedAt(updatedAt);
    return c;
  }

  @Nested
  @DisplayName("GET /api/banking/banks")
  class ListBanks {

    @Test
    @DisplayName("should return bank list for a given country")
    void shouldListBanksForCountry() throws Exception {
      List<Map<String, Object>> banks =
          List.of(Map.of("name", "Nordea", "country", "FI"), Map.of("name", "OP", "country", "FI"));
      when(ebClient.listBanks("FI")).thenReturn(banks);

      mockMvc
          .perform(
              get("/api/banking/banks").param("country", "FI").accept(MediaType.APPLICATION_JSON))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$", hasSize(2)))
          .andExpect(jsonPath("$[0].name").value("Nordea"));
    }

    @Test
    @DisplayName("should return empty list when no banks found")
    void shouldReturnEmptyListWhenNoBanksFound() throws Exception {
      when(ebClient.listBanks("XX")).thenReturn(List.of());

      mockMvc
          .perform(
              get("/api/banking/banks").param("country", "XX").accept(MediaType.APPLICATION_JSON))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$", hasSize(0)));
    }
  }

  @Nested
  @DisplayName("POST /api/banking/connect")
  class Connect {

    @Test
    @DisplayName("should create PENDING connection and return auth URL")
    void shouldConnectBankAndReturnAuthUrl() throws Exception {
      when(ebClient.initiateAuth(eq("Nordea"), eq("FI"), anyString(), anyString()))
          .thenReturn("https://auth.nordea.com/oauth");

      String body =
          String.format(
              "{\"bankName\": \"Nordea\", \"country\": \"FI\", \"accountId\": \"%s\"}", ACCOUNT_ID);

      mockMvc
          .perform(
              post("/api/banking/connect")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(body)
                  .accept(MediaType.APPLICATION_JSON))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.authUrl").value("https://auth.nordea.com/oauth"));

      // Verify a PENDING connection was saved
      verify(connectionRepository).save(any(BankingConnection.class));
    }
  }

  @Nested
  @DisplayName("POST /api/banking/callback")
  class Callback {

    @Test
    @DisplayName("should return 400 when code is null")
    void shouldReturnBadRequestForNullCode() throws Exception {
      String body =
          """
        {"code": null, "state": "abc-123"}
        """;

      mockMvc
          .perform(
              post("/api/banking/callback")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(body)
                  .accept(MediaType.APPLICATION_JSON))
          .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("should return 400 when state is blank")
    void shouldReturnBadRequestForBlankState() throws Exception {
      String body =
          """
        {"code": "auth-code", "state": ""}
        """;

      mockMvc
          .perform(
              post("/api/banking/callback")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(body)
                  .accept(MediaType.APPLICATION_JSON))
          .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("should return 400 when state does not match any connection")
    void shouldReturnBadRequestForUnknownState() throws Exception {
      when(connectionRepository.findByState("unknown-state")).thenReturn(Optional.empty());

      String body =
          """
        {"code": "auth-code", "state": "unknown-state"}
        """;

      mockMvc
          .perform(
              post("/api/banking/callback")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(body)
                  .accept(MediaType.APPLICATION_JSON))
          .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("should return 502 when session creation returns null")
    void shouldReturnBadGatewayWhenSessionCreationReturnsNull() throws Exception {
      BankingConnection pendingConn =
          connection("PENDING", LocalDateTime.now(ZoneId.systemDefault()).minusHours(1));
      when(connectionRepository.findByState("valid-state")).thenReturn(Optional.of(pendingConn));
      when(ebClient.createSession("auth-code")).thenReturn(null);

      String body =
          """
        {"code": "auth-code", "state": "valid-state"}
        """;

      mockMvc
          .perform(
              post("/api/banking/callback")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(body)
                  .accept(MediaType.APPLICATION_JSON))
          .andExpect(status().isBadGateway());
    }

    @Test
    @DisplayName("should return 502 when session has no accounts (no external UID)")
    void shouldReturnBadGatewayWhenNoExternalAccountUid() throws Exception {
      BankingConnection pendingConn =
          connection("PENDING", LocalDateTime.now(ZoneId.systemDefault()).minusHours(1));
      when(connectionRepository.findByState("valid-state")).thenReturn(Optional.of(pendingConn));
      // Session with no accounts → externalUid will be null
      Map<String, Object> session = Map.of("session_id", "sess-1", "accounts", List.of());
      when(ebClient.createSession("auth-code")).thenReturn(session);

      String body =
          """
        {"code": "auth-code", "state": "valid-state"}
        """;

      mockMvc
          .perform(
              post("/api/banking/callback")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(body)
                  .accept(MediaType.APPLICATION_JSON))
          .andExpect(status().isBadGateway());
    }

    @Test
    @DisplayName("should complete callback, mark ACTIVE, trigger sync, and return status")
    void shouldCompleteCallbackAndMarkActive() throws Exception {
      BankingConnection pendingConn =
          connection("PENDING", LocalDateTime.now(ZoneId.systemDefault()).minusHours(1));
      when(connectionRepository.findByState("valid-state")).thenReturn(Optional.of(pendingConn));

      Map<String, Object> session =
          Map.of("session_id", "sess-1", "accounts", List.of(Map.of("uid", "ext-acct-uid-999")));
      when(ebClient.createSession("auth-code")).thenReturn(session);

      String body =
          """
        {"code": "auth-code", "state": "valid-state"}
        """;

      mockMvc
          .perform(
              post("/api/banking/callback")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(body)
                  .accept(MediaType.APPLICATION_JSON))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.status").value("ACTIVE"))
          .andExpect(jsonPath("$.bankName").value("Nordea"))
          .andExpect(jsonPath("$.country").value("FI"));

      verify(syncService).syncAccount(any(BankingConnection.class));
    }
  }

  @Nested
  @DisplayName("GET /api/banking/status/{accountId}")
  class Status {

    @Test
    @DisplayName("should return NONE status when no connections exist")
    void shouldReturnNoneStatusWhenNoConnections() throws Exception {
      when(connectionRepository.findByAccountId(ACCOUNT_ID)).thenReturn(List.of());

      mockMvc
          .perform(
              get("/api/banking/status/{accountId}", ACCOUNT_ID).accept(MediaType.APPLICATION_JSON))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.status").value("NONE"))
          .andExpect(jsonPath("$.bankName").isEmpty())
          .andExpect(jsonPath("$.country").isEmpty());
    }

    @Test
    @DisplayName("should prefer ACTIVE connection over PENDING ones")
    void shouldPreferActiveOverPendingConnections() throws Exception {
      BankingConnection pending =
          connection("PENDING", LocalDateTime.now(ZoneId.systemDefault()).minusHours(1));
      BankingConnection active = connection("ACTIVE", LocalDateTime.now(ZoneId.systemDefault()));
      when(connectionRepository.findByAccountId(ACCOUNT_ID)).thenReturn(List.of(pending, active));

      mockMvc
          .perform(
              get("/api/banking/status/{accountId}", ACCOUNT_ID).accept(MediaType.APPLICATION_JSON))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    @DisplayName("should fallback to most recent PENDING when no ACTIVE exists")
    void shouldFallbackToMostRecentWhenNoActive() throws Exception {
      BankingConnection older =
          connection("PENDING", LocalDateTime.now(ZoneId.systemDefault()).minusHours(2));
      older.setBankName("OldBank");
      older.setCountry("DE");
      BankingConnection newer =
          connection("PENDING", LocalDateTime.now(ZoneId.systemDefault()).minusHours(1));
      newer.setBankName("NewBank");
      newer.setCountry("SE");
      when(connectionRepository.findByAccountId(ACCOUNT_ID)).thenReturn(List.of(older, newer));

      mockMvc
          .perform(
              get("/api/banking/status/{accountId}", ACCOUNT_ID).accept(MediaType.APPLICATION_JSON))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.status").value("PENDING"))
          .andExpect(jsonPath("$.bankName").value("NewBank"))
          .andExpect(jsonPath("$.country").value("SE"));
    }
  }

  @Nested
  @DisplayName("POST /api/banking/sync/{accountId}")
  class Sync {

    @Test
    @DisplayName("should return 400 when no ACTIVE connection exists for account")
    void shouldReturn400OnSyncWithoutActiveConnection() throws Exception {
      when(connectionRepository.findByAccountIdAndStatus(ACCOUNT_ID, "ACTIVE"))
          .thenReturn(List.of());

      mockMvc
          .perform(
              post("/api/banking/sync/{accountId}", ACCOUNT_ID).accept(MediaType.APPLICATION_JSON))
          .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("should trigger sync and return connection status for ACTIVE connection")
    void shouldSyncActiveConnection() throws Exception {
      BankingConnection activeConn =
          connection("ACTIVE", LocalDateTime.now(ZoneId.systemDefault()));
      when(connectionRepository.findByAccountIdAndStatus(ACCOUNT_ID, "ACTIVE"))
          .thenReturn(List.of(activeConn));

      mockMvc
          .perform(
              post("/api/banking/sync/{accountId}", ACCOUNT_ID).accept(MediaType.APPLICATION_JSON))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.status").value("ACTIVE"))
          .andExpect(jsonPath("$.bankName").value("Nordea"));

      verify(syncService).syncAccount(activeConn);
    }
  }

  @Nested
  @DisplayName("GET /api/banking/health")
  class Health {

    @Test
    @DisplayName("should return 200 with service status UP")
    void shouldReturn200Health() throws Exception {
      mockMvc
          .perform(get("/api/banking/health").accept(MediaType.APPLICATION_JSON))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.status").value("UP"))
          .andExpect(jsonPath("$.service").value("banking-service"));
    }
  }
}
