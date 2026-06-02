data "aws_caller_identity" "current" {}

resource "random_string" "suffix" {
  length  = 6
  special = false
  upper   = false
}

locals {
  runtime_name = "spring_ai_agentcore_observability_${random_string.suffix.result}"

  # Image URI is written by build-and-push.sh; fall back to the variable otherwise.
  container_uri = fileexists("image-uri.txt") ? trimspace(file("image-uri.txt")) : var.container_uri
}

# Execution role assumed by the AgentCore runtime.
resource "aws_iam_role" "agentcore_runtime" {
  name = "SpringAiObservabilityRuntimeRole-${random_string.suffix.result}"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect    = "Allow"
        Principal = { Service = "bedrock-agentcore.amazonaws.com" }
        Action    = "sts:AssumeRole"
        Condition = {
          StringEquals = { "aws:SourceAccount" = data.aws_caller_identity.current.account_id }
          ArnLike      = { "aws:SourceArn" = "arn:aws:bedrock-agentcore:${var.aws_region}:${data.aws_caller_identity.current.account_id}:*" }
        }
      }
    ]
  })
}

resource "aws_iam_role_policy" "agentcore_execution" {
  name = "AgentCoreExecutionPolicy"
  role = aws_iam_role.agentcore_runtime.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid      = "EcrPull"
        Effect   = "Allow"
        Action   = ["ecr:BatchGetImage", "ecr:GetDownloadUrlForLayer", "ecr:GetAuthorizationToken"]
        Resource = "*"
      },
      {
        # Container stdout/stderr is captured to the default runtime log group.
        Sid    = "RuntimeLogs"
        Effect = "Allow"
        Action = ["logs:CreateLogGroup", "logs:CreateLogStream", "logs:PutLogEvents", "logs:DescribeLogStreams"]
        Resource = [
          "arn:aws:logs:${var.aws_region}:${data.aws_caller_identity.current.account_id}:log-group:/aws/bedrock-agentcore/runtimes/*"
        ]
      },
      {
        # OTLP traces exporter posts spans to the X-Ray endpoint (Transaction Search).
        Sid      = "XRayWrite"
        Effect   = "Allow"
        Action   = ["xray:PutTraceSegments", "xray:PutTelemetryRecords", "xray:GetSamplingRules", "xray:GetSamplingTargets"]
        Resource = "*"
      },
      {
        Sid    = "BedrockInvoke"
        Effect = "Allow"
        Action = ["bedrock:InvokeModel", "bedrock:InvokeModelWithResponseStream"]
        Resource = [
          "arn:aws:bedrock:*::foundation-model/*",
          "arn:aws:bedrock:*:${data.aws_caller_identity.current.account_id}:inference-profile/*"
        ]
      },
      {
        Sid      = "AgentCoreMemory"
        Effect   = "Allow"
        Action   = ["bedrock-agentcore:*"]
        Resource = "arn:aws:bedrock-agentcore:${var.aws_region}:${data.aws_caller_identity.current.account_id}:memory/*"
      }
    ]
  })
}

# IAM-authenticated AgentCore runtime. Observability is configured through the OTEL_*
# environment variables consumed by the ADOT Java agent inside the container.
resource "aws_bedrockagentcore_agent_runtime" "observability" {
  agent_runtime_name = local.runtime_name
  role_arn           = aws_iam_role.agentcore_runtime.arn

  agent_runtime_artifact {
    container_configuration {
      container_uri = local.container_uri
    }
  }

  network_configuration {
    network_mode = "PUBLIC"
  }

  # Only the trace endpoint is overridden: the runtime's injected OTLP endpoint targets
  # a Python-only sidecar, so the ADOT Java agent ships spans collector-less (SigV4) to
  # X-Ray instead. OTEL_RESOURCE_ATTRIBUTES is intentionally NOT set: AgentCore injects
  # it with cloud.resource_id + cloud.platform, which is what links the spans to this
  # runtime in the GenAI Observability "Bedrock AgentCore" tab. Overriding it breaks that.
  environment_variables = {
    OTEL_EXPORTER_OTLP_TRACES_ENDPOINT = "https://xray.${var.aws_region}.amazonaws.com/v1/traces"
    AGENTCORE_MEMORY_ID                = var.memory_id
  }
}
