#!/usr/bin/env bash
set -euo pipefail

ENV_FILE="${1:-.env.team}"

if [[ ! -f "$ENV_FILE" ]]; then
  echo "Missing env file: $ENV_FILE"
  echo "Create it from .env.team.example and fill team-provided values."
  exit 1
fi

required_vars=(POSTGRES_USER POSTGRES_PASSWORD)
for var in "${required_vars[@]}"; do
  if ! grep -Eq "^${var}=.+$" "$ENV_FILE"; then
    echo "Missing required variable $var in $ENV_FILE"
    exit 1
  fi
done

echo "Using env file: $ENV_FILE"
docker compose --env-file "$ENV_FILE" up -d --build
