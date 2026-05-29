# Bedrock Agent Observability Demo (tighter-scope build)

Exercises the AgentCore-aware observability module against live Amazon Bedrock.

## What this demo proves

1. Inbound headers `X-Amzn-Bedrock-AgentCore-Runtime-Session-Id` and `x-amzn-request-id`
   land on the GenAI span as `aws.bedrock.agentcore.session_id` / `aws.request_id`.
2. `gen_ai.*` attribute enrichment on the AgentCore controller invocation span,
   plus token usage attributes.
3. No `gen_ai.content.*` events emitted by this module (asserted directly).

## Run

```bash
export AWS_REGION=us-east-1
export AWS_ACCESS_KEY_ID=...
export AWS_SECRET_ACCESS_KEY=...
mvn -f spring-ai-agentcore-observability/examples/bedrock-agent-demo/pom.xml \
    spring-boot:run
```

The app starts on a random port, POSTs once to /invocations, reads spans from the in-memory OTel exporter, prints [PASS] / [FAIL] lines, and exits.
