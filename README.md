# Multi-Agent Banking System (DevOps Course Project)

This repository contains a full mono-repo banking web application with:

- `client`: React + TypeScript frontend
- `server`: Java Spring Boot microservices (3 services)
- `genai`: Python-based GenAI microservice
- `infra`: Docker Compose, Kubernetes manifests, monitoring stack

## 1. System Architecture

### Subsystems

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

Run in 2 commands:

```bash
docker compose build
docker compose up
```

App endpoints:

- Frontend: `http://localhost:3000`
- Orchestrator API: `http://localhost:8083`
- Prometheus: `http://localhost:9090`
- Grafana: `http://localhost:3001` (admin/admin)

## 3. GenAI Model Modes (No Cloud Dependency)

Configure in `docker-compose.yml` through env vars:

- `MODEL_PROVIDER=local` (default, no cloud dependency)
- `MODEL_PROVIDER=ollama` for local LLM via Ollama

## 4. CI/CD

GitHub Actions workflows:

- CI: `.github/workflows/ci.yml`
	- builds and tests all services (Java, Python, React)
- CD: `.github/workflows/cd.yml`
	- deploys Kubernetes manifests on push to `main`
	- expects `KUBECONFIG` in GitHub secrets

## 5. Kubernetes Deployment

Kustomize manifests are in `infra/k8s/base`.

Deploy:

```bash
kubectl apply -k infra/k8s/base
```

Replace placeholder image names (`ghcr.io/your-org/...`) with your own registry images.

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
cd server/account-service && mvn test
cd server/transaction-service && mvn test
cd server/orchestrator-service && mvn test
cd genai && pip install -r requirements.txt && pytest
cd client && npm install && npm run test
```

## 9. Suggested Team Responsibility Split

- Student A: Client primary ownership (`client`)
- Student B: Server primary ownership (`server`)
- Student C: GenAI primary ownership (`genai`)

All students should collaborate on integration, CI/CD, deployment, and monitoring.
