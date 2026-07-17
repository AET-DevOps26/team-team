package com.team.bank.orchestrator;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.util.UUID;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("DashboardController")
class DashboardControllerTest {

  @Autowired private MockMvc mockMvc;

  private static MockWebServer accountServer;
  private static MockWebServer transactionServer;
  private static MockWebServer genaiServer;
  private static MockWebServer bankingServer;

  private static final UUID ACCOUNT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

  @DynamicPropertySource
  static void configureProperties(DynamicPropertyRegistry registry) {
    registry.add(
        "services.account.url", () -> accountServer.url("/").toString().replaceAll("/$", ""));
    registry.add(
        "services.transaction.url",
        () -> transactionServer.url("/").toString().replaceAll("/$", ""));
    registry.add("services.genai.url", () -> genaiServer.url("/").toString().replaceAll("/$", ""));
    registry.add(
        "services.banking.url", () -> bankingServer.url("/").toString().replaceAll("/$", ""));
  }

  @BeforeAll
  static void startServers() throws IOException {
    accountServer = new MockWebServer();
    transactionServer = new MockWebServer();
    genaiServer = new MockWebServer();
    bankingServer = new MockWebServer();
    accountServer.start();
    transactionServer.start();
    genaiServer.start();
    bankingServer.start();
  }

  @AfterAll
  static void stopServers() throws IOException {
    accountServer.shutdown();
    transactionServer.shutdown();
    genaiServer.shutdown();
    bankingServer.shutdown();
  }

  private static MockResponse json(String body) {
    return new MockResponse().setBody(body).addHeader("Content-Type", "application/json");
  }

  private String accountJson() {
    return "{\"accountId\":\"" + ACCOUNT_ID + "\",\"customerName\":\"Test\",\"totalBalance\":1200}";
  }

  private String trendJson() {
    return "[{\"month\":\"Jan\",\"balance\":1000},{\"month\":\"Feb\",\"balance\":1200}]";
  }

  private String expensesJson() {
    return "[{\"category\":\"Rent\",\"percentage\":60},{\"category\":\"Food\",\"percentage\":40}]";
  }

  private String transactionsJson() {
    return "[{\"id\":\""
        + UUID.randomUUID()
        + "\",\"accountId\":\""
        + ACCOUNT_ID
        + "\",\"category\":\"Rent\",\"amount\":500.00,\"direction\":\"DEBIT\",\"createdAt\":\"2026-07-01T10:00:00\"}]";
  }

  private String bankingConnectionsJson() {
    return "[{\"bankName\":\"Nordea\",\"country\":\"FI\",\"status\":\"ACTIVE\",\"accountId\":\""
        + ACCOUNT_ID
        + "\",\"externalAccountUid\":\"ext-123\"}]";
  }

  @Test
  @DisplayName("GET /api should return UP")
  void shouldReturnApiIndex() throws Exception {
    mockMvc
        .perform(get("/api").accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("UP"));
  }

  @Test
  @DisplayName("should aggregate dashboard from all downstream services")
  void shouldAggregateDashboard() throws Exception {
    accountServer.enqueue(json(accountJson()));
    accountServer.enqueue(json(trendJson()));
    transactionServer.enqueue(json(expensesJson()));
    transactionServer.enqueue(json(transactionsJson()));
    genaiServer.enqueue(json("{\"summary\":\"OK\"}"));
    bankingServer.enqueue(
        json("{\"status\":\"ACTIVE\",\"bankName\":\"Nordea\",\"country\":\"FI\"}"));
    bankingServer.enqueue(json(bankingConnectionsJson()));

    mockMvc
        .perform(get("/api/dashboard/{accountId}", ACCOUNT_ID).accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.account.customerName").value("Test"))
        .andExpect(jsonPath("$.trend[0].month").value("Jan"))
        .andExpect(jsonPath("$.expenses[0].category").value("Rent"))
        .andExpect(jsonPath("$.aiSummary").value("OK"))
        .andExpect(jsonPath("$.connectionStatus.status").value("ACTIVE"));
  }

  @Test
  @DisplayName("should return 502 when account-service returns empty body")
  void shouldReturnBadGatewayWhenAccountFails() throws Exception {
    accountServer.enqueue(new MockResponse().setResponseCode(200).setBody(""));
    mockMvc
        .perform(get("/api/dashboard/{accountId}", ACCOUNT_ID).accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isBadGateway());
  }

  @Test
  @DisplayName("should fallback summary when genai is unavailable")
  void shouldFallbackSummary() throws Exception {
    accountServer.enqueue(json(accountJson()));
    accountServer.enqueue(json(trendJson()));
    transactionServer.enqueue(json(expensesJson()));
    transactionServer.enqueue(json(transactionsJson()));
    genaiServer.enqueue(new MockResponse().setResponseCode(500));
    bankingServer.enqueue(json("{\"status\":\"ACTIVE\",\"bankName\":\"N\",\"country\":\"FI\"}"));
    bankingServer.enqueue(json(bankingConnectionsJson()));

    mockMvc
        .perform(get("/api/dashboard/{accountId}", ACCOUNT_ID).accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.aiSummary").value("No summary available."));
  }

  @Test
  @DisplayName("should continue without connection status when banking unavailable")
  void shouldFallbackBanking() throws Exception {
    accountServer.enqueue(json(accountJson()));
    accountServer.enqueue(json(trendJson()));
    transactionServer.enqueue(json(expensesJson()));
    transactionServer.enqueue(json(transactionsJson()));
    genaiServer.enqueue(json("{\"summary\":\"OK\"}"));
    bankingServer.enqueue(new MockResponse().setResponseCode(500));

    mockMvc
        .perform(get("/api/dashboard/{accountId}", ACCOUNT_ID).accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.connectionStatus").isEmpty());
  }

  @Test
  @DisplayName("should proxy chat to genai and return reply")
  void shouldProxyChat() throws Exception {
    genaiServer.enqueue(json("{\"reply\":\"Hello!\",\"reasoning\":null}"));
    mockMvc
        .perform(
            post("/api/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"message\":\"Hi\",\"messages\":null,\"context\":null}")
                .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.reply").value("Hello!"));
  }

  @Test
  @DisplayName("should return 400 for empty chat request")
  void shouldReturn400ForEmptyChat() throws Exception {
    mockMvc
        .perform(
            post("/api/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}")
                .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isBadRequest());
  }

  @Test
  @DisplayName("should return fallback when genai chat fails")
  void shouldFallbackChat() throws Exception {
    genaiServer.enqueue(new MockResponse().setResponseCode(500));
    mockMvc
        .perform(
            post("/api/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"message\":\"Hi\",\"messages\":null,\"context\":null}")
                .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.reply").value("I could not process that request."));
  }

  @Test
  @DisplayName("should proxy banking bank list")
  void shouldProxyBankList() throws Exception {
    bankingServer.enqueue(json("[{\"name\":\"Nordea\",\"country\":\"FI\"}]"));
    mockMvc
        .perform(
            get("/api/banking/banks").param("country", "FI").accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].name").value("Nordea"));
  }
}
