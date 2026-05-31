#!/usr/bin/env bash
# Provision Azure infra with Terraform, then deploy the stack with Ansible.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TF_DIR="$SCRIPT_DIR/terraform"
ANSIBLE_DIR="$SCRIPT_DIR/ansible"

echo "==> Terraform init"
terraform -chdir="$TF_DIR" init -upgrade

echo "==> Terraform plan"
terraform -chdir="$TF_DIR" plan -out=tfplan

echo "==> Terraform apply"
terraform -chdir="$TF_DIR" apply -auto-approve tfplan
rm -f "$TF_DIR/tfplan"

echo "==> Installing Ansible collections"
ansible-galaxy collection install -r "$ANSIBLE_DIR/requirements.yml"

echo "==> Waiting 30s for VM cloud-init to settle"
sleep 30

echo "==> Running Ansible playbook"
cd "$ANSIBLE_DIR"
ansible-playbook playbook.yml

echo ""
echo "==> Done. Outputs:"
terraform -chdir="$TF_DIR" output
