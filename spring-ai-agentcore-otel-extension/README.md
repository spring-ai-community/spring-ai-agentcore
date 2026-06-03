# Spring AI AgentCore OTel Extension

A lightweight OTel Java agent extension that configures observability for Java agents
targeting the **CloudWatch GenAI Observability** dashboard. It bridges the gap between
Python (which gets automatic instrumentation from the ADOT Python distro) and Java.

Activates when `AGENT_OBSERVABILITY_ENABLED=true` — no-op otherwise.

## What it does

| Feature | Purpose |
|---------|---------|
| `aws.service.type=gen_ai_agent` resource attribute | Required for the "Bedrock AgentCore" dashboard tab |
| `session.id` baggage → span attribute | Groups traces by session in the dashboard |
| `parentbased_always_on` sampler | 100% span capture for agent observability |
| Disable AWS resource detectors | Prevents `cloud.platform=aws_ec2` from overriding AgentCore's value |
| Disable Application Signals | Avoids duplicate cost with Transaction Search |
| Disable `http-url-connection` instrumentation | Suppresses IMDS credential-fetching noise |
| Disable Tomcat/Servlet instrumentation | Avoids duplicate server spans (Spring MVC provides one) |

All settings are defaults — override any with environment variables.

## Usage

### AgentCore Runtime-hosted agents

AgentCore injects `AGENT_OBSERVABILITY_ENABLED=true` and `OTEL_RESOURCE_ATTRIBUTES` automatically.
You only need to add the extension to your Dockerfile:

```dockerfile
ARG ADOT_VERSION=v2.26.2
ARG AGENTCORE_EXT_VERSION=1.1.0

RUN curl -fsSL -o /app/aws-opentelemetry-agent.jar \
    "https://github.com/aws-observability/aws-otel-java-instrumentation/releases/download/${ADOT_VERSION}/aws-opentelemetry-agent.jar" \
 && curl -fsSL -o /app/agentcore-otel-extension.jar \
    "https://repo1.maven.org/maven2/org/springaicommunity/spring-ai-agentcore-otel-extension/${AGENTCORE_EXT_VERSION}/spring-ai-agentcore-otel-extension-${AGENTCORE_EXT_VERSION}.jar"

ENV JAVA_TOOL_OPTIONS="-javaagent:/app/aws-opentelemetry-agent.jar" \
    OTEL_JAVAAGENT_EXTENSIONS=/app/agentcore-otel-extension.jar \
    OTEL_EXPORTER_OTLP_PROTOCOL=http/protobuf \
    OTEL_TRACES_EXPORTER=otlp \
    OTEL_LOGS_EXPORTER=otlp \
    OTEL_METRICS_EXPORTER=none
```

The trace endpoint is set via Terraform/environment variable:
```
OTEL_EXPORTER_OTLP_TRACES_ENDPOINT=https://xray.<region>.amazonaws.com/v1/traces
```

### Non-runtime hosted agents (ECS, EKS, EC2, Lambda)

For agents running outside AgentCore Runtime, set the following environment variables
in addition to the Dockerfile setup above:

```bash
# Activate the extension
AGENT_OBSERVABILITY_ENABLED=true

# Resource attributes (AgentCore would inject these on-runtime; set manually here)
OTEL_RESOURCE_ATTRIBUTES=service.name=<your-agent-name>,cloud.resource_id=<AgentEndpointArn>:<EndpointName>

# Trace export endpoint
OTEL_EXPORTER_OTLP_TRACES_ENDPOINT=https://xray.<region>.amazonaws.com/v1/traces

# Protocol and exporters
OTEL_EXPORTER_OTLP_PROTOCOL=http/protobuf
OTEL_TRACES_EXPORTER=otlp

# Optional: OTLP logs export (if not using stdout)
OTEL_LOGS_EXPORTER=otlp
OTEL_EXPORTER_OTLP_LOGS_ENDPOINT=https://logs.<region>.amazonaws.com/v1/logs
OTEL_EXPORTER_OTLP_LOGS_HEADERS=x-aws-log-group=/aws/bedrock-agentcore/runtimes/<agent-id>,x-aws-log-stream=runtime-logs
```

The IAM role must have `xray:PutTraceSegments`, `xray:PutTelemetryRecords`,
`xray:GetSamplingRules`, and `xray:GetSamplingTargets` permissions.

## Prerequisites

- [CloudWatch Transaction Search](https://docs.aws.amazon.com/AmazonCloudWatch/latest/monitoring/Enable-TransactionSearch.html)
  enabled (one-time, per account/region)
- ADOT Java agent v2.10+ (tested with v2.26.2)

## How it works

The extension implements `AutoConfigurationCustomizerProvider` — an OTel Java agent SPI
that runs during agent initialization (before the application starts). It merges resource
attributes and registers a `SpanProcessor` for session ID propagation. Because it runs in
the agent's classloader, it can modify the resource before it's sealed.

## Overriding defaults

Every property set by the extension can be overridden with environment variables:

```bash
# Re-enable Tomcat instrumentation
OTEL_INSTRUMENTATION_TOMCAT_ENABLED=true

# Use a different sampler
OTEL_TRACES_SAMPLER=parentbased_traceidratio
OTEL_TRACES_SAMPLER_ARG=0.1
```
