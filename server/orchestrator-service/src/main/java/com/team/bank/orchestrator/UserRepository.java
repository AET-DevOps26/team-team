package com.team.bank.orchestrator;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persists application users populated by the GitHub OAuth callback. Uses JdbcClient (no JPA/no
 * entities) to keep the orchestrator's DB footprint tiny; the table lives in the shared bankdb (see
 * infra/docker/init.sql and infra/docker/migrate-users.sql).
 */
@Repository
public class UserRepository {

  private final JdbcClient jdbcClient;

  public UserRepository(JdbcClient jdbcClient) {
    this.jdbcClient = jdbcClient;
  }

  /**
   * Creates the user on first sign-in, refreshes profile fields on later sign-ins, and provisions a
   * per-user aggregate `accounts` row (with a small starter transaction set) the first time. Later
   * calls reuse the existing account_id. Returns the persisted row so the caller sees exactly what
   * the DB holds.
   */
  @Transactional
  public AppUser upsert(
      long githubId,
      String login,
      String firstName,
      String lastName,
      String email,
      String avatarUrl) {
    jdbcClient
        .sql(
            "INSERT INTO users (github_id, login, first_name, last_name, email, avatar_url) "
                + "VALUES (:githubId, :login, :firstName, :lastName, :email, :avatarUrl) "
                + "ON CONFLICT (github_id) DO UPDATE SET "
                + "  login = EXCLUDED.login, "
                + "  first_name = EXCLUDED.first_name, "
                + "  last_name = EXCLUDED.last_name, "
                + "  email = EXCLUDED.email, "
                + "  avatar_url = EXCLUDED.avatar_url, "
                + "  updated_at = :updatedAt")
        .param("githubId", githubId)
        .param("login", login)
        .param("firstName", firstName)
        .param("lastName", lastName)
        .param("email", email)
        .param("avatarUrl", avatarUrl)
        .param("updatedAt", Timestamp.from(Instant.now()))
        .update();

    provisionAccountIfMissing(githubId, displayName(firstName, lastName, login));

    return findByGithubId(githubId)
        .orElseThrow(
            () ->
                new IllegalStateException(
                    "user disappeared right after upsert: github_id=" + githubId));
  }

  public Optional<AppUser> findByGithubId(long githubId) {
    return jdbcClient
        .sql(
            "SELECT github_id, login, first_name, last_name, email, avatar_url, account_id "
                + "FROM users WHERE github_id = :githubId")
        .param("githubId", githubId)
        .query(
            (rs, rowNum) ->
                new AppUser(
                    rs.getLong("github_id"),
                    rs.getString("login"),
                    rs.getString("first_name"),
                    rs.getString("last_name"),
                    rs.getString("email"),
                    rs.getString("avatar_url"),
                    rs.getObject("account_id", UUID.class)))
        .optional();
  }

  /**
   * Ensures the user has their own aggregate account. Idempotent — if `account_id` is already set
   * on the users row, does nothing. Otherwise creates the accounts row, links it, and seeds a small
   * illustrative transaction history so a first sign-in doesn't land on an empty dashboard.
   */
  private void provisionAccountIfMissing(long githubId, String customerName) {
    Optional<UUID> existing =
        jdbcClient
            .sql("SELECT account_id FROM users WHERE github_id = :githubId")
            .param("githubId", githubId)
            .query((rs, rn) -> rs.getObject("account_id", UUID.class))
            .optional();
    // JdbcClient.optional() maps a null column value to Optional.empty(), so a present
    // Optional already implies a non-null account_id — no need to unwrap and null-check.
    if (existing.isPresent()) {
      return;
    }

    UUID accountId = UUID.randomUUID();
    jdbcClient
        .sql(
            "INSERT INTO accounts (id, customer_name, account_type, balance, credit_limit) "
                + "VALUES (:id, :name, 'AGGREGATE', 0, 0)")
        .param("id", accountId)
        .param("name", customerName)
        .update();
    jdbcClient
        .sql("UPDATE users SET account_id = :accountId WHERE github_id = :githubId")
        .param("accountId", accountId)
        .param("githubId", githubId)
        .update();

    seedStarterData(accountId);
  }

  /**
   * Small illustrative dataset for a freshly-provisioned user account: one linked bank + roughly
   * three months of salary / rent / groceries / subscriptions. Kept minimal so the dashboard has
   * something to render without pretending to be real financial data.
   */
  private void seedStarterData(UUID accountId) {
    UUID connectionId = UUID.randomUUID();
    BigDecimal openingBalance = new BigDecimal("3250.00");

    jdbcClient
        .sql(
            "INSERT INTO banking_connections "
                + "  (id, account_id, session_id, bank_name, country, state, "
                + "   external_account_uid, account_name, balance, currency, status) "
                + "VALUES (:id, :accountId, :session, 'N26', 'DE', :state, "
                + "        :extUid, 'Main account', :balance, 'EUR', 'ACTIVE')")
        .param("id", connectionId)
        .param("accountId", accountId)
        .param("session", "seed-" + connectionId)
        .param("state", "seed-user-" + connectionId)
        .param("extUid", "ext-" + connectionId)
        .param("balance", openingBalance)
        .update();

    jdbcClient
        .sql("UPDATE accounts SET balance = :balance WHERE id = :id")
        .param("balance", openingBalance)
        .param("id", accountId)
        .update();

    LocalDate today = LocalDate.now(ZoneId.of("UTC"));
    for (int monthsAgo = 0; monthsAgo < 3; monthsAgo++) {
      LocalDate month = today.minusMonths(monthsAgo).withDayOfMonth(1);
      insertTx(
          accountId,
          connectionId,
          month.withDayOfMonth(28),
          "Salary",
          "TUM Payroll",
          "3200.00",
          "CREDIT");
      insertTx(accountId, connectionId, month, "Rent", "Vermieter GmbH", "1180.00", "DEBIT");
      insertTx(accountId, connectionId, month.plusDays(3), "Groceries", "REWE", "62.40", "DEBIT");
      insertTx(
          accountId, connectionId, month.plusDays(10), "Subscription", "Spotify", "9.99", "DEBIT");
      insertTx(
          accountId, connectionId, month.plusDays(11), "Subscription", "Netflix", "12.99", "DEBIT");
    }
  }

  private void insertTx(
      UUID accountId,
      UUID connectionId,
      LocalDate date,
      String category,
      String counterparty,
      String amount,
      String direction) {
    jdbcClient
        .sql(
            "INSERT INTO transactions "
                + "  (id, account_id, connection_id, bank_name, category, counterparty, "
                + "   amount, direction, created_at) "
                + "VALUES (:id, :accountId, :connectionId, 'N26', :category, :counterparty, "
                + "        :amount, :direction, :createdAt)")
        .param("id", UUID.randomUUID())
        .param("accountId", accountId)
        .param("connectionId", connectionId)
        .param("category", category)
        .param("counterparty", counterparty)
        .param("amount", new BigDecimal(amount))
        .param("direction", direction)
        .param("createdAt", Timestamp.valueOf(date.atTime(9, 0)))
        .update();
  }

  private static String displayName(String firstName, String lastName, String login) {
    StringBuilder sb = new StringBuilder();
    if (firstName != null && !firstName.isBlank()) {
      sb.append(firstName.trim());
    }
    if (lastName != null && !lastName.isBlank()) {
      if (sb.length() > 0) {
        sb.append(' ');
      }
      sb.append(lastName.trim());
    }
    if (sb.length() == 0) {
      sb.append(login);
    }
    return sb.toString();
  }
}
