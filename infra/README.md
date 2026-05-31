# Azure deployment (Terraform + Ansible)

Provisions a single Ubuntu 24.04 VM in **Sweden Central** and deploys the full
`docker-compose.yml` stack onto it.

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

| Tool        | Min version | Install |
|-------------|-------------|---------|
| Azure CLI   | 2.60        | `az --version` |
| Terraform   | 1.6         | https://developer.hashicorp.com/terraform/install |
| Ansible     | 2.16        | `pipx install ansible` or `apt install ansible` |
| rsync, ssh  | any         | usually preinstalled |

Login to Azure:

```bash
az login
az account set --subscription "<YOUR_SUBSCRIPTION_ID>"
```

## VM sizing

Default: **`Standard_B4s_v2`** — 4 vCPU / 16 GB RAM (Intel x64, burstable).
Comfortably runs Postgres + 3 Spring Boot JVMs + FastAPI + Vite/Nginx +
Traefik + Prometheus + Grafana. Change via `vm_size` in `terraform.tfvars`.

## Quick start

```bash
cd infra/terraform
cp terraform.tfvars.example terraform.tfvars
# (optional) edit terraform.tfvars — at minimum set allowed_ssh_cidr to your IP
cd ..
./deploy.sh
```

What happens:

1. Terraform creates RG, VNet, subnet, NSG (22/80/443 open), static public IP,
   NIC, Linux VM. If you don't supply an SSH key, a fresh one is generated
   into `terraform/ssh/`.
2. Terraform renders `ansible/inventory.ini` with the VM's public IP.
3. Ansible:
   - installs Docker Engine + Compose plugin from the official Docker repo
   - `rsync`s the whole repo to `/opt/team-team` on the VM
   - renders `.env` (values come from `ansible/group_vars/all.yml`)
   - runs `docker compose up -d --build`

After it finishes, open `http://<public-ip>/` in a browser.

## Configuration knobs

### Terraform (`infra/terraform/terraform.tfvars`)

| Variable              | Default               | Notes |
|-----------------------|-----------------------|-------|
| `project_name`        | `team-team`           | Used as a prefix for every resource |
| `environment`         | `dev`                 | Tag + name suffix |
| `location`            | `polandcentral`       | Azure region |
| `vm_size`             | `Standard_B4s_v2`     | See SKU list |
| `admin_username`      | `azureuser`           | Linux user |
| `ssh_public_key_path` | `""` (auto-generate)  | Or point at `~/.ssh/id_rsa.pub` |
| `allowed_ssh_cidr`    | `*`                   | Lock to `your.ip/32` |
| `os_disk_size_gb`     | `64`                  | StandardSSD_LRS |

### Ansible (`infra/ansible/group_vars/all.yml`)

`app_env` controls the contents of `.env` rendered on the VM. By default:

```yaml
app_env:
  POSTGRES_USER: bank
  POSTGRES_PASSWORD: bank        # CHANGE for anything beyond a demo
  APP_HOSTNAME: "<auto from terraform>"
```

Override at runtime without editing files:

```bash
ansible-playbook playbook.yml \
  -e 'app_env={"POSTGRES_USER":"bank","POSTGRES_PASSWORD":"s3cret","APP_HOSTNAME":"my.domain.com"}'
```

## Re-deploying app changes

After editing source code locally, just re-run Ansible — it will rsync the
changes and rebuild the affected containers:

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

Removes the entire resource group and everything in it.

## Notes & gotchas

- **HTTPS / Let's Encrypt:** Works out of the box against the Azure-assigned
  `*.cloudapp.azure.com` FQDN. Traefik requests a cert via HTTP-01 on first
  start (~30 s) and stores it in `/opt/team-team/letsencrypt/acme.json` on the
  VM. To use a custom domain, point a DNS A-record at the public IP and
  override `app_env.APP_HOSTNAME` in `group_vars/all.yml`. Change the ACME
  contact address by editing `acme_email` in the same file.
- **Secrets:** Don't commit `terraform.tfvars` or production passwords.
  Use Ansible Vault or `--extra-vars` for real credentials.
- **Line endings:** This project has `.gitattributes` enforcing LF on shell
  scripts and `gradlew` so Docker builds on the Linux VM don't break.
