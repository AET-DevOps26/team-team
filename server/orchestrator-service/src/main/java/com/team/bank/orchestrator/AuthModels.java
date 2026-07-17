package com.team.bank.orchestrator;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.UUID;

/** Payloads for the lightweight GitHub OAuth login flow exposed by AuthController. */
final class AuthModels {
  private AuthModels() {}
}

/** Response returned to the SPA when it starts the GitHub OAuth flow. */
record LoginStartResponse(String authUrl, String state) {}

/** Body posted by the SPA once GitHub redirects back with a code+state. */
record CallbackRequest(String code, String state) {}

/** GitHub's /login/oauth/access_token response (only the field we use). */
record GitHubTokenResponse(
    @JsonProperty("access_token") String accessToken,
    @JsonProperty("token_type") String tokenType,
    @JsonProperty("scope") String scope,
    @JsonProperty("error") String error,
    @JsonProperty("error_description") String errorDescription) {}

/** Subset of https://api.github.com/user the app cares about. */
record GitHubUser(
    long id,
    String login,
    String name,
    @JsonProperty("avatar_url") String avatarUrl,
    String email) {}

/**
 * One entry from https://api.github.com/user/emails — used to resolve the primary verified email.
 */
record GitHubEmail(String email, boolean primary, boolean verified) {}

/**
 * Registered application user as we persist it in the `users` table. This is what the SPA sees as
 * the "current user" — GitHub's raw response is an internal detail. `accountId` is the per-user
 * aggregate account provisioned on first sign-in; the SPA uses it for Live-mode dashboard calls.
 */
record AppUser(
    long githubId,
    String login,
    String firstName,
    String lastName,
    String email,
    String avatarUrl,
    UUID accountId) {}

/** What the SPA stores in localStorage after a successful sign-in. */
record AuthSession(String token, AppUser user) {}
