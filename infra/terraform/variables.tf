variable "project_name" {
  description = "Short project name used as a prefix for all Azure resources."
  type        = string
  default     = "team-team"
}

variable "environment" {
  description = "Deployment environment label (e.g. dev, prod)."
  type        = string
  default     = "dev"
}

variable "location" {
  description = "Azure region."
  type        = string
  default     = "polandcentral"
}

variable "vm_size" {
  description = "Azure VM SKU."
  type        = string
  default     = "Standard_B4s_v2"
}

variable "admin_username" {
  description = "Linux admin username on the VM."
  type        = string
  default     = "azureuser"
}

variable "ssh_public_key_path" {
  description = "Path to an existing SSH public key. If empty, a new key pair is generated and written to ./ssh/."
  type        = string
  default     = ""
}

variable "ssh_private_key_path" {
  description = <<-EOT
    Path to the matching SSH **private** key, used to render the Ansible
    inventory's `ansible_ssh_private_key_file`. Only consulted when
    `ssh_public_key_path` is set.

    Leave empty to derive it by stripping a trailing `.pub` from
    `ssh_public_key_path` (works for the common `id_rsa` / `id_rsa.pub`
    convention). Set explicitly when your keys don't follow that naming
    (e.g. `~/.ssh/team-team-azure` paired with `team-team-azure.pub`).
  EOT
  type        = string
  default     = ""

  validation {
    condition = (
      var.ssh_private_key_path == "" ||
      can(regex("\\.pub$", var.ssh_private_key_path)) == false
    )
    error_message = "ssh_private_key_path must point to the PRIVATE key file (no trailing .pub)."
  }
}

variable "allowed_ssh_cidr" {
  description = <<-EOT
    CIDR block allowed to SSH (port 22) to the VM. Required — must be an
    explicit CIDR such as "203.0.113.4/32". The wildcard "*" / "0.0.0.0/0"
    is rejected to prevent accidentally exposing SSH to the public internet.
    Find your IP via https://ifconfig.me and append "/32".
  EOT
  type        = string

  validation {
    condition     = var.allowed_ssh_cidr != "*" && var.allowed_ssh_cidr != "0.0.0.0/0" && can(cidrnetmask(var.allowed_ssh_cidr))
    error_message = "allowed_ssh_cidr must be an explicit CIDR (e.g. \"1.2.3.4/32\"); \"*\" and \"0.0.0.0/0\" are not allowed."
  }
}

variable "os_disk_size_gb" {
  description = "OS disk size in GB."
  type        = number
  default     = 64
}

variable "tags" {
  description = "Tags applied to all resources."
  type        = map(string)
  default = {
    project = "team-team"
    managed = "terraform"
  }
}
