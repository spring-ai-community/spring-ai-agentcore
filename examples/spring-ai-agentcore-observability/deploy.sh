#!/bin/bash
set -e

echo "🚀 Deploying Spring AI AgentCore Observability example"

for tool in terraform "${CONTAINER_CLI:-docker}" aws; do
    if ! command -v "$tool" &> /dev/null; then
        echo "❌ $tool is required but not installed"
        exit 1
    fi
done

if ! aws sts get-caller-identity &> /dev/null; then
    echo "❌ AWS credentials not configured"
    exit 1
fi

if [ ! -f terraform/image-uri.txt ]; then
    echo "❌ terraform/image-uri.txt not found. Run ./build-and-push.sh first."
    exit 1
fi

cd terraform

echo "🔧 Initializing Terraform..."
terraform init -input=false

echo "📋 Planning deployment..."
terraform plan -out=tfplan

echo "🚀 Applying..."
terraform apply -auto-approve tfplan

echo ""
echo "✅ Deployment complete!"
echo "  Runtime ARN:   $(terraform output -raw runtime_arn)"
echo ""
echo "🔍 Invoke with: ./invoke.sh"
echo "📊 View traces: CloudWatch console → GenAI Observability → Bedrock AgentCore"
