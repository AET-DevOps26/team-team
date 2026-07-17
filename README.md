# Multi-Agent Banking System

> For the full problem statement and system overview (architecture, diagrams, backlog), see [docs/problem_statement+system_overview.md](docs/problem_statement+system_overview.md).

## 🚀 Endpoints

| Service               | Local (Docker)                            | Production (K8s)                                                         |
| --------------------- | ----------------------------------------- | ------------------------------------------------------------------------ |
| Frontend              | `https://localhost/`                      | `https://team-devops-ss26.stud.k8s.aet.cit.tum.de/`                      |
| Backend API           | `https://localhost/api`                   | `https://team-devops-ss26.stud.k8s.aet.cit.tum.de/api`                   |
| GitHub OAuth (start)  | `https://localhost/api/auth/github/login` | `https://team-devops-ss26.stud.k8s.aet.cit.tum.de/api/auth/github/login` |
| GitHub OAuth callback | `https://localhost/login/oauth2/code/github` | `https://team-devops-ss26.stud.k8s.aet.cit.tum.de/login/oauth2/code/github` |
| Banking (PSD2) API    | `https://localhost/api/banking/*`         | `https://team-devops-ss26.stud.k8s.aet.cit.tum.de/api/banking/*`         |
| Swagger UI            | `https://localhost/swagger-ui/index.html` | `https://team-devops-ss26.stud.k8s.aet.cit.tum.de/swagger-ui/index.html` |
| OpenAPI JSON          | `https://localhost/v3/api-docs`           | `https://team-devops-ss26.stud.k8s.aet.cit.tum.de/v3/api-docs`           |
| Grafana               | `https://localhost/grafana/`              | `https://team-devops-ss26.stud.k8s.aet.cit.tum.de/grafana/`              |
| Prometheus            | —                                         | `https://team-devops-ss26.stud.k8s.aet.cit.tum.de/prometheus/`           |
| Traefik Dashboard     | `http://localhost:8080/dashboard/`        | —                                                                        |

This repository contains a full mono-repo banking web application with:

- `client`: React + TypeScript frontend (with GitHub OAuth sign-in gate)
- `server`: Java Spring Boot microservices — **four** services, Gradle-built:
  `account-service`, `transaction-service`, `banking-service`, `orchestrator-service`.
- `genai`: Python-based GenAI microservice (FastAPI)
- `infra`: Docker Compose, Traefik reverse proxy, Kubernetes manifests, Helm chart (`infra/helm/banking-app`), monitoring stack (Prometheus + Alertmanager + Grafana), Terraform + Ansible bootstrap

---

## Prerequisites & Requirements

### Option 1: Docker-Based (Recommended)

**Linux System Requirements:**

```bash
# Install Docker
https://docs.docker.com/desktop/setup/install/linux/
```

**Versions:**

- Docker: 20.10+ (any recent version)
- Docker Compose: 2.0+
- Git: 2.0+

### Option 2: Local Development (Full Stack)

**Required Languages & Frameworks:**

| Component        | Language   | Framework            | Version        |
| ---------------- | ---------- | -------------------- | -------------- |
| Frontend         | TypeScript | React + Vite         | 18.3.1 + 5.4.0 |
| Backend Services | Java       | Spring Boot (Gradle) | 4.0.6          |
| GenAI Service    | Python     | FastAPI              | 3.12           |
| Database         | SQL        | PostgreSQL           | 16             |
| Reverse Proxy    | Go         | Traefik              | 3.6            |

**Linux System Packages:**

```bash
# Ubuntu/Debian
sudo apt-get update
sudo apt-get install -y \
  build-essential \
  curl \
  git \
  openjdk-21-jdk \
  nodejs \
  npm \
  python3.12 \
  python3-pip \
  postgresql-client
```

> **Note:** The project uses the Gradle wrapper (`./gradlew`) — no separate Gradle or Maven install needed.

**Python Dependencies:**

```bash
cd genai
pip install -r requirements.txt
```

