terraform {
  required_version = ">= 1.6.0"

  # Remote state lives in an Azure Storage container so CI runs are incremental
  # and multiple developers don't fight over local tfstate files.
  # Backend config is supplied via `-backend-config=...` flags from CI / a local
  # `backend.hcl` file — keep this block empty (partial configuration).
  # For local-only experimentation you can comment this out to fall back to
  # the local backend.
  backend "azurerm" {}

  required_providers {
    azurerm = {
      source  = "hashicorp/azurerm"
      version = "~> 4.0"
    }
    local = {
      source  = "hashicorp/local"
      version = "~> 2.5"
    }
    tls = {
      source  = "hashicorp/tls"
      version = "~> 4.0"
    }
  }
}

provider "azurerm" {
  features {}
}
