# Multi-Agent Banking System

> For the full problem statement and system overview (architecture, diagrams, backlog), see [docs/problem_statement+system_overview.md](docs/problem_statement+system_overview.md).

This repository contains a full mono-repo banking web application with:

- `client`: React + TypeScript frontend
- `server`: Java Spring Boot microservices (3 services, Gradle-built)
- `genai`: Python-based GenAI microservice
- `infra`: Docker Compose, Traefik reverse proxy, Kubernetes manifests, monitoring stack

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

| Component | Language | Framework | Version |
|-----------|----------|-----------|---------|
| Frontend | TypeScript | React + Vite | 18.3.1 + 5.4.0 |
| Backend Services | Java | Spring Boot (Gradle) | 4.0.6 |
| GenAI Service | Python | FastAPI | 3.12 |
| Database | SQL | PostgreSQL | 16 |
| Reverse Proxy | Go | Traefik | 3.6 |

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

**Gradle & Dependency Management:**
- Uses Gradle wrapper (`gradlew`) — no pre-installed Gradle required
- Spring Boot dependencies resolved from Maven Central via the Gradle version catalog (`server/gradle/libs.versions.toml`)
- Java toolchain configured to JDK 21

## 1. System Architecture

### Subsystems

- **Traefik** reverse proxy (`config/traefik/`) handles TLS termination, routing, and load balancing for all services.
- Frontend (`client`) renders dashboard and assistant UI.
- Orchestrator service (`server/orchestrator-service`) aggregates data from all backend services and exposes a unified API.
- Account service (`server/account-service`) manages account-level data and trend points.
- Transaction service (`server/transaction-service`) serves transactions and expense analytics.
- GenAI service (`genai`) provides summary and chat capabilities with local-first fallback.
- PostgreSQL stores persistent account and transaction data.

### Required diagrams

- Subsystem decomposition: `docs/uml/subsystem-decomposition.puml`
- Use case diagram: `docs/uml/use-case.puml`
- Analysis object model: `docs/uml/analysis-object-model.puml`

## 2. Running Locally (Docker Compose)

Prerequisites:

- Docker + Docker Compose plugin

Create your team-local environment file for database credentials:

```bash
cp .env.team.example .env.team
```

Start with the team launcher (enforces required env file and variables):

```bash
./scripts/dev-up.sh
```

Optional: pass a custom env file path.

```bash
./scripts/dev-up.sh .env
```

App endpoints (routed through Traefik):

- Frontend: `https://<APP_HOSTNAME>` (or `http://localhost:3000` directly)
- Orchestrator API: `https://<APP_HOSTNAME>/api` (or `http://localhost:8083` directly)
- Traefik Dashboard: `http://localhost:8080`
- Prometheus: `http://localhost:9090`
- Grafana: `https://<APP_HOSTNAME>/grafana` (or `http://localhost:3001`, admin/admin)

## 3. GenAI Model Modes (No Cloud Dependency)

Configure in `docker-compose.yml` through env vars:

- `MODEL_PROVIDER=local` (default, no cloud dependency)
- `MODEL_PROVIDER=ollama` for local LLM via Ollama

## 4. CI/CD

GitHub Actions workflows:

- **CI** (`.github/workflows/ci.yml`):
	- Builds & tests all services with Gradle (`./gradlew clean test`), Python (`pytest`), and React (`npm test`)
	- Runs OWASP Dependency Check for vulnerability scanning
	- Uses Java 21 (Temurin), Node 22, Python 3.12
	- Uploads test reports and OWASP security reports as artifacts
- **CD** (`.github/workflows/cd.yml`):
	- Deploys Kubernetes manifests via `kubectl apply -k infra/k8s/base` on push to `main`
	- Expects `KUBECONFIG` in GitHub secrets

## 5. Kubernetes Deployment

Kustomize manifests are in `infra/k8s/base`.

Deploy:

```bash
kubectl apply -k infra/k8s/base
```

Use your GitHub Container Registry image names (for example: `ghcr.io/aet-devops26/...`).

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
- Swagger UI (runtime): `http://localhost:8083/swagger-ui/index.html`

## 8. Testing

- Java unit tests in each Spring service under `src/test`
- Python tests in `genai/tests`
- React tests in `client/src/App.test.tsx`

Manual local test commands (without Docker):

```bash
# All Java services (Gradle)
cd server && ./gradlew test

# Individual services
cd server && ./gradlew :account-service:test
cd server && ./gradlew :transaction-service:test
cd server && ./gradlew :orchestrator-service:test

# Python
cd genai && pip install -r requirements.txt && pytest

# React
cd client && npm install && npm run test
```
