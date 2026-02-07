#!/bin/bash
set -e

echo "🚀 Building and pushing Spring AI Extended Chat Client"

# Get AWS account and region
ACCOUNT_ID=$(aws sts get-caller-identity --query Account --output text)
REGION=$(aws configure get region || echo "us-east-1")

# Detect CPU architecture
HOST_ARCH=$(uname -m)
TARGET_ARCH="arm64"

# Normalize host architecture for comparison
if [ "$HOST_ARCH" = "x86_64" ]; then
    HOST_ARCH_NORMALIZED="amd64"
elif [ "$HOST_ARCH" = "aarch64" ]; then
    HOST_ARCH_NORMALIZED="arm64"
else
    HOST_ARCH_NORMALIZED="$HOST_ARCH"
fi

# Set up cross-platform build if needed
if [ "$HOST_ARCH_NORMALIZED" != "$TARGET_ARCH" ]; then
    echo "⚠️  Cross-platform build detected (${HOST_ARCH_NORMALIZED} → ${TARGET_ARCH})"
    if ! docker buildx inspect --bootstrap 2>/dev/null | grep -q "linux/${TARGET_ARCH}"; then
        echo "📦 Installing QEMU for cross-platform builds..."
        docker run --privileged --rm tonistiigi/binfmt --install all
        if [ $? -ne 0 ]; then
            echo "❌ Error: Failed to install QEMU"
            echo "   You can install it manually with:"
            echo "   docker run --privileged --rm tonistiigi/binfmt --install all"
            exit 1
        fi
        echo "✅ QEMU installed successfully"
    else
        echo "✅ QEMU already installed and configured"
    fi
else
    echo "✅ Native ${TARGET_ARCH} build - no emulation needed"
fi

# Generate unique suffix for ECR repository
SUFFIX=$(openssl rand -hex 4)
ECR_REPO_NAME="spring-ai-extended-chat-client-${SUFFIX}"
IMAGE_URI="${ACCOUNT_ID}.dkr.ecr.${REGION}.amazonaws.com/${ECR_REPO_NAME}:latest"

echo "📦 ECR Repository: ${ECR_REPO_NAME}"
echo "🏷️  Image URI: ${IMAGE_URI}"

# Create ECR repository if it doesn't exist
if ! aws ecr describe-repositories --repository-names "${ECR_REPO_NAME}" --region "${REGION}" >/dev/null 2>&1; then
    echo "📦 Creating ECR repository..."
    aws ecr create-repository \
        --repository-name "${ECR_REPO_NAME}" \
        --region "${REGION}" \
        --image-scanning-configuration scanOnPush=true \
        --tags Key=Purpose,Value="Spring AI Extended Chat Client" Key=Environment,Value=dev
fi

# Login to ECR
echo "🔐 Logging into ECR..."
aws ecr get-login-password --region "${REGION}" | docker login --username AWS --password-stdin "${ACCOUNT_ID}.dkr.ecr.${REGION}.amazonaws.com"

# Build the application
echo "🔨 Building Spring Boot application..."
mvn clean package

# Build and tag Docker image
echo "🐳 Building Docker image..."
docker build --platform linux/arm64 -t "${ECR_REPO_NAME}" .
docker tag "${ECR_REPO_NAME}:latest" "${IMAGE_URI}"

# Push image to ECR
echo "📤 Pushing image to ECR..."
docker push "${IMAGE_URI}"

# Save image info for Terraform
echo "${IMAGE_URI}" > terraform/image-uri.txt
echo "${ECR_REPO_NAME}" > terraform/ecr-repo-name.txt

echo "✅ Build and push completed!"
echo "📝 Image URI saved to terraform/image-uri.txt"
echo "📝 ECR repo name saved to terraform/ecr-repo-name.txt"
