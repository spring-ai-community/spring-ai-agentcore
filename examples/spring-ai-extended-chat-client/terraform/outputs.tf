output "memory_id" {
  description = "AgentCore Memory ID"
  value       = aws_bedrockagentcore_memory.agent_memory.id
}

output "runtime_name" {
  description = "AgentCore Runtime Name"
  value       = aws_bedrockagentcore_agent_runtime.extended_chat.agent_runtime_name
}

output "runtime_arn" {
  description = "ARN of the deployed AgentCore Runtime"
  value       = aws_bedrockagentcore_agent_runtime.extended_chat.agent_runtime_arn
}

output "container_uri" {
  description = "Container URI used for deployment"
  value       = local.container_uri
}

output "cognito_user_pool_id" {
  description = "Cognito User Pool ID for OAuth authentication"
  value       = aws_cognito_user_pool.oauth_users.id
}

output "cognito_client_id" {
  description = "Cognito Client ID for OAuth authentication"
  value       = aws_cognito_user_pool_client.oauth_client.id
}

output "runtime_status" {
  description = "Runtime deployment status"
  value       = "Runtime deployed with OAuth authentication. Test with: ./test.sh"
}
