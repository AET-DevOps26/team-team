# Terraform — Azure infrastructure for `team-team`

Provisions the Resource Group, VNet/Subnet/NIC, Public IP + FQDN, Network Security Group and Ubuntu 24.04 VM that host the docker-compose stack.

State lives in a remote Azure Storage backend (see [infra/CI-CD.md](../CI-CD.md)), so every `terraform apply` — local or from CI — is incremental.

## Files

| File | Purpose |
|------|---------|
| `providers.tf`           | Provider versions, remote state backend |
| `variables.tf`           | Input variables + validation |
| `main.tf`                | All Azure resources + generated Ansible inventory |
| `outputs.tf`             | `public_ip`, `fqdn`, `ssh_command`, `resource_group` |
| `terraform.tfvars`       | Local values (gitignored) |
| `terraform.tfvars.example` | Template |
| `templates/inventory.tmpl` | Ansible inventory rendered after `apply` |

## Required variables

`allowed_ssh_cidr` is **required** and validated — Terraform refuses `"*"` or `"0.0.0.0/0"` to prevent SSH from being world-open. Find your IP with `curl https://ifconfig.me` and append `/32`.

```hcl
# terraform.tfvars
allowed_ssh_cidr = "203.0.113.4/32"
```

## Quick start (local)

```bash
cd infra/terraform
cp terraform.tfvars.example terraform.tfvars
# edit terraform.tfvars: set allowed_ssh_cidr to your /32

terraform init \
  -backend-config="resource_group_name=tfstate-rg" \
  -backend-config="storage_account_name=teamteamtfstateXXXXX" \
  -backend-config="container_name=tfstate" \
  -backend-config="key=main.tfstate"

terraform plan
terraform apply
```

After apply:

```bash
terraform output ssh_command   # ready-to-paste ssh command
terraform output fqdn          # https://<fqdn>/
```

## ⚠️ Manual SSH unlock step (every time your IP changes)

CI uses GitHub-hosted runners with random public IPs, so the `ALLOWED_SSH_CIDR` GitHub secret is intentionally set to a wide range (e.g. `0.0.0.0/0`) **only for CI runs**. Locally you should keep your own `terraform.tfvars` pinned to your `/32`.

Whenever your public IP changes (new café, VPN toggled, home ISP rotation), do this:

```bash
# 1. Find your current public IP
curl https://ifconfig.me

# 2. Update terraform.tfvars
#    allowed_ssh_cidr = "NEW.IP.HERE/32"

# 3. Apply — only the NSG rule changes, no VM rebuild
cd infra/terraform
terraform apply

# 4. SSH
ssh -i ~/.ssh/team-team-azure azureuser@$(terraform output -raw public_ip)
```

Alternatively, skip Terraform and patch the NSG rule directly with the Azure CLI (faster, no state churn):

```bash
RG=$(terraform output -raw resource_group)
NSG=$(az network nsg list -g "$RG" --query "[0].name" -o tsv)
MYIP=$(curl -s https://ifconfig.me)

az network nsg rule update \
  -g "$RG" --nsg-name "$NSG" -n "allow-ssh" \
  --source-address-prefixes "${MYIP}/32"
```

> **Note:** if you push to `main`, the CI `terraform apply` will overwrite the NSG rule back to whatever `ALLOWED_SSH_CIDR` is set to in GitHub secrets. Re-run the unlock step after every deploy if you need SSH from a narrower CIDR than CI uses.

## Useful commands

```bash
terraform output                  # all outputs
terraform state list              # what Terraform manages
terraform plan -refresh-only      # detect drift without changing anything
terraform destroy                 # tear it all down
```

## Troubleshooting

- **`Error: allowed_ssh_cidr must be an explicit CIDR`** — you supplied `"*"` or left it unset. Set it in `terraform.tfvars`.
- **SKU not available / `SkuNotAvailable`** — region/SKU combination has no capacity. Try a different `vm_size` (`Standard_B4s_v2`, `Standard_D4s_v5`) or `location`.
- **Policy denied** — your subscription denies the chosen region/SKU. Switch to `polandcentral` (default) or another allowed region.
