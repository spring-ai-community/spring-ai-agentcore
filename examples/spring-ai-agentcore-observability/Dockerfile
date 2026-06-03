FROM --platform=linux/arm64 amazoncorretto:21-alpine

# AWS Distro for OpenTelemetry (ADOT) Java auto-instrumentation agent version.
ARG ADOT_VERSION=v2.26.2
# AgentCore OTel extension version (adds aws.service.type=gen_ai_agent for the GenAI dashboard).
ARG AGENTCORE_EXT_VERSION=1.1.0

RUN addgroup -S appuser && adduser -S appuser -G appuser
WORKDIR /app

# The ADOT Java agent auto-instruments Spring MVC and ships spans to the CloudWatch
# X-Ray OTLP endpoint with SigV4 signing (collector-less). It also forwards the
# Micrometer-bridged Spring AI spans.
RUN apk add --no-cache curl \
    && curl -fsSL -o /app/aws-opentelemetry-agent.jar \
       "https://github.com/aws-observability/aws-otel-java-instrumentation/releases/download/${ADOT_VERSION}/aws-opentelemetry-agent.jar" \
    && apk del curl

COPY target/agentcore-otel-extension.jar /app/agentcore-otel-extension.jar

COPY target/spring-ai-agentcore-observability.jar app.jar
RUN chown -R appuser:appuser /app
USER appuser
EXPOSE 8080

# Attach the ADOT agent and the AgentCore OTel extension. The extension handles agent
# observability mode config (resource attributes, session tracking, noise reduction) when
# AGENT_OBSERVABILITY_ENABLED=true is injected by AgentCore. Only exporter plumbing remains
# here. OTEL_RESOURCE_ATTRIBUTES is NOT set — AgentCore injects it with cloud.resource_id.
ENV JAVA_TOOL_OPTIONS="-javaagent:/app/aws-opentelemetry-agent.jar" \
    OTEL_JAVAAGENT_EXTENSIONS=/app/agentcore-otel-extension.jar \
    OTEL_EXPORTER_OTLP_PROTOCOL=http/protobuf \
    OTEL_TRACES_EXPORTER=otlp \
    OTEL_LOGS_EXPORTER=otlp

ENTRYPOINT ["java", "-jar", "app.jar"]
