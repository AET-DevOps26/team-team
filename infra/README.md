# Azure deployment (Terraform + Ansible)

Provisions a single Ubuntu 24.04 VM in **Poland Central** and deploys the full
[docker-compose.yml](../docker-compose.yml) stack onto it. For the CI/CD wiring
around this (remote Terraform state, GitHub OIDC, workflows) see
[CI-CD.md](CI-CD.md).

## Layout

```
infra/
├── terraform/         # Resource group, VNet, NSG, public IP, Linux VM
├── ansible/           # Installs Docker, syncs the repo, runs docker compose
├── deploy.sh          # One-shot: terraform apply + ansible-playbook
└── destroy.sh         # terraform destroy
```

## Prerequisites

On your workstation (WSL / Linux / macOS — Ansible is not first-class on Windows):

| Tool       | Min version | Install                                           |
| ---------- | ----------- | ------------------------------------------------- |
| Azure CLI  | 2.60        | `az --version`                                    |
| Terraform  | 1.6         | https://developer.hashicorp.com/terraform/install |
| Ansible    | 2.16        | `pipx install ansible` or `apt install ansible`   |
| rsync, ssh | any         | usually preinstalled                              |

Login to Azure:

```bash
az login
az account set --subscription "<YOUR_SUBSCRIPTION_ID>"
```

