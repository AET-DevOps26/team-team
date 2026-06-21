# Enable Banking (PSD2) Integration -- Implementation Summary

## Overview

Integrated Enable Banking, a PSD2 aggregator, into the existing banking dashboard so users can connect real bank accounts and view actual balances, transactions, and trends instead of hardcoded demo data.

A new `banking-service` microservice (port 8084) handles all Enable Banking API interaction: listing banks, initiating PSD2 authorization, exchanging OAuth codes for sessions, and syncing balances/transactions into the shared database.

---

## Step-by-Step Changes

### 1. Database Schema

**Modified:** `infra/docker/init.sql`

- Added `banking_connections` table to track PSD2 connection state (session ID, bank name, external account UID, status, OAuth state parameter)

### 2. New `banking-service` Microservice

**Created:** `server/banking-service/` (entire directory -- 16 source files)

| File                                     | Purpose                                                                                                                                                                                      |
| ---------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `build.gradle.kts`                       | Gradle build config; adds `nimbus-jose-jwt` dependency for RS256 JWT signing                                                                                                                 |
| `src/main/resources/application.yml`     | Port 8084, PostgreSQL config, Enable Banking properties (app-id, private-key-path, redirect-url, base-url)                                                                                   |
| `BankingServiceApplication.java`         | Spring Boot entry point                                                                                                                                                                      |
| `config/EnableBankingConfig.java`        | `@Value`-injected Enable Banking configuration                                                                                                                                               |
| `client/EnableBankingJwtSigner.java`     | Loads RSA private key from `.pem` file, signs RS256 JWTs per Enable Banking spec (iss, aud, kid, exp)                                                                                        |
| `client/EnableBankingClient.java`        | REST client wrapping all Enable Banking API calls with JWT auth: list banks (`GET /aspsps`), initiate auth (`POST /auth`), create session (`POST /sessions`), get balances, get transactions |
| `client/HttpConfig.java`                 | RestTemplate bean with 3s connect / 5s read timeouts                                                                                                                                         |
| `model/BankingConnection.java`           | JPA entity for `banking_connections` table                                                                                                                                                   |
| `model/BankingConnectionRepository.java` | Queries: `findByState()`, `findByAccountIdAndStatus()`, `findByAccountId()`                                                                                                                  |
| `model/Account.java`                     | Duplicated JPA entity for `accounts` table (shared DB, needed for direct writes)                                                                                                             |
| `model/AccountRepository.java`           | `JpaRepository<Account, UUID>`                                                                                                                                                               |
| `model/Transaction.java`                 | Duplicated JPA entity for `transactions` table                                                                                                                                               |
| `model/TransactionRepository.java`       | Includes dedup query `findByAccountIdAndCategoryAndAmountAndDirection()`                                                                                                                     |
| `dto/ConnectBankRequest.java`            | Record: bankName, country, accountId                                                                                                                                                         |
| `dto/ConnectionStatus.java`              | Record: status, bankName, country                                                                                                                                                            |
| `service/BankingSyncService.java`        | After session creation: fetches balances/transactions from Enable Banking, updates account balance, inserts new transactions with deduplication                                              |
| `controller/BankingController.java`      | REST endpoints at `/api/banking/`: `GET /banks`, `POST /connect`, `POST /callback`, `GET /status/{accountId}`, `POST /sync/{accountId}`, `GET /health`                                       |

**Created:** `server/banking-service/Dockerfile`

- Multi-stage build following existing per-service Dockerfile pattern

### 3. Account-Service -- Real Trend Computation

**Modified:** `server/account-service/src/main/java/com/team/bank/account/AccountController.java`

- Replaced hardcoded 6-month trend with computed trend: fetches transactions from transaction-service, groups by month, walks backward from current balance to reconstruct 6 months of balance points

**Created:** `server/account-service/src/main/java/com/team/bank/account/HttpConfig.java`

- RestTemplate bean (same pattern as orchestrator)

**Created:** `server/account-service/src/main/java/com/team/bank/account/TransactionItem.java`

- DTO record for deserializing transaction-service responses

**Modified:** `server/account-service/src/main/resources/application.yml`

- Added `services.transaction.url` property

### 4. Orchestrator-Service -- Banking Proxy & Connection Status

**Modified:** `server/orchestrator-service/src/main/java/com/team/bank/orchestrator/DashboardController.java`

- Added 5 proxy endpoints forwarding `/api/banking/**` to banking-service
- Dashboard now fetches connection status from banking-service (try-catch so dashboard works if banking-service is down)
- GenAI summarization call wrapped in try-catch for graceful degradation

**Modified:** `server/orchestrator-service/src/main/java/com/team/bank/orchestrator/DashboardModels.java`

- Added `ConnectionStatus` record
- Added `connectionStatus` field to `DashboardResponse`

**Modified:** `server/orchestrator-service/src/main/resources/application.yml`

- Added `services.banking.url` property

### 5. Frontend -- Bank Picker, Callback Handling, Demo Indicators

**Modified:** `client/src/api.ts`

- Added `BankListItem`, `ConnectionStatus` interfaces
- Added `connectionStatus` to `DashboardPayload`
- Added `fetchBanks()`, `connectBank()`, `handleBankCallback()` API functions

**Modified:** `client/src/App.tsx`

- OAuth callback handling: detects `?code=&state=` URL params, exchanges for session, reloads dashboard
- Bank Connection panel: country dropdown, "Load Banks" button, scrollable bank list with "Connect" buttons
- Demo data indicators: amber "Demo Data" badge in header, yellow banner, "sample" tags on KPI cards and panels, 75% opacity on demo panels -- all disappear when a bank is connected