**Node.js Dependencies:**

```bash
cd client
npm install
```

**Pre-commit Hooks:**

```bash
python3 -m pip install pre-commit
pre-commit install
pre-commit install --hook-type commit-msg
pre-commit run --all-files
```

This repository includes `.pre-commit-config.yaml` at the project root. Hooks cover:

- repository hygiene: whitespace, end-of-file, YAML syntax, merge conflicts
- formatting for Markdown, YAML, JSON, CSS, HTML, JS/TS via Prettier
- Python formatting for `genai/` via Black and isort

**Gradle & Dependency Management:**

- Uses Gradle wrapper (`./gradlew`) — no pre-installed Gradle required
- Spring Boot dependencies resolved from Maven Central via the Gradle version catalog (`server/gradle/libs.versions.toml`)
- Java toolchain configured to JDK 21

## 1. System Architecture

### Subsystems

- **Traefik** reverse proxy (`config/traefik/`) handles TLS termination, routing, and load balancing for all services.
- Frontend (`client`) renders dashboard and assistant UI.
- Orchestrator service (`server/orchestrator-service`) aggregates data from all backend services and exposes a unified API.
- Account service (`server/account-service`) manages account-level data and trend points.
- Transaction service (`server/transaction-service`) serves transactions and expense analytics.
- Banking service (`server/banking-service`) integrates with Enable Banking (PSD2/Open Banking) to link external bank accounts and sync balances and transactions into the shared schema. See [ENABLE_BANKING_INTEGRATION.md](ENABLE_BANKING_INTEGRATION.md).
- GenAI service (`genai`) provides summary and chat capabilities with local-first fallback.
- PostgreSQL stores persistent account and transaction data.

### Required diagrams

- Subsystem decomposition: `docs/uml/subsystem-decomposition.puml`
- Use case diagram: `docs/uml/use-case.puml`
- Analysis object model: `docs/uml/analysis-object-model.puml`

## 2. Running Locally (Docker Compose)

Prerequisites:

- Docker + Docker Compose plugin

