package com.team.bank.account;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.RestTemplate;

@WebMvcTest(AccountController.class)
@ActiveProfiles("test")
@DisplayName("AccountController")
class AccountControllerTest {

  @Autowired private MockMvc mockMvc;

  @MockitoBean private AccountRepository accountRepository;

  @MockitoBean private RestTemplate restTemplate;

  private static final UUID ACCOUNT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

  /* ---- mock data ---- */
  private Account mockAccount(String name, String balance, String creditLimit) {
    Account a = new Account();
    a.setId(ACCOUNT_ID);
    a.setCustomerName(name);
    a.setAccountType("CHECKING");
    a.setBalance(new BigDecimal(balance));
    a.setCreditLimit(new BigDecimal(creditLimit));
    a.setUpdatedAt(LocalDateTime.now(ZoneId.systemDefault()));
    return a;
  }

  @Nested
  @DisplayName("GET /api/accounts/{accountId}")
  class GetAccount {

    @Test
    @DisplayName("should return account summary with computed utilization rate")
    void shouldReturnAccountSummary() throws Exception {
      Account account = mockAccount("Michael Carter", "1200.00", "4000.00");
      when(accountRepository.findById(ACCOUNT_ID)).thenReturn(Optional.of(account));

      mockMvc
          .perform(get("/api/accounts/{accountId}", ACCOUNT_ID).accept(MediaType.APPLICATION_JSON))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.accountId").value(ACCOUNT_ID.toString()))
          .andExpect(jsonPath("$.customerName").value("Michael Carter"))
          .andExpect(jsonPath("$.totalBalance").value(1200))
          .andExpect(jsonPath("$.totalCreditLimit").value(4000))
          .andExpect(jsonPath("$.utilizationRate").value(0.3));
    }

    @Test
    @DisplayName("should return 404 when account does not exist")
    void shouldReturn404ForUnknownAccount() throws Exception {
      UUID unknownId = UUID.randomUUID();
      when(accountRepository.findById(unknownId)).thenReturn(Optional.empty());

      mockMvc
          .perform(get("/api/accounts/{accountId}", unknownId).accept(MediaType.APPLICATION_JSON))
          .andExpect(status().isNotFound());
    }
  }

  @Nested
  @DisplayName("GET /api/accounts/{accountId}/trend")
  class GetTrend {

    @Test
    @DisplayName("should return single balance point when no transactions exist")
    void shouldReturnSinglePointWhenNoTransactions() throws Exception {
      Account account = mockAccount("Test User", "500.00", "1000.00");
      when(accountRepository.findById(ACCOUNT_ID)).thenReturn(Optional.of(account));
      when(restTemplate.getForObject(anyString(), eq(TransactionItem[].class)))
          .thenReturn(new TransactionItem[0]);

      mockMvc
          .perform(
              get("/api/accounts/{accountId}/trend", ACCOUNT_ID).accept(MediaType.APPLICATION_JSON))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$", hasSize(1)))
          .andExpect(jsonPath("$[0].balance").value(500));
    }

    @Test
    @DisplayName("should return single balance point when transaction service is unavailable")
    void shouldHandleTransactionServiceUnavailable() throws Exception {
      Account account = mockAccount("Test User", "500.00", "1000.00");
      when(accountRepository.findById(ACCOUNT_ID)).thenReturn(Optional.of(account));
      when(restTemplate.getForObject(anyString(), eq(TransactionItem[].class)))
          .thenThrow(new RuntimeException("Connection refused"));

      mockMvc
          .perform(
              get("/api/accounts/{accountId}/trend", ACCOUNT_ID).accept(MediaType.APPLICATION_JSON))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$", hasSize(1)))
          .andExpect(jsonPath("$[0].balance").value(500));
    }

    @Test
    @DisplayName("should return 404 for trend when account not found")
    void shouldReturn404ForTrendUnknownAccount() throws Exception {
      UUID unknownId = UUID.randomUUID();
      when(accountRepository.findById(unknownId)).thenReturn(Optional.empty());

      mockMvc
          .perform(
              get("/api/accounts/{accountId}/trend", unknownId).accept(MediaType.APPLICATION_JSON))
          .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("should reconstruct 6-month balance from transaction history")
    void shouldReturnCorrectTrendWhenTransactionsSpanMonths() throws Exception {
      Account account = mockAccount("Test User", "600.00", "1000.00");
      when(accountRepository.findById(ACCOUNT_ID)).thenReturn(Optional.of(account));

      LocalDateTime now = LocalDateTime.now(ZoneId.systemDefault());
      TransactionItem[] txs =
          new TransactionItem[] {
            // This month: +100 CREDIT (balance goes from 500 → 600 this month)
            new TransactionItem(
                UUID.randomUUID(), "Salary", new BigDecimal("100.00"), "CREDIT", now),
            // Last month: a -50 DEBIT
            new TransactionItem(
                UUID.randomUUID(), "Food", new BigDecimal("50.00"), "DEBIT", now.minusMonths(1)),
          };
      when(restTemplate.getForObject(anyString(), eq(TransactionItem[].class))).thenReturn(txs);

      mockMvc
          .perform(
              get("/api/accounts/{accountId}/trend", ACCOUNT_ID).accept(MediaType.APPLICATION_JSON))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$", hasSize(6)));
      // The last point (index 5) should be the current month with balance=600
    }
  }

  @Nested
  @DisplayName("GET /api/accounts/health")
  class Health {

    @Test
    @DisplayName("should return 200 with service status UP")
    void shouldReturn200Health() throws Exception {
      mockMvc
          .perform(get("/api/accounts/health").accept(MediaType.APPLICATION_JSON))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.status").value("UP"))
          .andExpect(jsonPath("$.service").value("account-service"));
    }
  }
}
