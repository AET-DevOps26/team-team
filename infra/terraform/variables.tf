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

variable "allowed_ssh_cidr" {
  description = "CIDR allowed to SSH to the VM. Set to your IP for safety (e.g. \"1.2.3.4/32\")."
  type        = string
  default     = "*"
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
