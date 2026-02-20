#!/bin/bash
set -e

# Get configuration from Terraform vars
APP_NAME=$(grep 'app_name' terraform.tfvars | cut -d'"' -f2)
AWS_REGION=$(grep 'region' terraform.tfvars | cut -d'"' -f2)
AWS_ACCOUNT_ID=$(aws sts get-caller-identity --query Account --output text)

# Available examples
VALID_APPS=("simple-spring-boot-app" "spring-ai-sse-chat-client" "spring-ai-simple-chat-client" "spring-ai-extended-chat-client" "spring-ai-override-invocations")

# Interactive selection if no argument provided
if [ $# -eq 0 ]; then
    echo "🚀 Select example to build and push:"
    echo ""
    for i in "${!VALID_APPS[@]}"; do
        echo "  $((i+1)). ${VALID_APPS[i]}"
    done
    echo ""
    read -p "Enter choice (1-${#VALID_APPS[@]}): " choice
    
    if [[ "$choice" =~ ^[1-9]+$ ]] && [ "$choice" -ge 1 ] && [ "$choice" -le "${#VALID_APPS[@]}" ]; then
        EXAMPLE_APP="${VALID_APPS[$((choice-1))]}"
    else
        echo "❌ Invalid choice"
        exit 1
    fi
else
    EXAMPLE_APP="$1"
    if [[ ! " ${VALID_APPS[@]} " =~ " ${EXAMPLE_APP} " ]]; then
        echo "❌ Invalid example app: $EXAMPLE_APP"
        echo "Valid options: ${VALID_APPS[*]}"
        exit 1
    fi
fi

echo "🚀 Building and pushing: $EXAMPLE_APP"

# Detect container runtime
if command -v finch >/dev/null 2>&1; then
    RUNTIME="finch"
elif command -v docker >/dev/null 2>&1; then
    RUNTIME="docker"
else
    echo "❌ Neither Docker nor Finch found"
    exit 1
fi

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
    if ! $RUNTIME buildx inspect --bootstrap 2>/dev/null | grep -q "linux/${TARGET_ARCH}"; then
        echo "📦 Installing QEMU for cross-platform builds..."
        $RUNTIME run --privileged --rm tonistiigi/binfmt --install all
        if [ $? -ne 0 ]; then
            echo "❌ Error: Failed to install QEMU"
            echo "   You can install it manually with:"
            echo "   $RUNTIME run --privileged --rm tonistiigi/binfmt --install all"
            exit 1
        fi
        echo "✅ QEMU installed successfully"
    else
        echo "✅ QEMU already installed and configured"
    fi
else
    echo "✅ Native ${TARGET_ARCH} build - no emulation needed"
fi

# ECR repository name (lowercase)
ECR_REPO_NAME=$(echo "$APP_NAME" | tr '[:upper:]' '[:lower:]')
ECR_URL="$AWS_ACCOUNT_ID.dkr.ecr.$AWS_REGION.amazonaws.com/$ECR_REPO_NAME"

echo "📦 Creating ECR repository if needed..."
aws ecr create-repository --repository-name "$ECR_REPO_NAME" --region "$AWS_REGION" 2>/dev/null || echo "Repository already exists"

echo "🔨 Building application..."
# Check if we're in the monorepo (starter source available)
if [ -f "../../pom.xml" ] && grep -q "spring-ai-agentcore-runtime-starter" "../../pom.xml"; then
    echo "📦 Building starter from source..."
    cd ../.. && mvn clean install -DskipTests -q && cd examples/terraform
fi

cd "../$EXAMPLE_APP" && mvn clean package -DskipTests -q && cd ../terraform

echo "🐳 Building container image..."
$RUNTIME build --platform linux/arm64 -t temp-image "../$EXAMPLE_APP"

echo "🚀 Pushing to ECR..."
aws ecr get-login-password --region "$AWS_REGION" | \
    $RUNTIME login --username AWS --password-stdin "$ECR_URL"

VERSION="v$(date +%Y%m%d-%H%M%S)"
$RUNTIME tag temp-image:latest "$ECR_URL:$VERSION"
$RUNTIME tag temp-image:latest "$ECR_URL:latest"

$RUNTIME push "$ECR_URL:$VERSION"
$RUNTIME push "$ECR_URL:latest"

# Save version to file for Terraform
echo "$VERSION" > image-version.txt

echo "✅ Image pushed successfully!"
echo "📦 Image: $ECR_URL:$VERSION"
echo "📦 Latest: $ECR_URL:latest"
echo "💾 Version saved to: image-version.txt"
echo ""
echo "🚀 Now run: terraform apply"
