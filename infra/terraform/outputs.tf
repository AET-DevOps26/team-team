output "public_ip" {
  description = "Public IP of the VM."
  value       = azurerm_public_ip.pip.ip_address
}

output "fqdn" {
  description = "Auto-generated Azure FQDN of the VM."
  value       = azurerm_public_ip.pip.fqdn
}

output "ssh_command" {
  description = "Convenience SSH command."
  value       = var.ssh_public_key_path == "" ? "ssh -i ${path.module}/ssh/id_rsa ${var.admin_username}@${azurerm_public_ip.pip.ip_address}" : "ssh ${var.admin_username}@${azurerm_public_ip.pip.ip_address}"
}

output "resource_group" {
  value = azurerm_resource_group.rg.name
}
