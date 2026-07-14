# Testing Plan — Team Banking Application

> **Scope**: All server-side microservices (account, transaction, orchestrator, banking) and the React client. GenAI (Python) component excluded per request.

---

## Table of Contents

1. [Current State](#current-state)
2. [Test Pyramid & Strategy](#test-pyramid--strategy)
3. [Server-Side Test Plan](#server-side-test-plan)
   - [3.1 account-service](#31-account-service)
   - [3.2 transaction-service](#32-transaction-service)
   - [3.3 orchestrator-service](#33-orchestrator-service)
   - [3.4 banking-service](#34-banking-service)
4. [Client-Side Test Plan](#client-side-test-plan)
5. [CI Pipeline Integration](#ci-pipeline-integration)
6. [Prerequisites & Dependencies](#prerequisites--dependencies)
7. [Implementation Order & Effort Estimate](#implementation-order--effort-estimate)

---

## Current State

| Area                     | What Exists                                      | What's Missing                                                |
| ------------------------ | ------------------------------------------------ | ------------------------------------------------------------- |
| **account-service**      | 1 trivial DTO test (`AccountSummaryTest`)        | Controller tests, repository tests, service logic tests       |
| **transaction-service**  | 1 trivial DTO test (`ExpenseSliceTest`)          | Controller tests, repository tests, expense breakdown tests   |
| **orchestrator-service** | 1 trivial DTO test (`ChatRequestTest`)           | Controller tests, WebClient error handling, fallback logic    |
| **banking-service**      | **ZERO tests**                                   | Everything — controller, sync service, JWT signer, API client |
| **Client (React)**       | 6 workflow tests in `App.test.tsx`               | Component unit tests, API error handling, edge cases          |
| **Test configs**         | None (`src/test/resources/`)                     | H2/test DB config, test Spring profiles                       |
| **CI**                   | `./gradlew check` + `npm run test` already in CI | Coverage thresholds, test report publishing                   |

---

## Test Pyramid & Strategy

```
         ┌──────────────┐
         │   E2E Tests   │  (future: Playwright)
         ├──────────────┤
         │ Integration   │  @SpringBootTest, @DataJpaTest
         ├──────────────┤
         │  Unit Tests   │  Controllers (@WebMvcTest), Services, Utils
         └──────────────┘
```

### Guiding Principles

1. **Unit tests** cover critical business logic and are mandatory for all services
2. **Integration tests** cover database interactions and cross-service communication
3. **Client tests** cover core user workflows and interaction patterns
4. **All tests run in CI** — `./gradlew clean check` and `npm run test` already gate merges
5. **Tests are deterministic** — no reliance on external services with mocked boundaries
6. **Tests are fast** — unit tests < 10s total per service, integration tests use H2 in-memory DB

---

## Server-Side Test Plan

### Test Framework & Dependencies Already Available

- **JUnit 5** (Jupiter) — `useJUnitPlatform()` configured in root `build.gradle.kts`
- **Mockito** — included via `spring-boot-starter-test`
- **MockMvc** — included via `spring-boot-starter-test` for `@WebMvcTest`
- **AssertJ / Hamcrest** — included via `spring-boot-starter-test`

### New Dependencies Needed

Add to `server/gradle/libs.versions.toml`:

```toml
[libraries]
com-h2database-h2 = { module = "com.h2database:h2", version = "2.3.232" }
```

Add to `account-service`, `transaction-service`, and `banking-service` `build.gradle.kts`:

```kotlin
testImplementation("com.h2database:h2")
```

> **Rationale**: H2 in PostgreSQL compatibility mode provides fast, isolated repository/integration tests without requiring a running PostgreSQL instance in CI.

---

### 3.1 account-service

**Critical business logic:**

- `utilizationRate()` — balance ÷ creditLimit with zero/nulls handling
- `getBalanceTrend()` — 6-month balance reconstruction from transaction history

#### Test File: `AccountControllerTest.java` (`@WebMvcTest`)

| #   | Test Name                                            | Category  | What It Validates                                                     |
| --- | ---------------------------------------------------- | --------- | --------------------------------------------------------------------- |
| 1   | `shouldReturnAccountSummary`                         | Unit      | `GET /{accountId}` → 200 with correct AccountSummary fields           |
| 2   | `shouldReturn404ForUnknownAccount`                   | Unit      | `GET /{nonexistent}` → 404 with error message                         |
| 3   | `shouldReturn200Health`                              | Unit      | `GET /health` → `{"status":"UP","service":"account-service"}`         |
| 4   | `shouldReturnTrendWithTransactions`                  | Unit      | `GET /{id}/trend` → 6 BalancePoints reconstructed correctly           |
| 5   | `shouldReturnSinglePointWhenNoTransactions`          | Unit      | `GET /{id}/trend` with no tx data → 1 balance point                   |
| 6   | `shouldHandleTransactionServiceUnavailable`          | Edge Case | Transaction service down → graceful fallback to single point          |
| 7   | `shouldReturnCorrectTrendWhenTransactionsSpanMonths` | Unit      | Transactions across Jan–Jun → correct backward-reconstructed balances |

#### Test File: `UtilizationRateTest.java` (pure unit, no Spring)

| #   | Test Name                                   | Category  | What It Validates                         |
| --- | ------------------------------------------- | --------- | ----------------------------------------- |
| 1   | `shouldReturnZeroWhenBalanceIsNull`         | Edge Case | null balance → 0                          |
| 2   | `shouldReturnZeroWhenCreditLimitIsNull`     | Edge Case | null creditLimit → 0                      |
| 3   | `shouldReturnZeroWhenCreditLimitIsZero`     | Edge Case | creditLimit = 0 → 0 (no division by zero) |
| 4   | `shouldReturnZeroWhenCreditLimitIsNegative` | Edge Case | creditLimit < 0 → 0                       |
| 5   | `shouldComputeCorrectRateForNormalValues`   | Unit      | balance 300 / limit 1000 → 0.3000         |
| 6   | `shouldComputeRateWithFourDecimalPrecision` | Unit      | balance 1 / limit 3 → 0.3333 (HALF_UP)    |

#### Test File: `AccountRepositoryTest.java` (`@DataJpaTest`)

| #   | Test Name                         | Category    | What It Validates                                           |
| --- | --------------------------------- | ----------- | ----------------------------------------------------------- |
| 1   | `shouldFindById`                  | Integration | Save → findById returns the account                         |
| 2   | `shouldReturnEmptyForUnknownId`   | Integration | findById random UUID → Optional.empty()                     |
| 3   | `shouldPersistAllFieldsCorrectly` | Integration | Round-trip: customerName, accountType, balance, creditLimit |

---

### 3.2 transaction-service

**Critical business logic:**

- `expenseBreakdown()` — group DEBIT transactions by category, compute percentage, sort descending

#### Test File: `TransactionControllerTest.java` (`@WebMvcTest`)

| #   | Test Name                                           | Category  | What It Validates                                                                          |
| --- | --------------------------------------------------- | --------- | ------------------------------------------------------------------------------------------ |
| 1   | `shouldListTransactionsByAccountId`                 | Unit      | `GET /{accountId}` → 200 with TransactionItem list                                         |
| 2   | `shouldReturnEmptyListForAccountWithNoTransactions` | Unit      | No transactions → 200 with `[]`                                                            |
| 3   | `shouldComputeExpenseBreakdownCorrectly`            | Unit      | `GET /{id}/expenses` — 3 DEBIT tx (Food: 50, Rent: 100, Food: 50) → [Rent: 50%, Food: 50%] |
| 4   | `shouldReturnEmptyExpensesWhenOnlyCredits`          | Edge Case | Only CREDIT transactions → empty list                                                      |
| 5   | `shouldReturnEmptyExpensesForNoTransactions`        | Edge Case | No transactions → empty list                                                               |
| 6   | `shouldSortExpensesDescendingByPercentage`          | Unit      | Verify largest percentage first                                                            |
| 7   | `shouldRoundPercentagesToWholeNumbers`              | Unit      | Verify HALF_UP rounding (e.g., 33.6% → 34%)                                                |
| 8   | `shouldHandleSingleExpenseCategory`                 | Unit      | One category → 100%                                                                        |
| 9   | `shouldHandleCategoriesWithSameAmount`              | Edge Case | Two categories both 50 → 50%/50%                                                           |

#### Test File: `TransactionRepositoryTest.java` (`@DataJpaTest`)

| #   | Test Name                                   | Category    | What It Validates                                  |
| --- | ------------------------------------------- | ----------- | -------------------------------------------------- |
| 1   | `shouldFindByAccountIdOrderByCreatedAtDesc` | Integration | Insert 3 tx → verify descending order              |
| 2   | `shouldReturnEmptyForUnknownAccountId`      | Integration | No transactions for unknown UUID                   |
| 3   | `shouldPersistAllTransactionFields`         | Integration | Round-trip: category, amount, direction, createdAt |

#### Test File: `ExpenseBreakdownTest.java` (pure unit, no Spring)

| #   | Test Name                                     | Category  | What It Validates                        |
| --- | --------------------------------------------- | --------- | ---------------------------------------- |
| 1   | `shouldHandleEmptyTransactionList`            | Edge Case | Empty list → empty breakdown             |
| 2   | `shouldGroupMultipleTransactionsSameCategory` | Unit      | 2× Food DEBIT → combined percentage      |
| 3   | `shouldIgnoreCreditTransactions`              | Unit      | Mix of CREDIT/DEBIT → only DEBIT counted |
| 4   | `shouldSumPercentagesTo100`                   | Invariant | All percentages should sum to ~100       |
| 5   | `shouldHandleVerySmallAmounts`                | Edge Case | €0.01 transactions → correct rounding    |

---

### 3.3 orchestrator-service

**Critical business logic:**

- `dashboard()` — aggregates 4 downstream services, handles partial failures gracefully
- `chat()` — proxies to genai with fallback response on failure
- Thin proxies for banking endpoints

#### Test File: `DashboardControllerTest.java` (`@WebMvcTest` with mocked `WebClient`)

Since the orchestrator uses `WebClient` (not `RestTemplate`), we test with `MockWebServer` (OkHttp) or a mocked `WebClient` bean.

| #   | Test Name                                              | Category   | What It Validates                                                  |
| --- | ------------------------------------------------------ | ---------- | ------------------------------------------------------------------ |
| 1   | `shouldReturnApiIndex`                                 | Unit       | `GET /api` → service info with UP status                           |
| 2   | `shouldAggregateDashboardSuccessfully`                 | Unit       | All 4 downstream services return data → complete DashboardResponse |
| 3   | `shouldReturnBadGatewayWhenAccountServiceFails`        | Edge Case  | account-service returns null → 502 BAD_GATEWAY                     |
| 4   | `shouldContinueWithoutTrendWhenTrendIsNull`            | Robustness | trend endpoint returns null → empty list, not crash                |
| 5   | `shouldContinueWithoutExpensesWhenExpenseIsNull`       | Robustness | expenses returns null → empty list                                 |
| 6   | `shouldFallbackSummaryWhenGenaiUnavailable`            | Robustness | genai-service throws → "No summary available." fallback            |
| 7   | `shouldFallbackConnectionStatusWhenBankingUnavailable` | Robustness | banking-service throws → null connectionStatus                     |
| 8   | `shouldHandleAllDownstreamServicesFailing`             | Edge Case  | All services fail → still returns response with defaults           |
| 9   | `shouldValidateChatRequestHasMessages`                 | Validation | Empty request → 400 BAD_REQUEST                                    |
| 10  | `shouldValidateChatRequestNotNull`                     | Validation | null request body → 400 BAD_REQUEST                                |
| 11  | `shouldSupportSingleMessageField`                      | Unit       | `{"message":"hello"}` → valid (backward compat)                    |
| 12  | `shouldSupportMessagesArrayField`                      | Unit       | `{"messages":[...]}` → valid                                       |
| 13  | `shouldReturnFallbackChatWhenGenaiFails`               | Robustness | genai-service throws → "I could not process that request."         |
| 14  | `shouldProxyBankListRequest`                           | Unit       | `GET /api/banking/banks?country=DE` → forwarded to banking-service |
| 15  | `shouldProxyConnectRequest`                            | Unit       | `POST /api/banking/connect` → forwarded                            |
| 16  | `shouldProxyCallbackRequest`                           | Unit       | `POST /api/banking/callback` → forwarded                           |

#### Test Approach for WebClient Mocking

Two options:

1. **Mock `WebClient` bean** — Inject a mock and stub `.get()/.post()/.uri()/.retrieve()/.bodyToMono()` chains
2. **`MockWebServer` (OkHttp)** — Run a real HTTP server in test, stub responses, inject URL

**Recommendation**: Use **MockWebServer** (add `com.squareup.okhttp3:mockwebserver:4.12.0` as `testImplementation`) — it's simpler for WebClient testing and avoids the deep Mockito stubbing chain.

---

### 3.4 banking-service

**Critical business logic:**

- **OAuth-like flow**: `POST /connect` → creates PENDING connection → return auth URL
- **Callback handling**: `POST /callback` → exchanges code for session → marks ACTIVE → triggers sync
- **Sync logic** (`BankingSyncService`): Balance update, transaction deduplication, `parseAmount()` robustness
- **Status endpoint**: Prefer ACTIVE over PENDING, fallback to most-recently-updated

#### Test File: `BankingControllerTest.java` (`@WebMvcTest`)

| #   | Test Name                                              | Category   | What It Validates                                            |
| --- | ------------------------------------------------------ | ---------- | ------------------------------------------------------------ |
| 1   | `shouldListBanksForCountry`                            | Unit       | `GET /banks?country=FI` → 200 with bank list                 |
| 2   | `shouldReturnEmptyListWhenNoBanksFound`                | Edge Case  | enableBanking returns empty → 200 with `[]`                  |
| 3   | `shouldConnectBankAndReturnAuthUrl`                    | Unit       | `POST /connect` → 200 with authUrl, connection PENDING in DB |
| 4   | `shouldReturnBadRequestForNullCodeInCallback`          | Validation | `{"code":null,"state":"abc"}` → 400                          |
| 5   | `shouldReturnBadRequestForBlankStateInCallback`        | Validation | `{"code":"abc","state":""}` → 400                            |
| 6   | `shouldReturnBadRequestForUnknownStateInCallback`      | Validation | `{"code":"abc","state":"nonexistent"}` → 400                 |
| 7   | `shouldReturnBadGatewayWhenSessionCreationReturnsNull` | Edge Case  | createSession → null → 502                                   |
| 8   | `shouldReturnBadGatewayWhenNoExternalAccountUid`       | Edge Case  | session has no accounts → 502                                |
| 9   | `shouldCompleteCallbackAndMarkActive`                  | Unit       | Valid callback → ACTIVE, sync triggered, 200                 |
| 10  | `shouldReturnNoneStatusWhenNoConnections`              | Unit       | `GET /status/{unknownId}` → `{"status":"NONE"}`              |
| 11  | `shouldPreferActiveOverPendingConnections`             | Unit       | 1 PENDING + 1 ACTIVE → returns ACTIVE                        |
| 12  | `shouldFallbackToMostRecentWhenNoActive`               | Edge Case  | 2 PENDING connections → returns most recently updated        |
| 13  | `shouldReturn400OnSyncWithoutActiveConnection`         | Validation | `POST /sync/{id}` with no ACTIVE → 400                       |
| 14  | `shouldReturn200Health`                                | Unit       | `GET /health` → service up                                   |

#### Test File: `BankingSyncServiceTest.java` (pure unit, Mockito)

| #   | Test Name                                        | Category   | What It Validates                                                                    |
| --- | ------------------------------------------------ | ---------- | ------------------------------------------------------------------------------------ |
| 1   | `shouldSkipSyncWhenExternalUidIsNull`            | Edge Case  | null externalUid → early return, no API calls                                        |
| 2   | `shouldSkipSyncWhenExternalUidIsBlank`           | Edge Case  | blank externalUid → early return                                                     |
| 3   | `shouldUpdateAccountBalanceFromApi`              | Unit       | getBalances returns valid data → account balance updated                             |
| 4   | `shouldNotUpdateBalanceWhenResponseIsNull`       | Robustness | getBalances returns null → no update, no crash                                       |
| 5   | `shouldNotUpdateBalanceWhenBalancesListIsEmpty`  | Edge Case  | getBalances has empty balances list → skip                                           |
| 6   | `shouldInsertNewTransactions`                    | Unit       | getTransactions returns 2 new tx → both inserted                                     |
| 7   | `shouldSkipDuplicateTransactions`                | Unit       | Transaction with same (account, category, amount, direction) already in DB → skipped |
| 8   | `shouldNotInsertTransactionsWhenResponseIsNull`  | Robustness | getTransactions returns null → no insert, no crash                                   |
| 9   | `shouldMapCreditDebitIndicatorToDirection`       | Unit       | "CRDT" → "CREDIT", "DBIT" → "DEBIT", null → "DEBIT"                                  |
| 10  | `shouldFallbackCategoryWhenRemittanceInfoIsNull` | Edge Case  | null remittanceInformation → "Uncategorized"                                         |
| 11  | `shouldUpdateConnectionTimestampAfterSync`       | Unit       | After sync → connection.updatedAt is refreshed                                       |
| 12  | `shouldLogWarningWhenSkippingDueToMissingUid`    | Robustness | Verify log message emitted                                                           |

#### Test File: `BankingConnectionRepositoryTest.java` (`@DataJpaTest`)

| #   | Test Name                        | Category    | What It Validates                                                                       |
| --- | -------------------------------- | ----------- | --------------------------------------------------------------------------------------- |
| 1   | `shouldFindByState`              | Integration | Save connection → findByState returns it                                                |
| 2   | `shouldFindByAccountIdAndStatus` | Integration | Save ACTIVE connection → findByAccountIdAndStatus returns it                            |
| 3   | `shouldNotFindWhenStatusDiffers` | Integration | Save PENDING → findByAccountIdAndStatus(ACTIVE) returns empty                           |
| 4   | `shouldFindByAccountId`          | Integration | Save 2 connections for same account → both returned                                     |
| 5   | `shouldPersistAllFields`         | Integration | Round-trip: sessionId, bankName, country, state, status, externalAccountUid, timestamps |

#### Test File: `EnableBankingJwtSignerTest.java` (pure unit)

| #   | Test Name                           | Category  | What It Validates                                     |
| --- | ----------------------------------- | --------- | ----------------------------------------------------- |
| 1   | `shouldSignValidJwt`                | Unit      | sign() returns non-empty, well-formed JWT string      |
| 2   | `shouldContainCorrectClaims`        | Unit      | Decode JWT — verify iss, sub, iat, exp claims         |
| 3   | `shouldHandleMissingPrivateKeyFile` | Edge Case | Missing/empty key path → exception with clear message |

#### Test File: `ParseAmountTest.java` (pure unit, on `BankingSyncService.parseAmount()`)

| #   | Test Name                              | Category  | What It Validates                                          |
| --- | -------------------------------------- | --------- | ---------------------------------------------------------- |
| 1   | `shouldParseNestedAmountObject`        | Unit      | `{"amount":"150.00","currency":"EUR"}` → BigDecimal 150.00 |
| 2   | `shouldParseBareScalarAmount`          | Unit      | `"150.00"` → BigDecimal 150.00                             |
| 3   | `shouldReturnNullForNullInput`         | Edge Case | null → null                                                |
| 4   | `shouldReturnNullForEmptyMap`          | Edge Case | `{}` → null                                                |
| 5   | `shouldReturnNullForUnparseableString` | Edge Case | `"not-a-number"` → null                                    |
| 6   | `shouldParseNegativeAmount`            | Unit      | `"-50.00"` → BigDecimal -50.00                             |
| 7   | `shouldParseZeroAmount`                | Unit      | `"0"` → BigDecimal 0                                       |

---

## Client-Side Test Plan

**Framework**: Vitest + React Testing Library + jest-dom
**Already configured**: `vite.config.ts`, `src/test/setup.ts`, `tsconfig.app.json`

### Existing Tests (6 tests in `App.test.tsx`)

These already cover:

- Login flow (admin/admin)
- Empty state (no bank connected)
- Active bank dashboard
- Bank list render
- Sign out flow
- Chat message send/receive

### New Tests Needed

#### Test File: `App.test.tsx` (extend existing)

| #   | Test Name                                       | Category    | What It Validates                                                   |
| --- | ----------------------------------------------- | ----------- | ------------------------------------------------------------------- |
| 1   | `shows login error for wrong credentials`       | Edge Case   | Wrong password → error message displayed                            |
| 2   | `shows error when dashboard fetch fails`        | Edge Case   | fetchDashboard rejects → error state shown                          |
| 3   | `shows error message when bank list fails`      | Edge Case   | fetchBanks rejects → error message displayed                        |
| 4   | `shows error when bank connect fails`           | Edge Case   | connectBank rejects → error message, button re-enabled              |
| 5   | `handles bank callback via query params`        | Integration | URL has `?code=x&state=y` → handleBankCallback called               |
| 6   | `shows expense breakdown with percentages`      | Unit        | Dashboard with expenses → expense categories + percentages rendered |
| 7   | `renders trend chart with multiple data points` | Unit        | Dashboard with 2+ trend points → SVG polyline rendered              |
| 8   | `hides trend chart with insufficient data`      | Edge Case   | Only 1 trend point → chart not rendered                             |
| 9   | `formats currency values correctly`             | Unit        | Balance rendered as €1,200                                          |
| 10  | `shows chat history after multiple messages`    | Integration | Send 2 messages → both shown in chat log                            |
| 11  | `persists chat sessions across reloads`         | Integration | Send a message → reload → chat still present                        |
| 12  | `allows deleting a chat session`                | Unit        | Delete chat → chat removed from overview                            |
| 13  | `shows suggested prompts in empty chat state`   | Unit        | New chat → 3 suggested prompts displayed                            |
| 14  | `shows fallback message when chat fails`        | Edge Case   | sendChat rejects → "Assistant is unavailable" shown                 |
| 15  | `auto-names chat session from first message`    | Unit        | First message "What is my balance?" → title set accordingly         |
| 16  | `closes chat panel with close button`           | Unit        | Click × → chat panel hidden                                         |
| 17  | `maintains separate chats per accountId`        | Integration | Chat with account A → switch to B → different chats                 |

#### Test File: `api.test.ts` (new — pure unit tests for API client)

| #   | Test Name                              | Category  | What It Validates                                 |
| --- | -------------------------------------- | --------- | ------------------------------------------------- |
| 1   | `shouldFetchDashboardSuccessfully`     | Unit      | Mock fetch → fetchDashboard returns typed payload |
| 2   | `shouldThrowOnDashboardFetchFailure`   | Edge Case | 500 response → Error thrown                       |
| 3   | `shouldFetchBanksForCountry`           | Unit      | fetchBanks("DE") → correct URL constructed        |
| 4   | `shouldConnectBankWithCorrectBody`     | Unit      | connectBank(...) → correct JSON body sent         |
| 5   | `shouldHandleCallbackWithCodeAndState` | Unit      | handleBankCallback → correct POST body            |
| 6   | `shouldSendChatWithHistoryAndContext`  | Unit      | sendChat → correct payload structure              |
| 7   | `shouldRespectCustomApiBaseUrl`        | Unit      | VITE_API_BASE_URL set → prepended to all URLs     |
| 8   | `shouldDefaultToEmptyBaseUrl`          | Unit      | No env var → relative URLs                        |

#### Test File: `TrendChart.test.tsx` (new — component unit test)

| #   | Test Name                               | Category  | What It Validates                                      |
| --- | --------------------------------------- | --------- | ------------------------------------------------------ |
| 1   | `shouldRenderSvgWithPolyline`           | Unit      | 2+ data points → SVG + polyline present                |
| 2   | `shouldNotRenderWhenInsufficientPoints` | Edge Case | 1 point → null (no render)                             |
| 3   | `shouldRenderMonthAxisLabels`           | Unit      | Trend with Jan–Jun → all 6 month labels visible        |
| 4   | `shouldPositionEndDotCorrectly`         | Unit      | Last data point → enddot positioned at last coordinate |
| 5   | `shouldRecalculateOnTrendChange`        | Unit      | Rerender with new data → updated polyline points       |

---

## CI Pipeline Integration

### Current State

The CI pipeline (`.github/workflows/ci.yml`) already runs:

```yaml
# Backend: compile + static analysis + tests
./gradlew clean check

# Frontend: lint + test + build
npm run lint && npm run test && npm run build
```

This means **all new tests will automatically execute in CI** — no CI changes strictly required.

### Recommended CI Enhancements

| Enhancement                | Priority | Description                                                                                                            |
| -------------------------- | -------- | ---------------------------------------------------------------------------------------------------------------------- |
| **Test report publishing** | Medium   | Publish JUnit XML reports via `dorny/test-reporter` for visibility                                                     |
| **Coverage thresholds**    | Medium   | Add JaCoCo to Gradle, enforce minimum line/branch coverage (e.g., 70% for critical services)                           |
| **Test splitting**         | Low      | Split `build-test` into parallel matrix jobs (account, transaction, orchestrator, banking, client) for faster feedback |
| **Test retries**           | Low      | Configure Gradle test retry plugin for flaky test mitigation                                                           |

### JaCoCo Coverage Configuration

Add to root `build.gradle.kts`:

```kotlin
// In subprojects block:
apply(plugin = "jacoco")

tasks.jacocoTestReport {
    reports {
        xml.required = true
        html.required = true
    }
}

tasks.jacocoTestCoverageVerification {
    violationRules {
        rule {
            limit {
                minimum = "0.70".toBigDecimal() // 70% line coverage
            }
        }
    }
}
```

Add to CI workflow after `./gradlew clean check`:

```yaml
- name: Publish test coverage
  uses: dorny/test-reporter@v2
  with:
    name: Backend Tests
    path: "server/**/build/reports/tests/test/*.xml"
    reporter: java-junit
```

---

## Prerequisites & Dependencies

### New Gradle Dependencies

| Dependency                                  | Purpose                           | Services                      |
| ------------------------------------------- | --------------------------------- | ----------------------------- |
| `com.h2database:h2:2.3.232`                 | In-memory DB for `@DataJpaTest`   | account, transaction, banking |
| `com.squareup.okhttp3:mockwebserver:4.12.0` | WebClient mocking in orchestrator | orchestrator                  |

### New npm Dependencies (Client)

None — Vitest, React Testing Library, and jest-dom are already configured.

### Test Configuration Files Needed

| File                                                                  | Purpose                                          |
| --------------------------------------------------------------------- | ------------------------------------------------ |
| `server/account-service/src/test/resources/application-test.yml`      | H2 datasource, PostgreSQL compatibility mode     |
| `server/transaction-service/src/test/resources/application-test.yml`  | H2 datasource                                    |
| `server/banking-service/src/test/resources/application-test.yml`      | H2 datasource + banking config defaults          |
| `server/orchestrator-service/src/test/resources/application-test.yml` | Downstream service URLs pointed to MockWebServer |

Example `application-test.yml` for JPA services:

```yaml
spring:
  datasource:
    url: jdbc:h2:mem:testdb;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE
    driver-class-name: org.h2.Driver
    username: sa
    password:
  jpa:
    hibernate:
      ddl-auto: create-drop
    database-platform: org.hibernate.dialect.H2Dialect
```

---

## Implementation Order & Effort Estimate

### Phase 1: Foundation (enable test infrastructure)

| #   | Task                                                                  | Effort |
| --- | --------------------------------------------------------------------- | ------ |
| 1.1 | Add H2 dependency to account, transaction, banking `build.gradle.kts` | 0.5h   |
| 1.2 | Add MockWebServer dependency to orchestrator `build.gradle.kts`       | 0.25h  |
| 1.3 | Create `application-test.yml` for all 4 services                      | 0.5h   |
| 1.4 | Create `src/test/resources/` directories                              | 0.25h  |

### Phase 2: Core Business Logic Tests (highest value)

| #   | Task                                                        | Effort |
| --- | ----------------------------------------------------------- | ------ |
| 2.1 | `UtilizationRateTest.java` (account-service) — 6 tests      | 1h     |
| 2.2 | `ExpenseBreakdownTest.java` (transaction-service) — 5 tests | 1h     |
| 2.3 | `ParseAmountTest.java` (banking-service) — 7 tests          | 1h     |
| 2.4 | `BankingSyncServiceTest.java` (banking-service) — 12 tests  | 2h     |

### Phase 3: Controller Integration Tests

| #   | Task                                                     | Effort |
| --- | -------------------------------------------------------- | ------ |
| 3.1 | `AccountControllerTest.java` — 7 tests                   | 1.5h   |
| 3.2 | `TransactionControllerTest.java` — 9 tests               | 1.5h   |
| 3.3 | `DashboardControllerTest.java` (orchestrator) — 16 tests | 2.5h   |
| 3.4 | `BankingControllerTest.java` — 14 tests                  | 2h     |

### Phase 4: Repository Integration Tests

| #   | Task                                             | Effort |
| --- | ------------------------------------------------ | ------ |
| 4.1 | `AccountRepositoryTest.java` — 3 tests           | 0.5h   |
| 4.2 | `TransactionRepositoryTest.java` — 3 tests       | 0.5h   |
| 4.3 | `BankingConnectionRepositoryTest.java` — 5 tests | 0.75h  |

### Phase 5: Client-Side Tests

| #   | Task                                 | Effort |
| --- | ------------------------------------ | ------ |
| 5.1 | Extend `App.test.tsx` — 17 new tests | 3h     |
| 5.2 | New `api.test.ts` — 8 tests          | 1.5h   |
| 5.3 | New `TrendChart.test.tsx` — 5 tests  | 1h     |

### Phase 6: CI & Quality (optional enhancements)

| #   | Task                                                           | Effort |
| --- | -------------------------------------------------------------- | ------ |
| 6.1 | Add JaCoCo coverage to Gradle                                  | 0.5h   |
| 6.2 | Publish test reports in CI                                     | 0.5h   |
| 6.3 | Cover JWT signer (`EnableBankingJwtSignerTest.java`) — 3 tests | 1h     |

### Summary

| Phase                  | Tests          | Effort   |
| ---------------------- | -------------- | -------- |
| Phase 1 (Foundation)   | —              | 1.5h     |
| Phase 2 (Core Logic)   | 30 tests       | 5h       |
| Phase 3 (Controllers)  | 46 tests       | 7.5h     |
| Phase 4 (Repositories) | 11 tests       | 1.75h    |
| Phase 5 (Client)       | 30 tests       | 5.5h     |
| Phase 6 (CI/Quality)   | 3 tests        | 2h       |
| **Total**              | **~120 tests** | **~23h** |

---

## Test Naming Convention

All tests follow the pattern: **`should[ExpectedBehavior]When[Condition]`**

Examples:

- `shouldReturn404WhenAccountNotFound`
- `shouldSkipSyncWhenExternalUidIsNull`
- `shouldFallbackSummaryWhenGenaiUnavailable`

## Mocking Strategy

| Layer                        | Strategy                                                                     |
| ---------------------------- | ---------------------------------------------------------------------------- |
| **Controllers**              | Mock service/repository beans with `@MockBean`; use `MockMvc` for HTTP layer |
| **Services**                 | Mock repository and external client beans with `@Mock` / `@InjectMocks`      |
| **Repositories**             | Use `@DataJpaTest` with H2 — no mocking, real SQL                            |
| **WebClient (orchestrator)** | Use `MockWebServer` to simulate downstream HTTP services                     |
| **Client API**               | Use `vi.spyOn(api, ...)` for API mocking (pattern already established)       |
| **fetch (native)**           | Use `vi.fn()` to mock global `fetch` in `api.test.ts`                        |

---

_Plan prepared: 2026-07-10 — Excludes GenAI (Python) component per request._
