#!/usr/bin/env bash
# Destroy the Azure infrastructure provisioned by deploy.sh.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TF_DIR="$SCRIPT_DIR/terraform"

terraform -chdir="$TF_DIR" destroy -auto-approve
