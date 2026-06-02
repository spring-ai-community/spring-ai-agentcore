#!/bin/bash
set -e

AWS_REGION="${AWS_REGION:-$(cd terraform && terraform output -raw region 2>/dev/null || echo us-east-1)}"
# Runtime session IDs must be at least 33 characters. The agent propagates this as the
# session.id on the emitted spans, so traces for the same session group together.
SESSION_ID="${1:-obs-session-$(date +%s)abcdefghijklmnopqrstuvwxyz}"
PROMPT="${2:-What is the current date and time?}"

AGENT_RUNTIME_ARN=$(cd terraform && terraform output -raw runtime_arn 2>/dev/null)
if [ -z "$AGENT_RUNTIME_ARN" ]; then
    echo "❌ No runtime found. Run ./deploy.sh first."
    exit 1
fi

if [ ${#SESSION_ID} -lt 33 ]; then
    echo "❌ Session ID must be at least 33 characters (got ${#SESSION_ID})"
    exit 1
fi

echo "🚀 Invoking runtime"
echo "📝 Prompt: $PROMPT"
echo "🔑 Session ID: $SESSION_ID"
echo ""

PAYLOAD_B64=$(printf '{"prompt":"%s"}' "$PROMPT" | base64)

aws bedrock-agentcore invoke-agent-runtime \
    --agent-runtime-arn "$AGENT_RUNTIME_ARN" \
    --content-type "application/json" \
    --accept "application/json" \
    --runtime-session-id "$SESSION_ID" \
    --runtime-user-id "obs-user" \
    --qualifier "DEFAULT" \
    --payload "$PAYLOAD_B64" \
    --region "$AWS_REGION" \
    --no-cli-pager \
    --output text \
    --query 'response' \
    /dev/stdout
echo ""
echo "✅ Invocation completed"
