variable "aws_region" {
  description = "AWS region for deployment"
  type        = string
  default     = "us-east-1"
}

variable "container_uri" {
  description = "Container URI for the AgentCore runtime (fallback if image-uri.txt is not found)"
  type        = string
  default     = ""
}
