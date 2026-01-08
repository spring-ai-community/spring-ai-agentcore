#!/bin/bash

echo "🧹 Starting cleanup of Spring AI Extended Chat Client..."

# Check for remaining agent runtimes and remove them
echo "🔍 Checking for remaining agent runtimes..."
REMAINING_RUNTIMES=$(aws bedrock-agentcore-control list-agent-runtimes --region us-east-1 --query 'agentRuntimes[?contains(agentRuntimeName, `spring_ai_extended`)].agentRuntimeId' --output text)

if [ -n "$REMAINING_RUNTIMES" ]; then
    echo "🗑️  Found remaining agent runtimes, removing them..."
    for runtime_id in $REMAINING_RUNTIMES; do
        echo "   Deleting runtime: $runtime_id"
        aws bedrock-agentcore-control delete-agent-runtime --agent-runtime-id "$runtime_id" --region us-east-1
    done
    
    # Wait for deletion to complete
    echo "⏳ Waiting for agent runtime deletion to complete..."
    attempt=1
    max_attempts=30
    while [ $attempt -le $max_attempts ]; do
        REMAINING_RUNTIMES=$(aws bedrock-agentcore-control list-agent-runtimes --region us-east-1 --query 'agentRuntimes[?contains(agentRuntimeName, `spring_ai_extended`)].agentRuntimeId' --output text)
        if [ -z "$REMAINING_RUNTIMES" ]; then
            echo "✅ Agent runtime deletion completed"
            break
        fi
        echo "   Still deleting... (attempt $attempt/$max_attempts)"
        sleep 10
        ((attempt++))
    done
    
    if [ $attempt -gt $max_attempts ]; then
        echo "⚠️  Warning: Agent runtime deletion may still be in progress"
    fi
else
    echo "✅ No remaining agent runtimes found"
fi

# Destroy infrastructure
echo "🏗️  Destroying infrastructure..."
cd terraform
terraform destroy -auto-approve

echo "✅ Cleanup completed!"
