# Contributing Guide

## 1. Core Rule: Issue Required for Every PR

Every Pull Request (PR) must be linked to an existing issue.

- No issue -> no PR review.
- Include the issue reference in the PR description using one of:
  - `Closes #123`
  - `Fixes #123`
  - `Related to #123`

Before coding:

1. Search existing issues.
2. If no issue exists, create one.
3. Wait for maintainer confirmation if the change is large.

## 2. Branch Naming Convention

Create branches from `main` using this format:

`<type>/<issue-number>-<short-kebab-description>`

Examples:

- `feat/142-dashboard-expense-chart`
- `fix/188-docker-gradle-build-resolution`
- `chore/205-update-readme-requirements`
- `docs/211-add-contributing-guide`

Allowed `type` values:

- `feat` for new features
- `fix` for bug fixes
- `docs` for documentation only
- `test` for test-only changes
- `refactor` for code structure changes without behavior changes
- `chore` for maintenance, tooling, dependency updates
- `ci` for pipeline/workflow changes

## 3. Commit Message Convention

Use clear, meaningful commit messages.

Preferred format (Conventional Commits):

`<type>(<scope>): <short summary> (#<issue-number>)`

Examples:

- `feat(client): add loading state to dashboard cards (#142)`
- `fix(orchestrator): handle timeout calling genai service (#188)`
- `docs(readme): add linux local setup requirements (#205)`

Commit message rules:

- Use imperative mood ("add", "fix", "update", not "added", "fixed").
- Keep summary <= 72 characters when possible.
- One logical change per commit.
- Avoid vague messages like `update`, `changes`, `fix stuff`.

## 4. Pull Request Expectations

PR title format:

`<type>: <short description> (#<issue-number>)`

Examples:

- `fix: resolve docker compose Gradle build error (#188)`
- `feat: add spending trend sparkline to client dashboard (#142)`

PR description should include:

- What changed
- Why it changed
- Linked issue (`Closes #...`)
- How it was tested
- Screenshots or API samples for UI/API changes

Keep PRs focused and small.

- Prefer under ~400 lines changed when possible.
- Split large work into smaller PRs.

## 5. Code Quality and Testing

Before opening a PR, run relevant tests locally:

```bash
# Java services (Gradle)
cd server && ./gradlew test

# Individual services
cd server && ./gradlew :account-service:test
cd server && ./gradlew :transaction-service:test
cd server && ./gradlew :banking-service:test
cd server && ./gradlew :orchestrator-service:test

# Full CI-equivalent quality gate (compile + tests + Spotless + Error Prone + Detekt)
cd server && ./gradlew test spotlessCheck --parallel

# GenAI service
cd ../genai && pip install -r requirements.txt && pytest

# Frontend
cd ../client && npm install && npm run lint && npm run test && npm run build
```

Also verify Docker build still works:

```bash
docker compose build
```

## 6. Style and Scope Rules

- Follow existing project structure and naming patterns.
- Do not include unrelated refactors in feature/bugfix PRs.
- Update docs when behavior, API, or setup changes.
- Add or update tests for behavior changes.

## 7. Review and Merge Process

- At least one maintainer approval is required.
- Address review comments with follow-up commits.
- Keep discussion in the PR (not private messages).
- Do not force-push after review starts unless requested.

## 8. Security and Secrets

- Never commit secrets, tokens, passwords, or kubeconfigs.
- Use environment variables and secret managers.
- If you accidentally commit a secret, rotate it immediately and notify maintainers.

## 9. Team Local Environment Workflow

Use the team scripts so everyone runs Compose the same way with an explicit env file.

1. Create your local env file at the repo root (git-ignored):

```bash
touch .env
```

2. Fill `.env` with the values described in the [Environment Variables](README.md#environment-variables) section of the README. At minimum you need `POSTGRES_USER`, `POSTGRES_PASSWORD`, and `APP_HOSTNAME`. Ask a maintainer for shared credentials (GitHub OAuth App, Enable Banking `.pem`, Logos key) if you need to exercise the full feature set.
3. Start the stack:

```bash
./scripts/dev-up.sh          # Linux/macOS
./scripts/dev-up.ps1         # Windows PowerShell
```

4. Stop the stack:

```bash
./scripts/dev-down.sh        # Linux/macOS
./scripts/dev-down.ps1       # Windows PowerShell
```

Notes:

- `.env` (and `.env.team`) are ignored by git and must never be committed.
- `./scripts/dev-up.sh` validates that `POSTGRES_USER`, `POSTGRES_PASSWORD`, and `APP_HOSTNAME` are present before startup.
- Both scripts accept an alternate env-file path as their first argument:

```bash
./scripts/dev-up.sh path/to/file.env
./scripts/dev-down.sh path/to/file.env
```

- Never commit real secrets. `GITHUB_CLIENT_SECRET`, `LOGOS_KEY`, `ENABLE_BANKING_APP_ID`, and the Enable Banking `.pem` file are considered sensitive.

## 10. Local Service Endpoints

After `./scripts/dev-up.sh` finishes, use these URLs:

- Frontend app: `https://localhost/`
- Backend API index: `https://localhost/api`
- Backend health: `https://localhost/api/health`
- GitHub sign-in start: `https://localhost/api/auth/github/login`
- GitHub OAuth callback (SPA route): `https://localhost/login/oauth2/code/github`
- Banking (PSD2) API: `https://localhost/api/banking/*`
- Swagger UI: `https://localhost/swagger-ui/index.html`
- OpenAPI JSON: `https://localhost/v3/api-docs`
- Grafana: `https://localhost/grafana/` (default login: `admin` / `admin1`)
- Traefik dashboard: `http://localhost:8080/dashboard/`
- Traefik API (raw data): `http://localhost:8080/api/rawdata`

Service visibility notes:

- Prometheus and Alertmanager are internal-only by default (not published on host ports).
- Postgres is internal-only by default (container name `database`, port `5432`).
- Internal service names reachable from containers on Compose networks:
  - `http://orchestrator-service:8083`
  - `http://account-service:8081`
  - `http://transaction-service:8082`
  - `http://banking-service:8084`
  - `http://genai-service:8000`

Internal checks from your terminal (without opening host ports):

- Prometheus health:

```bash
docker compose --env-file .env exec prometheus wget -qO- http://localhost:9090/-/healthy
```

- Postgres shell:

```bash
docker compose --env-file .env exec database psql -U bank -d bankdb
```
