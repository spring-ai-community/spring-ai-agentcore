# AgentCore Payments resources for the example (Terraform)

Terraform configuration and scripts used by the [setup of the payments example](../README.md#setup-stripe-privy-testnets); follow the steps there. This file only describes what is created.

`terraform apply` creates, in the configured region (default `us-east-1`, name prefix `SpringAiPaymentsExample`; to change them, copy `terraform.tfvars.example` to `terraform.tfvars`):

- IAM role `<prefix>Role` trusted by `bedrock-agentcore.amazonaws.com`, with the permissions AgentCore validates when the payment manager is created and access to the Privy secrets.
- Payment credential provider `<prefix>Privy` (secrets stored by AgentCore Identity in Secrets Manager).
- Payment manager `<prefix>` (`AWS_IAM` authorizer) and connector `<prefix>Privy`.

Inputs: the Privy app ID, app secret, authorization key ID and private key, read from `TF_VAR_privy_app_id`, `TF_VAR_privy_app_secret`, `TF_VAR_privy_authorization_id` and `TF_VAR_privy_authorization_private_key` (setup step 1).

> The Terraform state contains the Privy secrets in plain text (the `awscc` provider does not mark them sensitive). Keep the state local or in an encrypted backend.

| Script | Purpose |
|---|---|
| `create-instrument.sh <email> [user-id]` | Creates a wallet (payment instrument) for a user and prints the variables for the example |
| `check-wallet.sh <instrument-id> [user-id]` | Shows status, USDC balance on Base Sepolia and pathUSD balance on Tempo Moderato |
| `cleanup.sh <instrument-id> [user-id]` | Deletes the wallet in AgentCore Payments and runs `terraform destroy` |
