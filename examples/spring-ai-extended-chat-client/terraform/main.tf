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

# Get current AWS account ID
data "aws_caller_identity" "current" {}

locals {
  memory_name_clean = "extendedChatClientMemory"
  unique_memory_name = "${local.memory_name_clean}_${random_string.suffix.result}"
  runtime_name = "spring_ai_extended_chat_client_${random_string.suffix.result}"
  
  # Read image URI from build script output
  container_uri = fileexists("image-uri.txt") ? trimspace(file("image-uri.txt")) : var.container_uri
  ecr_repo_name = fileexists("ecr-repo-name.txt") ? trimspace(file("ecr-repo-name.txt")) : "spring-ai-extended-chat-client-default"
}

# Cognito User Pool for OAuth authentication
resource "aws_cognito_user_pool" "oauth_users" {
  name = "spring-ai-extended-chat-client-users-${random_string.suffix.result}"

  password_policy {
    minimum_length    = 8
    require_lowercase = true
    require_numbers   = true
    require_symbols   = false
    require_uppercase = true
  }

  auto_verified_attributes = ["email"]
  
  tags = {
    Environment = var.environment
    Purpose     = "Spring AI Extended Chat Client OAuth"
  }
}

# Cognito User Pool Client
resource "aws_cognito_user_pool_client" "oauth_client" {
  name         = "spring-ai-extended-chat-client-${random_string.suffix.result}"
  user_pool_id = aws_cognito_user_pool.oauth_users.id

  generate_secret = false
  
  explicit_auth_flows = [
    "ADMIN_NO_SRP_AUTH",
    "USER_PASSWORD_AUTH"
  ]
}

# IAM Role for AgentCore Runtime
resource "aws_iam_role" "agentcore_runtime" {
  name = "ExtendedChatClientRuntimeRole-${random_string.suffix.result}"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect = "Allow"
        Principal = {
          Service = "bedrock-agentcore.amazonaws.com"
        }
        Action = "sts:AssumeRole"
        Condition = {
          StringEquals = {
            "aws:SourceAccount" = data.aws_caller_identity.current.account_id
          }
          ArnLike = {
            "aws:SourceArn" = "arn:aws:bedrock-agentcore:${var.aws_region}:${data.aws_caller_identity.current.account_id}:*"
          }
        }
      }
    ]
  })
}

# IAM Policy for runtime
resource "aws_iam_role_policy" "agentcore_execution" {
  name = "AgentCoreExecutionPolicy"
  role = aws_iam_role.agentcore_runtime.id
  
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect = "Allow"
        Action = [
          "ecr:BatchGetImage",
          "ecr:GetDownloadUrlForLayer",
          "ecr:GetAuthorizationToken"
        ]
        Resource = "*"
      },
      {
        Effect = "Allow"
        Action = [
          "logs:CreateLogGroup",
          "logs:CreateLogStream",
          "logs:PutLogEvents"
        ]
        Resource = "arn:aws:logs:${var.aws_region}:${data.aws_caller_identity.current.account_id}:*"
      },
      {
        Effect = "Allow"
        Action = [
          "bedrock:InvokeModel",
          "bedrock:InvokeModelWithResponseStream"
        ]
        Resource = "*"
      },
      {
        Effect = "Allow"
        Action = [
          "bedrock-agentcore:*"
        ]
        Resource = "*"
      }
    ]
  })
}

# AgentCore Runtime with OAuth authentication
resource "aws_bedrockagentcore_agent_runtime" "extended_chat" {
  depends_on = [aws_bedrockagentcore_memory.agent_memory]
  
  agent_runtime_name = local.runtime_name
  role_arn          = aws_iam_role.agentcore_runtime.arn

  agent_runtime_artifact {
    container_configuration {
      container_uri = local.container_uri
    }
  }

  network_configuration {
    network_mode = "PUBLIC"
  }

  authorizer_configuration {
    custom_jwt_authorizer {
      discovery_url   = "https://cognito-idp.${var.aws_region}.amazonaws.com/${aws_cognito_user_pool.oauth_users.id}/.well-known/openid-configuration"
      allowed_clients = [aws_cognito_user_pool_client.oauth_client.id]
    }
  }

  request_header_configuration {
    request_header_allowlist = [
      "Authorization",
      "X-Amzn-Bedrock-AgentCore-Runtime-Custom-Test"
    ]
  }

  environment_variables = {
    AGENTCORE_MEMORY_ID = aws_bedrockagentcore_memory.agent_memory.id
    SPRING_PROFILES_ACTIVE = "production"
  }
}
