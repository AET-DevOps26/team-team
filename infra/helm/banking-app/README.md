# Banking Application Helm Chart

A Helm chart for deploying the Banking Application to Kubernetes.

## Prerequisites

- Kubernetes cluster
- Helm 3.x installed
- Access to the container registry (`ghcr.io/aet-devops26`)

## Installation

### 1. Set your TUM ID

Replace `tumid` in `values.yaml` with your TUM ID, or pass it via `--set`:

```bash
helm install banking-app ./infra/helm/banking-app \
  --set tumid=<your-tum-id>
```

### 2. Set the PostgreSQL password

Override the default database password:

```bash
helm install banking-app ./infra/helm/banking-app \
  --set tumid=<your-tum-id> \
  --set postgres.database.password=<your-password>
```

## Chart Structure

```
banking-app/
├── Chart.yaml              # Chart metadata
├── values.yaml             # Default configuration values
├── README.md               # This file
├── .helmignore             # Files to ignore when packaging
└── templates/
    ├── namespace.yaml              # (optional) Namespace
    ├── postgres-secret.yaml        # PostgreSQL credentials secret
    ├── postgres-deployment.yaml    # PostgreSQL database
    ├── postgres-service.yaml       # PostgreSQL service
    ├── accountService-deployment.yaml     # Account microservice
    ├── accountService-service.yaml        # Account service
    ├── transactionService-deployment.yaml # Transaction microservice
    ├── transactionService-service.yaml    # Transaction service
    ├── genaiService-deployment.yaml       # GenAI microservice
    ├── genaiService-service.yaml          # GenAI service
    ├── orchestratorService-deployment.yaml # Orchestrator (BFF)
    ├── orchestratorService-service.yaml    # Orchestrator service
    ├── client-deployment.yaml      # Frontend client
    ├── client-service.yaml         # Client service
    ├── configmap.yaml              # Shared configuration
    └── ingress.yaml                # Ingress rules
```

## Services

| Service              | Port | Description                          |
|----------------------|------|--------------------------------------|
| `client`             | 80   | Frontend (Nginx)                     |
| `orchestrator-service` | 8083 | BFF / API Gateway                   |
| `account-service`    | 8081 | Account management microservice      |
| `transaction-service`| 8082 | Transaction management microservice  |
| `genai-service`      | 8000 | GenAI powered analysis service       |
| `postgres`           | 5432 | PostgreSQL database                  |

## Configuration

### Global parameters

| Parameter           | Description                          | Default |
|---------------------|--------------------------------------|---------|
| `tumid`             | Your TUM ID (used in namespace)      | `team` |
| `imagePullPolicy`   | Global image pull policy             | `Always` |

### Service parameters

Each service has the following configurable parameters (using `accountService` as example):

| Parameter                                | Description                  | Default |
|------------------------------------------|------------------------------|---------|
| `accountService.image.repository`        | Image repository             | `ghcr.io/aet-devops26/account-service` |
| `accountService.image.tag`               | Image tag                    | `latest` |
| `accountService.service.type`            | Kubernetes service type      | `ClusterIP` |
| `accountService.service.port`            | Service port                 | `8081` |
| `accountService.service.targetPort`      | Container port               | `8081` |
| `accountService.replicaCount`            | Number of replicas           | `2` |
| `accountService.resources.limits.cpu`    | CPU limit                    | `500m` |
| `accountService.resources.limits.memory` | Memory limit                 | `512Mi` |
| `accountService.resources.requests.cpu`  | CPU request                  | `250m` |
| `accountService.resources.requests.memory`| Memory request              | `256Mi` |

### Database parameters

| Parameter                      | Description                    | Default |
|--------------------------------|--------------------------------|---------|
| `postgres.image.repository`    | PostgreSQL image repository    | `postgres` |
| `postgres.image.tag`           | PostgreSQL image tag           | `16-alpine` |
| `postgres.database.name`       | Database name                  | `bankdb` |
| `postgres.database.user`       | Database user                  | `bank` |
| `postgres.database.password`   | Database password (REQUIRED)   | `change-me-before-deploy` |

### Ingress parameters

| Parameter           | Description                          | Default |
|---------------------|--------------------------------------|---------|
| `ingress.enabled`   | Enable ingress                       | `true` |
| `ingress.className` | Ingress class name                   | `nginx` |
| `ingress.tls`       | Enable TLS with Let's Encrypt        | `true` |

## Uninstalling

```bash
helm uninstall banking-app
```
