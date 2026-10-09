data "aws_caller_identity" "current" {}

locals {
  account_id = data.aws_caller_identity.current.account_id
  # AgentCore creates the payment manager's workload identity in this directory
  workload_identities = [
    "arn:aws:bedrock-agentcore:${var.region}:${local.account_id}:workload-identity-directory/default",
    "arn:aws:bedrock-agentcore:${var.region}:${local.account_id}:workload-identity-directory/default/workload-identity/*",
  ]
}

# Service role assumed by AgentCore Payments to read the wallet provider credentials.
resource "aws_iam_role" "payments" {
  name        = "${var.name_prefix}Role"
  description = "AgentCore Payments service role of ${var.name_prefix}"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "bedrock-agentcore.amazonaws.com" }
      Action    = "sts:AssumeRole"
      Condition = {
        StringEquals = { "aws:SourceAccount" = local.account_id }
        ArnLike      = { "aws:SourceArn" = "arn:aws:bedrock-agentcore:${var.region}:${local.account_id}:*" }
      }
    }]
  })
}

# CreatePaymentManager validates that the role already has these permissions.
resource "aws_iam_role_policy" "base" {
  name = "AgentCorePaymentsBase"
  role = aws_iam_role.payments.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid      = "WorkloadIdentityManagement"
        Effect   = "Allow"
        Action   = ["bedrock-agentcore:CreateWorkloadIdentity", "bedrock-agentcore:DeleteWorkloadIdentity", "bedrock-agentcore:TagResource"]
        Resource = local.workload_identities
      },
      {
        Sid      = "WorkloadIdentityAccess"
        Effect   = "Allow"
        Action   = ["bedrock-agentcore:GetWorkloadAccessToken"]
        Resource = local.workload_identities
      },
      {
        Sid      = "PaymentTokenAccess"
        Effect   = "Allow"
        Action   = ["bedrock-agentcore:GetResourcePaymentToken"]
        Resource = concat(["arn:aws:bedrock-agentcore:${var.region}:${local.account_id}:token-vault/default"], local.workload_identities)
      },
    ]
  })
}

# Privy credentials, stored by AgentCore Identity in Secrets Manager.
resource "awscc_bedrockagentcore_payment_credential_provider" "privy" {
  name                       = "${var.name_prefix}Privy"
  credential_provider_vendor = "StripePrivy"

  provider_configuration_input = {
    stripe_privy_configuration = {
      app_id                    = var.privy_app_id
      app_secret                = var.privy_app_secret
      authorization_id          = var.privy_authorization_id
      authorization_private_key = var.privy_authorization_private_key
    }
  }
}

# The connector needs the role to read this credential provider and its secrets.
resource "aws_iam_role_policy" "privy_connector" {
  name = "AgentCorePaymentsPrivyConnector"
  role = aws_iam_role.payments.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid      = "PaymentTokenAccess"
        Effect   = "Allow"
        Action   = ["bedrock-agentcore:GetResourcePaymentToken"]
        Resource = [awscc_bedrockagentcore_payment_credential_provider.privy.credential_provider_arn]
      },
      {
        Sid    = "SecretsManagerAccess"
        Effect = "Allow"
        Action = ["secretsmanager:GetSecretValue"]
        Resource = [
          awscc_bedrockagentcore_payment_credential_provider.privy.provider_configuration_output.stripe_privy_configuration.app_secret_arn.secret_arn,
          awscc_bedrockagentcore_payment_credential_provider.privy.provider_configuration_output.stripe_privy_configuration.authorization_private_key_arn.secret_arn,
        ]
      },
    ]
  })
}

# IAM changes take a few seconds to propagate before AgentCore validates the role.
resource "time_sleep" "iam_propagation" {
  depends_on      = [aws_iam_role_policy.base, aws_iam_role_policy.privy_connector]
  create_duration = "20s"
}

resource "awscc_bedrockagentcore_payment_manager" "this" {
  name            = var.name_prefix
  description     = "Spring AI AgentCore Payments example"
  authorizer_type = "AWS_IAM"
  role_arn        = aws_iam_role.payments.arn

  depends_on = [time_sleep.iam_propagation]
}

resource "awscc_bedrockagentcore_payment_connector" "privy" {
  payment_manager_id = awscc_bedrockagentcore_payment_manager.this.payment_manager_id
  connector_name     = "${var.name_prefix}Privy"
  connector_type     = "StripePrivy"

  credential_provider_configurations = [{
    stripe_privy = {
      credential_provider_arn = awscc_bedrockagentcore_payment_credential_provider.privy.credential_provider_arn
    }
  }]
}
