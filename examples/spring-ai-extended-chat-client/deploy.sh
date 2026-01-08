#!/bin/bash
set -e

echo "🚀 Deploying Spring AI Extended Chat Client to AgentCore Runtime"
echo ""

# Check prerequisites
if ! command -v terraform &> /dev/null; then
    echo "❌ Terraform is required but not installed"
    exit 1
fi

if ! command -v docker &> /dev/null; then
    echo "❌ Docker is required but not installed"
    exit 1
fi

if ! command -v aws &> /dev/null; then
    echo "❌ AWS CLI is required but not installed"
    exit 1
fi

# Check AWS credentials
if ! aws sts get-caller-identity &> /dev/null; then
    echo "❌ AWS credentials not configured"
    exit 1
fi

echo "✅ Prerequisites check passed"
echo ""

# Navigate to terraform directory
cd terraform

# Initialize Terraform
echo "🔧 Initializing Terraform..."
terraform init -input=false

# Plan deployment
echo "📋 Planning deployment..."
terraform plan -out=tfplan

# Apply deployment
echo "🚀 Deploying to AgentCore Runtime..."
terraform apply -auto-approve tfplan

echo ""
echo "✅ Deployment Complete!"
echo ""

# Get outputs
MEMORY_ID=$(terraform output -raw memory_id)
RUNTIME_NAME=$(terraform output -raw runtime_name)
CONTAINER_URI=$(terraform output -raw container_uri)

echo "✅ Deployment successful!"
echo ""
echo "📊 Deployment Information:"
echo "  Memory ID: $MEMORY_ID"
echo "  Runtime Name: $RUNTIME_NAME"
echo "  Container URI: $CONTAINER_URI"
echo ""

echo "🚀 What to run next:"
echo "  ./test.sh    # Test OAuth authentication and memory isolation"
echo ""

echo "🔍 Monitor runtime status:"
echo "  aws bedrock-agentcore-control get-runtime \\"
echo "    --runtime-name $RUNTIME_NAME \\"
echo "    --region us-east-1"