**Modified:** `client/src/styles/app.css`

- Added styles for `.demo-banner`, `.demo-badge`, `.demo-tag`, `.card--demo`, `.panel--demo`
- Added styles for `.bank-picker`, `.bank-list`, `.connection-active`

### 6. Gradle Module Registration

**Modified:** `settings.gradle.kts`

- Added `include(":banking-service")` and project directory mapping

### 7. Docker & CI

**Modified:** `docker-compose.yml`

- Added `banking-service` container with Enable Banking env vars and `.pem` volume mount
- Added `TRANSACTION_SERVICE_URL` to account-service
- Added `BANKING_SERVICE_URL` to orchestrator-service

**Modified:** `docker-compose.dev.yml`

- Same changes as above, using `build:` instead of `image:`

**Modified:** `.github/workflows/docker.yaml`

- Added `banking-service` to CI build matrix

**Modified:** `infra/monitoring/prometheus.yml`

- Added banking-service scrape job

---

## File Inventory

### New Files (20)

```
server/banking-service/build.gradle.kts
server/banking-service/Dockerfile
server/banking-service/src/main/resources/application.yml
server/banking-service/src/main/java/com/team/bank/banking/BankingServiceApplication.java
server/banking-service/src/main/java/com/team/bank/banking/config/EnableBankingConfig.java
server/banking-service/src/main/java/com/team/bank/banking/client/HttpConfig.java
server/banking-service/src/main/java/com/team/bank/banking/client/EnableBankingJwtSigner.java
server/banking-service/src/main/java/com/team/bank/banking/client/EnableBankingClient.java
server/banking-service/src/main/java/com/team/bank/banking/model/BankingConnection.java
server/banking-service/src/main/java/com/team/bank/banking/model/BankingConnectionRepository.java
server/banking-service/src/main/java/com/team/bank/banking/model/Account.java
server/banking-service/src/main/java/com/team/bank/banking/model/AccountRepository.java
server/banking-service/src/main/java/com/team/bank/banking/model/Transaction.java
server/banking-service/src/main/java/com/team/bank/banking/model/TransactionRepository.java
server/banking-service/src/main/java/com/team/bank/banking/dto/ConnectBankRequest.java
server/banking-service/src/main/java/com/team/bank/banking/dto/ConnectionStatus.java
server/banking-service/src/main/java/com/team/bank/banking/service/BankingSyncService.java
server/banking-service/src/main/java/com/team/bank/banking/controller/BankingController.java
server/account-service/src/main/java/com/team/bank/account/HttpConfig.java
server/account-service/src/main/java/com/team/bank/account/TransactionItem.java
```

### Modified Files (15)

```
settings.gradle.kts
infra/docker/init.sql
server/account-service/src/main/java/com/team/bank/account/AccountController.java
server/account-service/src/main/resources/application.yml
server/orchestrator-service/src/main/java/com/team/bank/orchestrator/DashboardController.java
server/orchestrator-service/src/main/java/com/team/bank/orchestrator/DashboardModels.java
server/orchestrator-service/src/main/resources/application.yml
client/src/api.ts
client/src/App.tsx
client/src/styles/app.css
docker-compose.yml
docker-compose.dev.yml
.github/workflows/docker.yaml
infra/monitoring/prometheus.yml
client/package-lock.json
```

---

## How to Run Locally

### Prerequisites

1. Sign up at https://enablebanking.com/sign-up/
2. Register an application, download the `.pem` private key
3. Add `http://localhost:5173/callback` as a redirect URL in the Enable Banking Control Panel
4. Create `.env` at project root:
   ```
   ENABLE_BANKING_APP_ID=<your-app-id>
   ENABLE_BANKING_PRIVATE_KEY_PATH=/path/to/private.pem
   ENABLE_BANKING_REDIRECT_URL=http://localhost:5173/callback
   ```

### Start Services

```bash
# 1. Start PostgreSQL (creates banking_connections table via init.sql)
docker compose up postgres -d

# 2. Start backend services (4 terminals, or background with &)
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5434/bankdb \
  ./gradlew :transaction-service:bootRun

SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5434/bankdb \
  TRANSACTION_SERVICE_URL=http://localhost:8082 \
  ./gradlew :account-service:bootRun

SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5434/bankdb \
  ENABLE_BANKING_APP_ID=<your-app-id> \
  ENABLE_BANKING_PRIVATE_KEY_PATH=/path/to/private.pem \
  ENABLE_BANKING_REDIRECT_URL=http://localhost:5173/callback \
  ./gradlew :banking-service:bootRun

ACCOUNT_SERVICE_URL=http://localhost:8081 \
  TRANSACTION_SERVICE_URL=http://localhost:8082 \
  BANKING_SERVICE_URL=http://localhost:8084 \
  APP_CORS_ALLOWED_ORIGINS=http://localhost:5173 \
  ./gradlew :orchestrator-service:bootRun

# 3. Start frontend
cd client && npm run dev
```

### Test the Flow

1. Open http://localhost:5173
2. Dashboard shows demo data with amber "sample" indicators
3. Select country, click "Load Banks", pick a bank (use "Mock ASPSP" for sandbox testing)
4. Click "Connect" -- redirects to bank PSD2 login
5. Authorize (Mock ASPSP credentials: `customera` / `12345678`)
6. Redirected back -- dashboard reloads with real data, demo indicators disappear
