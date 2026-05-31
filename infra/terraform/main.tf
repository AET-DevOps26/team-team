locals {
  name_prefix = "${var.project_name}-${var.environment}"
  tags        = merge(var.tags, { environment = var.environment })
}

# ---------------------------------------------------------------------------
# SSH key (use provided or generate one)
# ---------------------------------------------------------------------------
resource "tls_private_key" "generated" {
  count     = var.ssh_public_key_path == "" ? 1 : 0
  algorithm = "RSA"
  rsa_bits  = 4096
}

resource "local_sensitive_file" "private_key" {
  count           = var.ssh_public_key_path == "" ? 1 : 0
  content         = tls_private_key.generated[0].private_key_openssh
  filename        = "${path.module}/ssh/id_rsa"
  file_permission = "0600"
}

resource "local_file" "public_key" {
  count           = var.ssh_public_key_path == "" ? 1 : 0
  content         = tls_private_key.generated[0].public_key_openssh
  filename        = "${path.module}/ssh/id_rsa.pub"
  file_permission = "0644"
}

locals {
  ssh_public_key = var.ssh_public_key_path == "" ? tls_private_key.generated[0].public_key_openssh : file(pathexpand(var.ssh_public_key_path))
}

# ---------------------------------------------------------------------------
# Resource group
# ---------------------------------------------------------------------------
resource "azurerm_resource_group" "rg" {
  name     = "${local.name_prefix}-rg"
  location = var.location
  tags     = local.tags
}

# ---------------------------------------------------------------------------
# Networking
# ---------------------------------------------------------------------------
resource "azurerm_virtual_network" "vnet" {
  name                = "${local.name_prefix}-vnet"
  address_space       = ["10.30.0.0/16"]
  location            = azurerm_resource_group.rg.location
  resource_group_name = azurerm_resource_group.rg.name
  tags                = local.tags
}

resource "azurerm_subnet" "subnet" {
  name                 = "${local.name_prefix}-subnet"
  resource_group_name  = azurerm_resource_group.rg.name
  virtual_network_name = azurerm_virtual_network.vnet.name
  address_prefixes     = ["10.30.1.0/24"]
}

resource "azurerm_public_ip" "pip" {
  name                = "${local.name_prefix}-pip"
  location            = azurerm_resource_group.rg.location
  resource_group_name = azurerm_resource_group.rg.name
  allocation_method   = "Static"
  sku                 = "Standard"
  domain_name_label   = "${var.project_name}-${var.environment}-${substr(md5(azurerm_resource_group.rg.id), 0, 6)}"
  tags                = local.tags
}

resource "azurerm_network_security_group" "nsg" {
  name                = "${local.name_prefix}-nsg"
  location            = azurerm_resource_group.rg.location
  resource_group_name = azurerm_resource_group.rg.name
  tags                = local.tags

  security_rule {
    name                       = "SSH"
    priority                   = 1000
    direction                  = "Inbound"
    access                     = "Allow"
    protocol                   = "Tcp"
    source_port_range          = "*"
    destination_port_range     = "22"
    source_address_prefix      = var.allowed_ssh_cidr
    destination_address_prefix = "*"
  }

  security_rule {
    name                       = "HTTP"
    priority                   = 1010
    direction                  = "Inbound"
    access                     = "Allow"
    protocol                   = "Tcp"
    source_port_range          = "*"
    destination_port_range     = "80"
    source_address_prefix      = "*"
    destination_address_prefix = "*"
  }

  security_rule {
    name                       = "HTTPS"
    priority                   = 1020
    direction                  = "Inbound"
    access                     = "Allow"
    protocol                   = "Tcp"
    source_port_range          = "*"
    destination_port_range     = "443"
    source_address_prefix      = "*"
    destination_address_prefix = "*"
  }
}

resource "azurerm_network_interface" "nic" {
  name                = "${local.name_prefix}-nic"
  location            = azurerm_resource_group.rg.location
  resource_group_name = azurerm_resource_group.rg.name
  tags                = local.tags

  ip_configuration {
    name                          = "primary"
    subnet_id                     = azurerm_subnet.subnet.id
    private_ip_address_allocation = "Dynamic"
    public_ip_address_id          = azurerm_public_ip.pip.id
  }
}

resource "azurerm_network_interface_security_group_association" "nic_nsg" {
  network_interface_id      = azurerm_network_interface.nic.id
  network_security_group_id = azurerm_network_security_group.nsg.id
}

# ---------------------------------------------------------------------------
# Virtual machine (Ubuntu 24.04 LTS)
# ---------------------------------------------------------------------------
resource "azurerm_linux_virtual_machine" "vm" {
  name                            = "${local.name_prefix}-vm"
  resource_group_name             = azurerm_resource_group.rg.name
  location                        = azurerm_resource_group.rg.location
  size                            = var.vm_size
  admin_username                  = var.admin_username
  disable_password_authentication = true
  network_interface_ids           = [azurerm_network_interface.nic.id]
  tags                            = local.tags

  admin_ssh_key {
    username   = var.admin_username
    public_key = local.ssh_public_key
  }

  os_disk {
    name                 = "${local.name_prefix}-osdisk"
    caching              = "ReadWrite"
    storage_account_type = "StandardSSD_LRS"
    disk_size_gb         = var.os_disk_size_gb
  }

  source_image_reference {
    publisher = "Canonical"
    offer     = "ubuntu-24_04-lts"
    sku       = "server"
    version   = "latest"
  }
}

# ---------------------------------------------------------------------------
# Render Ansible inventory once the VM exists
# ---------------------------------------------------------------------------
locals {
  # Resolve the private-key path the Ansible inventory will reference.
  #   1. Generated key      -> ./ssh/id_rsa (absolute)
  #   2. Explicit override  -> var.ssh_private_key_path
  #   3. Otherwise          -> strip ".pub" from var.ssh_public_key_path
  resolved_ssh_private_key_path = (
    var.ssh_public_key_path == "" ? abspath("${path.module}/ssh/id_rsa") :
    var.ssh_private_key_path != "" ? pathexpand(var.ssh_private_key_path) :
    pathexpand(replace(var.ssh_public_key_path, ".pub", ""))
  )
}

resource "local_file" "ansible_inventory" {
  filename        = "${path.module}/../ansible/inventory.ini"
  file_permission = "0644"
  content = templatefile("${path.module}/templates/inventory.tmpl", {
    public_ip      = azurerm_public_ip.pip.ip_address
    fqdn           = azurerm_public_ip.pip.fqdn
    admin_username = var.admin_username
    ssh_key_path   = local.resolved_ssh_private_key_path
  })

  lifecycle {
    precondition {
      condition     = var.ssh_public_key_path == "" || fileexists(local.resolved_ssh_private_key_path)
      error_message = "Resolved SSH private key '${local.resolved_ssh_private_key_path}' does not exist. Set ssh_private_key_path explicitly to the matching private key file."
    }
  }
}
