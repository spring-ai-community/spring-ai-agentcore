variable "region" {
  description = "AWS region of the payment resources"
  type        = string
  default     = "us-east-1"
}

variable "name_prefix" {
  description = "Prefix of all resource names (letters and digits, starting with a letter)"
  type        = string
  default     = "SpringAiPaymentsExample"

  validation {
    condition     = can(regex("^[a-zA-Z][a-zA-Z0-9]{0,39}$", var.name_prefix))
    error_message = "name_prefix must start with a letter and contain only letters and digits (max 40)."
  }
}

variable "privy_app_id" {
  description = "Privy App ID"
  type        = string
}

variable "privy_app_secret" {
  description = "Privy App Secret"
  type        = string
  sensitive   = true
}

variable "privy_authorization_id" {
  description = "Privy authorization key ID (Wallet infrastructure > Authorization)"
  type        = string
}

variable "privy_authorization_private_key" {
  description = "Privy authorization private key, including the wallet-auth: prefix as shown by Privy"
  type        = string
  sensitive   = true
}
