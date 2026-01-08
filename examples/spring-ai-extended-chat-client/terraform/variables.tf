variable "aws_region" {
  description = "AWS region for deployment"
  type        = string
  default     = "us-east-1"
}

variable "environment" {
  description = "Environment name"
  type        = string
  default     = "dev"
}

variable "memory_name" {
  description = "Base name for AgentCore memory"
  type        = string
  default     = "extendedChatClientMemory"
}

variable "container_uri" {
  description = "Container URI for AgentCore runtime (fallback if image-uri.txt not found)"
  type        = string
  default     = ""
}
