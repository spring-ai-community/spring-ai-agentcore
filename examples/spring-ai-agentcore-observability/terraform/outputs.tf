output "runtime_arn" {
  description = "ARN of the deployed AgentCore runtime"
  value       = aws_bedrockagentcore_agent_runtime.observability.agent_runtime_arn
}

output "runtime_name" {
  description = "AgentCore runtime name (also used as service.name in traces)"
  value       = local.runtime_name
}

output "container_uri" {
  description = "Container image URI used for deployment"
  value       = local.container_uri
}

output "region" {
  description = "Deployment region"
  value       = var.aws_region
}
