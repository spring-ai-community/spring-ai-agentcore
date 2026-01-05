#!/bin/bash

set -e

echo "🚀 Deploying Spring AI AgentCore Memory Example"

# Check prerequisites
command -v terraform >/dev/null 2>&1 || { echo "❌ Terraform is required but not installed."; exit 1; }
command -v aws >/dev/null 2>&1 || { echo "❌ AWS CLI is required but not installed."; exit 1; }
command -v mvn >/dev/null 2>&1 || { echo "❌ Maven is required but not installed."; exit 1; }
command -v jq >/dev/null 2>&1 || { echo "❌ jq is required but not installed."; exit 1; }

echo "✅ Prerequisites check passed"

# Check AWS credentials
aws sts get-caller-identity >/dev/null 2>&1 || { echo "❌ AWS credentials not configured."; exit 1; }
echo "✅ AWS credentials verified"

# Deploy infrastructure
echo "📦 Deploying infrastructure..."
cd terraform
terraform init
terraform apply -auto-approve

# Get memory ID
MEMORY_ID=$(terraform output -raw memory_id)
echo "✅ AgentCore Memory created with ID: $MEMORY_ID"

# Export memory ID
export AGENTCORE_MEMORY_ID=$MEMORY_ID
echo "✅ Environment variable set: AGENTCORE_MEMORY_ID=$MEMORY_ID"

# Build application
echo "🔨 Building application..."
cd ..
mvn clean package -q

echo "🎉 Deployment complete!"
echo ""
echo "⚠️  Run this command to set the environment variable:"
echo "  export AGENTCORE_MEMORY_ID=$MEMORY_ID"
echo ""
echo "Then run the application:"
echo "  mvn spring-boot:run"
echo ""
echo "API will be available at: http://localhost:8080/api/chat"
