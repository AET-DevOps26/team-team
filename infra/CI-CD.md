# CI/CD for Terraform + Ansible

Two workflows live in `.github/workflows/`:

| File | When it runs | What it does |
|------|--------------|--------------|
| `infra-plan.yml`   | PR touching `infra/**` | `terraform fmt` + `validate` + `plan` (comments plan on PR), `ansible-playbook --syntax-check`, `ansible-lint` |
| `infra-deploy.yml` | Push to `main` touching `infra/**`, `config/**`, or `docker-compose.yml` (also `workflow_dispatch`) | `terraform apply` → captures outputs → `ansible-playbook` against the VM |

Deployments are **incremental** because Terraform state lives in an Azure Storage container (shared backend) and Ansible tasks are idempotent. Re-running the workflow on an unchanged main branch is a no-op.

## One-time setup

### 1. Bootstrap the Terraform state backend

Create a Storage Account + container that will hold `terraform.tfstate` (only needed once per project):

```bash
RG=tfstate-rg
STORAGE=teamteamtfstate$RANDOM
CONTAINER=tfstate

az group create -n $RG -l polandcentral
az storage account create -n $STORAGE -g $RG -l polandcentral --sku Standard_LRS
az storage container create -n $CONTAINER --account-name $STORAGE
echo "RG=$RG STORAGE=$STORAGE CONTAINER=$CONTAINER"
```

### 2. Create an Azure AD app for GitHub OIDC

```bash
SUB=$(az account show --query id -o tsv)
TENANT=$(az account show --query tenantId -o tsv)
APP_ID=$(az ad app create --display-name "team-team-gha" --query appId -o tsv)
az ad sp create --id $APP_ID
az role assignment create --assignee $APP_ID --role Contributor --scope /subscriptions/$SUB

# Federate to GitHub
REPO=<owner>/<repo>
az ad app federated-credential create --id $APP_ID --parameters "{
  \"name\":\"gh-main\",
  \"issuer\":\"https://token.actions.githubusercontent.com\",
  \"subject\":\"repo:$REPO:ref:refs/heads/main\",
  \"audiences\":[\"api://AzureADTokenExchange\"]
}"
az ad app federated-credential create --id $APP_ID --parameters "{
  \"name\":\"gh-pr\",
  \"issuer\":\"https://token.actions.githubusercontent.com\",
  \"subject\":\"repo:$REPO:pull_request\",
  \"audiences\":[\"api://AzureADTokenExchange\"]
}"

echo "AZURE_CLIENT_ID=$APP_ID AZURE_TENANT_ID=$TENANT AZURE_SUBSCRIPTION_ID=$SUB"
```

### 3. Generate an SSH keypair for the VM

```bash
ssh-keygen -t ed25519 -f ./vm-key -N "" -C "team-team-gha"
cat ./vm-key       # → VM_SSH_PRIVATE_KEY
cat ./vm-key.pub   # → VM_SSH_PUBLIC_KEY
```

### 4. Add GitHub secrets

In **Settings → Secrets and variables → Actions**:

| Secret | Value |
|--------|-------|
| `AZURE_CLIENT_ID` | App ID from step 2 |
| `AZURE_TENANT_ID` | Tenant ID from step 2 |
| `AZURE_SUBSCRIPTION_ID` | Subscription ID from step 2 |
| `TF_BACKEND_RG` | RG from step 1 |
| `TF_BACKEND_STORAGE` | Storage account from step 1 |
| `TF_BACKEND_CONTAINER` | Container from step 1 (`tfstate`) |
| `VM_SSH_PUBLIC_KEY` | Contents of `vm-key.pub` |
| `VM_SSH_PRIVATE_KEY` | Contents of `vm-key` (full PEM) |
| `ALLOWED_SSH_CIDR` | CIDR that CI's NSG rule will allow on port 22. Set to `0.0.0.0/0` so the GitHub-hosted runner can SSH in. See [terraform/README.md](terraform/README.md#-manual-ssh-unlock-step-every-time-your-ip-changes) for the manual local-narrowing flow. |

## Local development

When working locally you can keep using the local state backend by commenting out the `backend "azurerm" {}` block in `infra/terraform/providers.tf`, or initialize against the same remote backend with a `backend.hcl` file:

```hcl
# infra/terraform/backend.hcl  (do not commit)
resource_group_name  = "tfstate-rg"
storage_account_name = "teamteamtfstateXXXXX"
container_name       = "tfstate"
key                  = "local.tfstate"
```

Then:

```bash
cd infra/terraform
terraform init -backend-config=backend.hcl
```
