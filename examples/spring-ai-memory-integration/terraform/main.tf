terraform {
  required_version = ">= 1.0"
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = ">= 6.18.0"
    }
    random = {
      source  = "hashicorp/random"
      version = "~> 3.1"
    }
  }
}

provider "aws" {
  region = var.aws_region
}

# Generate a unique memory name that follows AWS naming constraints
resource "random_string" "suffix" {
  length  = 8
  special = false
  upper   = false
}

locals {
  # AWS requires pattern: [a-zA-Z][a-zA-Z0-9_]{0,47}
  memory_name_clean = replace(var.memory_name, "-", "_")
  unique_memory_name = "${local.memory_name_clean}_${random_string.suffix.result}"
}

# Create AgentCore Memory using proper Terraform resource
resource "aws_bedrockagentcore_memory" "agent_memory" {
  name                 = local.unique_memory_name
  description          = "Memory for Spring AI AgentCore example - ${var.environment}"
  event_expiry_duration = 365
}

# Output the memory details
output "memory_id" {
  description = "The ID of the created AgentCore Memory"
  value       = aws_bedrockagentcore_memory.agent_memory.id
}

output "memory_arn" {
  description = "The ARN of the created AgentCore Memory"
  value       = aws_bedrockagentcore_memory.agent_memory.arn
}

output "memory_name" {
  description = "The name of the created AgentCore Memory"
  value       = aws_bedrockagentcore_memory.agent_memory.name
}

output "export_command" {
  description = "Command to export memory ID as environment variable"
  value       = "export AGENTCORE_MEMORY_ID=${aws_bedrockagentcore_memory.agent_memory.id}"
}
