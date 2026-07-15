<#
.SYNOPSIS
  Bring the dev stack up on Windows / PowerShell.

.DESCRIPTION
  PowerShell port of scripts/dev-up.sh. Validates the env file, then runs
  `docker compose --env-file <envfile> up -d --build`. Meant to be run from
  the repo root so relative paths in docker-compose.yml resolve correctly.

.PARAMETER EnvFile
  Path to the compose env file. Defaults to .env in the current directory.

.PARAMETER Sequential
  Build service images one at a time instead of in parallel. Useful when Docker
  Desktop's memory limit is low — the default parallel BuildKit run can OOM-kill
  npm/gradle mid-build and surface as npm's "Exit handler never called!" error.

.EXAMPLE
  .\scripts\dev-up.ps1

.EXAMPLE
  .\scripts\dev-up.ps1 -Sequential

.EXAMPLE
  .\scripts\dev-up.ps1 -EnvFile .env.local
#>
[CmdletBinding()]
param(
    [string]$EnvFile = ".env",
    [switch]$Sequential,
    [switch]$SeedDemo
)

$ErrorActionPreference = "Stop"
# Prevent PowerShell 7.3+ from turning native-command stderr output into
# terminating errors. Docker BuildKit writes progress bars to stderr while
# still exiting with code 0, and without this the script would abort mid-build.
if (Get-Variable -Name PSNativeCommandUseErrorActionPreference -ErrorAction SilentlyContinue) {
    $PSNativeCommandUseErrorActionPreference = $false
}

if (-not (Test-Path -LiteralPath $EnvFile -PathType Leaf)) {
    Write-Error "Missing env file: $EnvFile`nCreate it and define POSTGRES_USER, POSTGRES_PASSWORD, and APP_HOSTNAME."
    exit 1
}

# docker compose only substitutes VAR=<non-empty> lines; enforce the same here.
$requiredVars = @("POSTGRES_USER", "POSTGRES_PASSWORD", "APP_HOSTNAME")
$envContent = Get-Content -LiteralPath $EnvFile
foreach ($var in $requiredVars) {
    if (-not ($envContent | Select-String -Pattern "^$([regex]::Escape($var))=.+$" -Quiet)) {
        Write-Error "Missing required variable $var in $EnvFile"
        exit 1
    }
}

# Fail early with a friendlier message than Docker Desktop's "command not found".
if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
    Write-Error "docker is not on PATH. Install Docker Desktop for Windows and ensure it is running."
    exit 1
}

Write-Host "Using env file: $EnvFile"

# The client image is built on the host to sidestep a Cloudflare TLS-fingerprint
# reject that kills `npm ci` inside Docker Desktop's WSL 2 VM (see client/Dockerfile).
# We do npm ci + npm run build here; the image only copies dist/ + nginx.conf.
if (-not (Get-Command npm -ErrorAction SilentlyContinue)) {
    Write-Error "npm is not on PATH. Install Node.js 22+ so the client can be built on the host."
    exit 1
}
Push-Location client
try {
    if (-not (Test-Path node_modules)) {
        Write-Host "client/node_modules is missing — running 'npm ci' on the host…"
        npm ci
        if ($LASTEXITCODE -ne 0) {
            Write-Error "npm ci failed on the host (exit code $LASTEXITCODE)."
            exit $LASTEXITCODE
        }
    }
    Write-Host "Building client bundle (npm run build)…"
    npm run build
    if ($LASTEXITCODE -ne 0) {
        Write-Error "npm run build failed on the host (exit code $LASTEXITCODE)."
        exit $LASTEXITCODE
    }
} finally {
    Pop-Location
}

if ($Sequential) {
    # Cap concurrency at 1 so peak build memory doesn't overlap across services. This is the
    # workaround for a stock Docker Desktop VM (~2 GB) that OOM-kills `npm ci` mid-build and
    # surfaces the misleading "npm error Exit handler never called!" message.
    $env:COMPOSE_PARALLEL_LIMIT = "1"
    $env:BUILDKIT_MAX_PARALLELISM = "1"
    Write-Host "Sequential build enabled (COMPOSE_PARALLEL_LIMIT=1, BUILDKIT_MAX_PARALLELISM=1)"

    # Service names must match the top-level keys in docker-compose.yml.
    $services = @(
        "account-service",
        "transaction-service",
        "banking-service",
        "genai-service",
        "orchestrator-service",
        "client"
    )
    foreach ($svc in $services) {
        Write-Host "`n=== Building $svc ==="
        docker compose --env-file $EnvFile build $svc
        if ($LASTEXITCODE -ne 0) {
            Write-Error "Build failed for $svc (exit code $LASTEXITCODE)."
            exit $LASTEXITCODE
        }
    }

    Write-Host "`n=== Starting stack ==="
    docker compose --env-file $EnvFile up -d
} else {
    docker compose --env-file $EnvFile up -d --build
}

if ($SeedDemo) {
    # Read POSTGRES_USER from the env file so the seed connects with the same role compose used.
    $pgUser = @(Get-Content -LiteralPath $EnvFile |
        Where-Object { $_ -match '^POSTGRES_USER=(.+)$' } |
        ForEach-Object { $Matches[1] })[0]
    if (-not $pgUser) { $pgUser = "bank" }

    Write-Host "`n=== Seeding demo data (as $pgUser) ==="
    # Wait for postgres to be healthy before piping SQL into it.
    for ($i = 0; $i -lt 30; $i++) {
        $health = docker compose --env-file $EnvFile ps --format json database 2>$null |
            ConvertFrom-Json -ErrorAction SilentlyContinue |
            Select-Object -ExpandProperty Health -ErrorAction SilentlyContinue
        if ($health -eq "healthy") { break }
        Start-Sleep -Seconds 2
    }
    Get-Content scripts\seed-demo-data.sql |
        docker compose --env-file $EnvFile exec -T database psql -U $pgUser -d bankdb
}

exit $LASTEXITCODE
