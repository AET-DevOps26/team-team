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
cd server && ./gradlew :orchestrator-service:test

# GenAI service
cd ../genai && pip install -r requirements.txt && pytest

# Frontend
cd ../client && npm install && npm run test && npm run build
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

1. Create your local team env file:
```bash
cp .env.team.example .env.team
```
2. Fill `.env.team` using values provided by maintainers.
3. Start the stack:
```bash
./scripts/dev-up.sh
```
4. Stop the stack:
```bash
./scripts/dev-down.sh
```

Notes:
- `.env.team` is ignored by git and must never be committed.
- `./scripts/dev-up.sh` validates required variables before startup.
- If you need a different env file path, both scripts accept one argument:
```bash
./scripts/dev-up.sh path/to/file.env
./scripts/dev-down.sh path/to/file.env
```
