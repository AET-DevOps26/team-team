<#
.SYNOPSIS
  Tear the dev stack down on Windows / PowerShell.

.DESCRIPTION
  PowerShell port of scripts/dev-down.sh. Runs `docker compose --env-file <envfile> down`.

.PARAMETER EnvFile
  Path to the compose env file. Defaults to .env in the current directory.

.EXAMPLE
  .\scripts\dev-down.ps1
#>
[CmdletBinding()]
param(
    [string]$EnvFile = ".env"
)

$ErrorActionPreference = "Stop"
if (Get-Variable -Name PSNativeCommandUseErrorActionPreference -ErrorAction SilentlyContinue) {
    $PSNativeCommandUseErrorActionPreference = $false
}

if (-not (Test-Path -LiteralPath $EnvFile -PathType Leaf)) {
    Write-Error "Missing env file: $EnvFile`nCreate it and define POSTGRES_USER, POSTGRES_PASSWORD, and APP_HOSTNAME."
    exit 1
}

if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
    Write-Error "docker is not on PATH. Install Docker Desktop for Windows and ensure it is running."
    exit 1
}

Write-Host "Using env file: $EnvFile"
docker compose --env-file $EnvFile down
exit $LASTEXITCODE