Create or update your local environment file (see the [Environment Variables](#environment-variables) table below for the full list):

```bash
# if .env already exists, just edit it
# otherwise create it
touch .env
```

At minimum you must set `POSTGRES_USER`, `POSTGRES_PASSWORD`, and `APP_HOSTNAME` (the launcher validates these). Optional variables enable GitHub sign-in, Enable Banking (PSD2), and the Logos GenAI gateway.

Start with the team launcher (enforces required env file and variables):

```bash
./scripts/dev-up.sh              # uses ./.env by default
./scripts/dev-up.sh path/to/file.env   # or point at any other env file
```

Windows PowerShell equivalents live at [scripts/dev-up.ps1](scripts/dev-up.ps1) / [scripts/dev-down.ps1](scripts/dev-down.ps1).

Stop the stack:

```bash
./scripts/dev-down.sh
```

> See [Endpoints](#-endpoints) at the top of this README for all app URLs.

### Environment Variables

| Variable                          | Required | Used by            | Purpose / Default                                                                                             |
| --------------------------------- | :------: | ------------------ | ------------------------------------------------------------------------------------------------------------- |
| `POSTGRES_USER`                   |    ✅    | database, services | Postgres role name.                                                                                           |
| `POSTGRES_PASSWORD`               |    ✅    | database, services | Postgres password.                                                                                            |
| `APP_HOSTNAME`                    |    ✅    | orchestrator, client, Traefik | External hostname used for CORS + client `PUBLIC_API_URL` (use `localhost` in dev).                    |
| `TRAEFIK_HTTP_PORT`               |          | Traefik            | Host port for HTTP (default `8088`).                                                                          |
| `TRAEFIK_HTTPS_PORT`              |          | Traefik            | Host port for HTTPS (default `443`).                                                                          |
| `TRAEFIK_DASHBOARD_PORT`          |          | Traefik            | Host port for the dashboard (default `8080`).                                                                 |
| `GITHUB_CLIENT_ID`                |    ⚠️    | orchestrator       | GitHub OAuth App Client ID. Required for login (`/api/auth/github/*`).                                        |
| `GITHUB_CLIENT_SECRET`            |    ⚠️    | orchestrator       | GitHub OAuth App Client Secret.                                                                               |
| `GITHUB_REDIRECT_URI`             |          | orchestrator       | OAuth callback URL. Default `https://localhost/login/oauth2/code/github` — must exactly match the OAuth App.  |
| `ENABLE_BANKING_APP_ID`           |          | banking-service    | Enable Banking (PSD2) application ID.                                                                         |
| `ENABLE_BANKING_PRIVATE_KEY_PATH` |          | banking-service    | Host path to the RSA `.pem` private key mounted into the container.                                           |
| `ENABLE_BANKING_REDIRECT_URL`     |          | banking-service    | Enable Banking PSD2 callback (default `https://localhost/callback`).                                          |
| `MODEL_PROVIDER`                  |          | genai              | `logos` (default in `docker-compose.yml`), `local`, or `ollama`. Dev compose defaults to `local`.             |
| `LOGOS_KEY`                       |    ⚠️    | genai              | Required only when `MODEL_PROVIDER=logos`. TUM AET Logos gateway API key.                                     |
| `LOGOS_BASE_URL`                  |          | genai              | Default `https://logos.aet.cit.tum.de`.                                                                       |
| `LOGOS_MODEL`                     |          | genai              | Default `openai/gpt-oss-120b`.                                                                                |

⚠️ = optional at startup, but the corresponding feature (login, banking sync, Logos LLM) is disabled without it.

## 3. GenAI Model Modes

Configure via `MODEL_PROVIDER` in `.env` / `docker-compose.yml`:

- `local` — canned offline replies, no external calls. Default in `docker-compose.dev.yml`.
- `logos` — TUM AET Logos gateway (OpenAI-compatible). Requires `LOGOS_KEY`. Default in `docker-compose.yml`.
- `ollama` — local LLM via Ollama at `http://host.docker.internal:11434` (model `llama3.1:8b`).

If the upstream is unreachable the service transparently falls back to a canned local reply, so the dashboard keeps working offline.

## 3a. GitHub OAuth Login

The orchestrator exposes a minimal GitHub OAuth flow (see [server/orchestrator-service/src/main/java/com/team/bank/orchestrator/AuthController.java](server/orchestrator-service/src/main/java/com/team/bank/orchestrator/AuthController.java)). No Spring Security — four endpoints and an in-memory session map.

**Endpoints (under `/api/auth`):**

- `GET /github/login` — returns the GitHub authorize URL + state
- `POST /github/callback` — exchanges `{ code, state }` for an opaque bearer token and the app user
- `GET /me` — returns the current `AppUser` for the bearer token
- `POST /logout` — invalidates the token

**Local setup:**

1. Register a new [GitHub OAuth App](https://github.com/settings/developers).
2. Set the **Authorization callback URL** to `https://localhost/login/oauth2/code/github` (or whatever `GITHUB_REDIRECT_URI` you use).
3. Copy the Client ID + Secret into `.env` as `GITHUB_CLIENT_ID` / `GITHUB_CLIENT_SECRET`.
4. Restart the stack — the sign-in gate on the SPA will now redirect through GitHub.

**Production (K8s):** the CD workflow injects the OAuth credentials from the `GH_OAUTH_K8S_CLIENT_ID` / `GH_OAUTH_K8S_CLIENT_SECRET` GitHub repo secrets into the orchestrator deployment. Register `https://<tumid>-devops-ss26.stud.k8s.aet.cit.tum.de/login/oauth2/code/github` as the callback on that OAuth App.

## 4. CI/CD

GitHub Actions workflows live under [.github/workflows/](.github/workflows/):

- **CI** ([ci.yml](.github/workflows/ci.yml)) — runs on every PR and push to `main`:
  - Backend quality gate: `./gradlew test spotlessCheck --parallel` (compilation + tests + Spotless formatting; Error Prone runs at compile time via [server/build.gradle.kts](server/build.gradle.kts))
  - Lints, tests, and builds the React frontend (`npm ci && npm run lint && npm run test && npm run build`)
  - Runs the Python GenAI service test suite (`pytest`)
  - Uses Java 21 (Temurin), Node 22, Python 3.12
- **CD** ([cd.yml](.github/workflows/cd.yml)) — runs on every push and `workflow_dispatch`:
  - Always: `helm lint` + `helm template` dry-run of the chart
  - Always: builds Docker images for all six services (`account-service`, `transaction-service`, `banking-service`, `orchestrator-service`, `genai-service`, `client`)
  - On `main` (or manual dispatch): pushes images to GHCR and runs `helm upgrade --install` against the TUM cluster
  - Required repo secrets: `KUBECONFIG`, `POSTGRES_PASSWORD`, `GRAFANA_ADMIN_PASSWORD`, `EB_APP_ID`, `EB_PRIVATE_KEY`, `GH_OAUTH_K8S_CLIENT_ID`, `GH_OAUTH_K8S_CLIENT_SECRET`, `LOGOS_KEY`
  - Optional repo variable: `GENAI_MODEL_PROVIDER` (defaults to `logos`)
- **Infra** ([infra-plan.yml](.github/workflows/infra-plan.yml), [infra-deploy.yml](.github/workflows/infra-deploy.yml)) — Terraform plan/apply for the bootstrap VM (see [infra/CI-CD.md](infra/CI-CD.md))
- **Docker** ([docker.yaml](.github/workflows/docker.yaml)) — auxiliary image workflow

## 5. Kubernetes Deployment (Helm)

The Helm chart is in `infra/helm/banking-app`.

Deploy manually:

```bash
helm upgrade --install banking-app ./infra/helm/banking-app \
  --namespace devops26 --create-namespace \
  --set tumid=team \
  --set postgres.database.password=<secure-password> \
  --timeout 5m --wait
```

Disable monitoring if not needed:

```bash
helm upgrade --install banking-app ./infra/helm/banking-app \
  --namespace devops26 --create-namespace \
  --set tumid=team \
  --set postgres.database.password=<secure-password> \
  --set monitoring.prometheus.enabled=false \
  --set monitoring.grafana.enabled=false \
  --timeout 5m --wait
```

Kustomize manifests (legacy) are in `infra/k8s/base`.

Images are pulled from GitHub Container Registry (`ghcr.io/aet-devops26/...`).

## 6. Monitoring and Alerting

- Prometheus config: `infra/monitoring/prometheus.yml`
- Grafana dashboard JSON: `infra/monitoring/grafana/dashboards/banking-overview.json`
- Alert rule file: `infra/monitoring/alerts.yml`

Tracked metrics include:

- Request count
- Request latency (P95)
- Error rate (5xx)

## 7. API Documentation

- OpenAPI definition: `server/openapi.yaml`
- Swagger UI (runtime): `https://localhost/swagger-ui/index.html`

## 8. Testing

- Java unit tests in each Spring service under `src/test`
- Python tests in [genai/tests](genai/tests)
- React tests in [client/src/App.test.tsx](client/src/App.test.tsx) and [client/src/api.test.ts](client/src/api.test.ts)

Manual local test commands (without Docker):

```bash
# All Java services (Gradle)
cd server && ./gradlew test

# Individual services
cd server && ./gradlew :account-service:test
cd server && ./gradlew :transaction-service:test
cd server && ./gradlew :banking-service:test
cd server && ./gradlew :orchestrator-service:test

# Quality gate mirrored from CI
cd server && ./gradlew test spotlessCheck --parallel

# Python
cd genai && pip install -r requirements.txt && pytest

# React
cd client && npm install && npm run test
```
