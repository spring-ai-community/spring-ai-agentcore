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
        # Container stdout/stderr and OTLP logs + EMF metrics are sent to the
        # default runtime log group.
        Sid    = "RuntimeLogs"
        Effect = "Allow"
        Action = ["logs:CreateLogGroup", "logs:CreateLogStream", "logs:PutLogEvents", "logs:DescribeLogStreams", "logs:DescribeLogGroups"]
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

# Log group for OTLP logs and EMF metrics. The OTLP CW Logs endpoint requires
# the log group to exist before sending logs (unlike PutLogEvents which auto-creates).
resource "aws_cloudwatch_log_group" "agent_logs" {
  name              = "/aws/bedrock-agentcore/runtimes/${local.runtime_name}"
  retention_in_days = 7
}

# Short-term memory for session-scoped conversation history.
resource "aws_bedrockagentcore_memory" "stm" {
  name                 = "observability_stm_${random_string.suffix.result}"
  event_expiry_duration = 7
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

  # Observability configuration following the AgentCore docs pattern:
  # - Traces: OTLP → X-Ray endpoint (SigV4, collector-less)
  # - Logs: OTLP → CloudWatch Logs endpoint (SigV4, structured with trace correlation)
  # - Metrics: EMF via PutLogEvents to the same log group (ADOT agent's awsemf exporter)
  #
  # OTEL_EXPORTER_OTLP_LOGS_HEADERS serves dual purpose: it configures both the OTLP
  # logs exporter (target log group/stream) and the EMF metrics exporter (namespace).
  # OTEL_RESOURCE_ATTRIBUTES is intentionally NOT set — AgentCore injects it with
  # cloud.resource_id + cloud.platform, which links spans to this runtime in the
  # GenAI Observability dashboard.
  environment_variables = {
    OTEL_EXPORTER_OTLP_TRACES_ENDPOINT = "https://xray.${var.aws_region}.amazonaws.com/v1/traces"
    OTEL_EXPORTER_OTLP_LOGS_ENDPOINT   = "https://logs.${var.aws_region}.amazonaws.com/v1/logs"
    OTEL_EXPORTER_OTLP_LOGS_HEADERS    = "x-aws-log-group=${aws_cloudwatch_log_group.agent_logs.name},x-aws-log-stream=runtime-logs,x-aws-metric-namespace=bedrock-agentcore"
    AGENTCORE_MEMORY_ID                = aws_bedrockagentcore_memory.stm.id
  }
}
