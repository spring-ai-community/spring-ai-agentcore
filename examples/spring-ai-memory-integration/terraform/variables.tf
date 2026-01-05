variable "aws_region" {
  description = "AWS region for resources"
  type        = string
  default     = "us-east-1"
}

variable "memory_name" {
  description = "Base name for the AgentCore Memory"
  type        = string
  default     = "springAiMemory"
}

variable "environment" {
  description = "Environment name"
  type        = string
  default     = "development"
}
