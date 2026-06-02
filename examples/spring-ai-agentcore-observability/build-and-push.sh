#!/bin/bash
set -e

echo "🚀 Building and pushing Spring AI AgentCore Observability example"

# Container runtime (docker by default; set CONTAINER_CLI=finch to use Finch).
CONTAINER_CLI="${CONTAINER_CLI:-docker}"

ACCOUNT_ID=$(aws sts get-caller-identity --query Account --output text)
REGION=$(aws configure get region || echo "us-east-1")

SUFFIX=$(openssl rand -hex 4)
ECR_REPO_NAME="spring-ai-agentcore-observability-${SUFFIX}"
IMAGE_URI="${ACCOUNT_ID}.dkr.ecr.${REGION}.amazonaws.com/${ECR_REPO_NAME}:latest"

echo "📦 ECR Repository: ${ECR_REPO_NAME}"
echo "🏷️  Image URI: ${IMAGE_URI}"
echo "🧰 Container CLI: ${CONTAINER_CLI}"

if ! aws ecr describe-repositories --repository-names "${ECR_REPO_NAME}" --region "${REGION}" >/dev/null 2>&1; then
    echo "📦 Creating ECR repository..."
    aws ecr create-repository \
        --repository-name "${ECR_REPO_NAME}" \
        --region "${REGION}" \
        --image-scanning-configuration scanOnPush=true >/dev/null
fi

echo "🔐 Logging into ECR..."
aws ecr get-login-password --region "${REGION}" | "${CONTAINER_CLI}" login --username AWS --password-stdin "${ACCOUNT_ID}.dkr.ecr.${REGION}.amazonaws.com"

echo "🔨 Building Spring Boot application..."
mvn -q clean package

echo "🐳 Building container image (linux/arm64)..."
"${CONTAINER_CLI}" build --platform linux/arm64 -t "${ECR_REPO_NAME}" .
"${CONTAINER_CLI}" tag "${ECR_REPO_NAME}:latest" "${IMAGE_URI}"

echo "📤 Pushing image to ECR..."
"${CONTAINER_CLI}" push "${IMAGE_URI}"

mkdir -p terraform
echo "${IMAGE_URI}" > terraform/image-uri.txt
echo "${ECR_REPO_NAME}" > terraform/ecr-repo-name.txt

echo "✅ Build and push completed. Image URI saved to terraform/image-uri.txt"
