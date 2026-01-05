# AgentCore Memory for Spring AI Extended Chat Client
resource "aws_bedrockagentcore_memory" "agent_memory" {
  name                 = local.unique_memory_name
  description          = "Memory for Spring AI Extended Chat Client"
  event_expiry_duration = 30
}

# Random suffix for unique memory names
resource "random_string" "suffix" {
  length  = 8
  special = false
  upper   = false
}
