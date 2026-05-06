#!/usr/bin/env bash
set -euo pipefail

ENV_FILE="${1:-.env.team}"

if [[ ! -f "$ENV_FILE" ]]; then
  echo "Missing env file: $ENV_FILE"
  echo "Create it from .env.team.example and fill team-provided values."
  exit 1
fi

echo "Using env file: $ENV_FILE"
docker compose --env-file "$ENV_FILE" down
