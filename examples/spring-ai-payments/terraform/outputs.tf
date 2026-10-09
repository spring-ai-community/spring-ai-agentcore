output "region" {
  value = var.region
}

output "payment_manager_arn" {
  value = awscc_bedrockagentcore_payment_manager.this.payment_manager_arn
}

output "payment_connector_id" {
  value = awscc_bedrockagentcore_payment_connector.privy.payment_connector_id
}

output "next_steps" {
  value = <<-EOT
    Create a wallet for a user:   ./create-instrument.sh <email> [user-id]
    Then connect the agent and fund the wallet as described in README.md.
  EOT
}
