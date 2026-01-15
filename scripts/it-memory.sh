#!/bin/bash
# AgentCore Memory integration tests
# Usage: ./scripts/it-memory.sh [app_prefix]

APP_NAME="${1:-aiagent}"
MEMORY_NAME="${APP_NAME}_memory"
REGION="${AWS_REGION:-us-east-1}"

# Check AWS credentials
if ! aws sts get-caller-identity --no-cli-pager &>/dev/null; then
  echo "❌ AWS credentials not configured. Configure AWS credentials and try again."
  exit 1
fi

echo "🔍 Looking for memory: ${MEMORY_NAME}*"

MEMORY_ID=$(aws bedrock-agentcore-control list-memories --region "$REGION" --no-cli-pager \
  --query "memories[?starts_with(id, '${MEMORY_NAME}')].id | [0]" --output text 2>/dev/null)

if [ -z "$MEMORY_ID" ] || [ "$MEMORY_ID" = "None" ]; then
  echo "❌ Memory not found: ${MEMORY_NAME}"
  exit 1
fi

export AGENTCORE_MEMORY_MEMORY_ID="$MEMORY_ID"

export AGENTCORE_MEMORY_LONG_TERM_SEMANTIC_FACTS_STRATEGY_ID=$(aws bedrock-agentcore-control get-memory \
  --region "$REGION" --memory-id "$MEMORY_ID" --no-cli-pager \
  --query "memory.strategies[?name=='SemanticFacts'].strategyId | [0]" --output text 2>/dev/null)

export AGENTCORE_MEMORY_LONG_TERM_USER_PREFERENCES_STRATEGY_ID=$(aws bedrock-agentcore-control get-memory \
  --region "$REGION" --memory-id "$MEMORY_ID" --no-cli-pager \
  --query "memory.strategies[?name=='UserPreferences'].strategyId | [0]" --output text 2>/dev/null)

export AGENTCORE_MEMORY_LONG_TERM_SUMMARY_STRATEGY_ID=$(aws bedrock-agentcore-control get-memory \
  --region "$REGION" --memory-id "$MEMORY_ID" --no-cli-pager \
  --query "memory.strategies[?name=='ConversationSummary'].strategyId | [0]" --output text 2>/dev/null)

export AGENTCORE_MEMORY_LONG_TERM_EPISODIC_STRATEGY_ID=$(aws bedrock-agentcore-control get-memory \
  --region "$REGION" --memory-id "$MEMORY_ID" --no-cli-pager \
  --query "memory.strategies[?name=='EpisodicMemory'].strategyId | [0]" --output text 2>/dev/null)

echo "✅ Memory: $AGENTCORE_MEMORY_MEMORY_ID"
echo "   Semantic: $AGENTCORE_MEMORY_LONG_TERM_SEMANTIC_FACTS_STRATEGY_ID"
echo "   Preferences: $AGENTCORE_MEMORY_LONG_TERM_USER_PREFERENCES_STRATEGY_ID"
echo "   Summary: $AGENTCORE_MEMORY_LONG_TERM_SUMMARY_STRATEGY_ID"
echo "   Episodic: $AGENTCORE_MEMORY_LONG_TERM_EPISODIC_STRATEGY_ID"
echo ""
echo "🧪 Running AgentCore Memory integration tests..."
echo ""

mvn test -pl spring-ai-memory-bedrock-agentcore -Dtest=AgentCoreMemoryEnvIT
