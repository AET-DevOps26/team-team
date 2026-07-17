package com.team.bank.orchestrator;

import java.sql.Timestamp;
import java.time.Instant;
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
   * per-user aggregate `accounts` row the first time. Later calls reuse the existing account_id.
   * Returns the persisted row so the caller sees exactly what the DB holds.
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
   * on the users row, does nothing. Otherwise creates an empty accounts row and links it. Real bank
   * connections and transactions are populated exclusively by the Enable Banking OAuth flow; the
   * live dashboard stays empty until a bank is actually linked. Rich mock data lives on the shared
   * demo aggregate account (see scripts/seed-demo-data.sql) and is exposed via the Demo toggle.
   */
  private void provisionAccountIfMissing(long githubId, String customerName) {
    Optional<UUID> existing =
        jdbcClient
            .sql("SELECT account_id FROM users WHERE github_id = :githubId FOR UPDATE")
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