> **Terraform state backend:** [providers.tf](terraform/providers.tf) declares an
> empty `backend "azurerm" {}` block. For **local-only** runs comment that block
> out (falls back to a local `terraform.tfstate`) or pass `-backend-config=...`
> flags — see [CI-CD.md](CI-CD.md#local-development). CI supplies the backend
> config from repo secrets.

## VM sizing

Default: **`Standard_B4s_v2`** — 4 vCPU / 16 GB RAM (Intel x64, burstable).
Comfortably runs Postgres + **4 Spring Boot JVMs** (`account-service`,
`transaction-service`, `banking-service`, `orchestrator-service`) + FastAPI
(GenAI) + Nginx (client) + Traefik + Prometheus + Alertmanager + Grafana.
Change via `vm_size` in `terraform.tfvars`.

## Quick start

```bash
cd infra/terraform
cp terraform.tfvars.example terraform.tfvars
# REQUIRED: set allowed_ssh_cidr to your IP (e.g. "$(curl -s ifconfig.me)/32").
# The variable is validated — "*" and "0.0.0.0/0" are rejected.
cd ..
./deploy.sh
```

What happens:

1. Terraform creates RG, VNet, subnet, NSG (22 restricted to
   `allowed_ssh_cidr`; 80/443 open to the world), static public IP with an
   auto-generated `*.polandcentral.cloudapp.azure.com` FQDN, NIC, and an
   Ubuntu 24.04 LTS VM. If you don't supply an SSH key one is generated into
   [terraform/ssh/](terraform/ssh/).
2. Terraform renders [ansible/inventory.ini](ansible/inventory.ini) with the
   VM's public IP + FQDN.
3. Ansible ([playbook.yml](ansible/playbook.yml)):
   - installs Docker Engine + Compose plugin from the official Docker repo
   - `rsync`s the repo to `/opt/team-team` on the VM (excluding `.git`,
     `.github`, `node_modules`, Gradle/Terraform state, `letsencrypt`, `.env`)
   - writes the Enable Banking `.pem` (only when `eb_private_key_pem` is passed
     via `--extra-vars`) into `secrets/eb_private_key.pem`
   - renders `.env` from `app_env` in [group_vars/all.yml](ansible/group_vars/all.yml)
   - builds the React bundle inside a throwaway `node:22-alpine` container so
     [client/Dockerfile](../client/Dockerfile) can `COPY dist/`
   - runs `docker compose --env-file .env up -d --build --remove-orphans`
   - waits for Postgres, then applies the idempotent SQL migrations
     [migrate-multibank.sql](docker/migrate-multibank.sql) and
     [migrate-users.sql](docker/migrate-users.sql) (needed because the
     Postgres data volume is persistent, so `init.sql` only runs on the very
     first deploy)

After it finishes, open `https://<fqdn>/` in a browser. Traefik terminates TLS
via a Let's Encrypt HTTP-01 cert (issued on the first request; ~30 s).

## Configuration knobs

### Terraform ([infra/terraform/terraform.tfvars](terraform/terraform.tfvars.example))

| Variable               | Default              | Notes                                                                             |
| ---------------------- | -------------------- | --------------------------------------------------------------------------------- |
| `project_name`         | `team-team`          | Used as a prefix for every resource                                               |
| `environment`          | `dev`                | Tag + name suffix                                                                 |
| `location`             | `polandcentral`      | Azure region                                                                      |
| `vm_size`              | `Standard_B4s_v2`    | See SKU list                                                                      |
| `admin_username`       | `azureuser`          | Linux user                                                                        |
| `ssh_public_key_path`  | `""` (auto-generate) | Or point at `~/.ssh/id_rsa.pub`                                                   |
| `ssh_private_key_path` | `""` (derive)        | Only needed when your key pair doesn't follow `<name>` / `<name>.pub` convention  |
| `allowed_ssh_cidr`     | **required**         | Explicit CIDR (e.g. `"1.2.3.4/32"`). Wildcards are rejected by validation.        |
| `os_disk_size_gb`      | `64`                 | StandardSSD_LRS                                                                   |
| `tags`                 | `{project, managed}` | Applied to every resource; `environment` is merged in automatically               |

### Ansible ([infra/ansible/group_vars/all.yml](ansible/group_vars/all.yml))

`app_env` controls the contents of `.env` rendered on the VM. Defaults today:

```yaml
app_env:
  POSTGRES_USER: bank
  POSTGRES_PASSWORD: admin-bankpass          # CHANGE for anything beyond a demo
  APP_HOSTNAME: "{{ app_fqdn | default(app_public_ip) }}"
  TRAEFIK_HTTP_PORT: "80"
  TRAEFIK_HTTPS_PORT: "443"
  TRAEFIK_DASHBOARD_PORT: "8080"

  # Enable Banking (PSD2) — banking-service. Falls back to /dev/null when
  # eb_private_key_pem is empty so compose still starts.
  ENABLE_BANKING_APP_ID: "{{ eb_app_id | default('') }}"
  ENABLE_BANKING_PRIVATE_KEY_PATH: "…/secrets/eb_private_key.pem or /dev/null"
  ENABLE_BANKING_REDIRECT_URL: "{{ eb_redirect_url | default('https://localhost/callback') }}"

  # GenAI (Logos). MODEL_PROVIDER auto-flips to 'local' when logos_key is empty.
  MODEL_PROVIDER: "{{ 'logos' if logos_key else 'local' }}"
  LOGOS_KEY: "{{ logos_key | default('') }}"
  LOGOS_BASE_URL: "{{ logos_base_url | default('https://logos.aet.cit.tum.de') }}"
  LOGOS_MODEL: "{{ logos_model | default('openai/gpt-oss-120b') }}"

  # GitHub OAuth sign-in for the app itself (see AuthController).
  # GitHub secret names can't start with GITHUB_, so we use GH_OAUTH_* in CI.
  GITHUB_CLIENT_ID: "{{ github_oauth_client_id | default('') }}"
  GITHUB_CLIENT_SECRET: "{{ github_oauth_client_secret | default('') }}"
  GITHUB_REDIRECT_URI: "{{ github_oauth_redirect_uri | default('https://<fqdn>/login/oauth2/code/github') }}"
```

Every optional feature is driven by a top-level Ansible variable that you pass
in via `--extra-vars` (or a vault file). None of them are required to bring the
stack up — missing values just disable the corresponding feature.

| Extra-var                     | Enables                                    |
| ----------------------------- | ------------------------------------------ |
| `eb_app_id`                   | Enable Banking API calls                   |
| `eb_private_key_pem`          | Signing JWTs for Enable Banking            |
| `eb_redirect_url`             | Enable Banking OAuth callback URL          |
| `logos_key`                   | GenAI via TUM Logos (else canned replies)  |
| `github_oauth_client_id`      | GitHub OAuth login                         |
| `github_oauth_client_secret`  | GitHub OAuth login                         |
| `github_oauth_redirect_uri`   | Overrides the default `https://<fqdn>/…` callback |
| `acme_email`                  | Contact address for Let's Encrypt (edit `all.yml` directly) |

Override at runtime without editing files:

```bash
cd infra/ansible
ansible-playbook playbook.yml \
  -e "logos_key=lg-xxx" \
  -e "github_oauth_client_id=Ov23…" \
  -e "github_oauth_client_secret=…" \
  -e "eb_app_id=…" \
  -e "eb_private_key_pem=$(cat ~/eb_private.pem)"
```

Or point at a vault file:

```bash
ansible-playbook playbook.yml -e @secrets.yml --ask-vault-pass
```

## Re-deploying app changes

After editing source code locally, just re-run Ansible — it will rsync the
changes, rebuild the client bundle, and rebuild the affected containers:

```bash
cd infra/ansible
ansible-playbook playbook.yml
```

(No Terraform run needed; the VM stays put.)

## SSH into the VM

```bash
terraform -chdir=infra/terraform output -raw ssh_command | bash
# or
ssh -i infra/terraform/ssh/id_rsa azureuser@<public-ip>
```

## Tear down

```bash
./infra/destroy.sh
```

Removes the entire resource group and everything in it. The remote Terraform
state (if you're using the azurerm backend) is kept.

## Notes & gotchas

- **HTTPS / Let's Encrypt:** Works out of the box against the Azure-assigned
  `*.cloudapp.azure.com` FQDN. Traefik requests a cert via HTTP-01 on first
  start (~30 s) and stores it in `/opt/team-team/letsencrypt/acme.json` on the
  VM. To use a custom domain, point a DNS A-record at the public IP and pass
  `-e "app_fqdn=my.domain.com"` when running the playbook. Change the ACME
  contact address by editing `acme_email` in `group_vars/all.yml`.
- **GitHub OAuth callback:** the default `GITHUB_REDIRECT_URI` is
  `https://<fqdn>/login/oauth2/code/github`. Register that exact URL on the
  GitHub OAuth App whose credentials you pass via `github_oauth_client_id` /
  `github_oauth_client_secret`, otherwise the login flow will error with
  `redirect_uri_mismatch`.
- **DB migrations:** the playbook runs `migrate-multibank.sql` and
  `migrate-users.sql` on every deploy (both are idempotent). If you add a new
  schema change, drop it in [docker/](docker/) and add another task to
  [playbook.yml](ansible/playbook.yml) — don't rely on `init.sql`, which only
  runs on a fresh Postgres data volume.
- **Secrets:** Don't commit `terraform.tfvars`, `.env`, `secrets/*.pem`, or
  production passwords. Use Ansible Vault or `--extra-vars` for real
  credentials. `terraform/ssh/`, `terraform.tfstate*`, `.env`, and
  `secrets/` are already git-ignored.
- **Line endings:** This project has `.gitattributes` enforcing LF on shell
  scripts and `gradlew` so Docker builds on the Linux VM don't break.
