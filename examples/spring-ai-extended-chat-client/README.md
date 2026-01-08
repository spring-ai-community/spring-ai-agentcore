# Spring AI Extended Chat Client

Spring AI chat client with OAuth authentication and per-user memory isolation, deployable to AWS Bedrock AgentCore Runtime.

## Features

- **OAuth Authentication**: JWT Bearer token authentication via AWS Cognito
- **Memory Isolation**: Per-user conversation memory using AgentCore Memory service
- **Spring AI Integration**: Powered by Spring AI ChatClient with Amazon Bedrock
- **AgentCore Deployment**: Containerized deployment with ARM64 support

## Architecture

```
┌─────────────────┐    ┌──────────────────┐    ┌─────────────────┐
│   OAuth Client  │───▶│  AgentCore       │───▶│  Spring AI      │
│   (Alice/Bob)   │    │  Runtime         │    │  Extended       │
│                 │    │  + JWT Auth      │    │  Chat Client    │
└─────────────────┘    │                  │    │                 │
        │              │  ┌─────────────┐ │    │  ┌─────────────┐│
        │              │  │  Memory     │ │◀───│  │  Amazon     ││
        └──────────────┼─▶│  Service    │ │    │  │  Bedrock    ││
          JWT Token    │  │ (Per-User)  │ │    │  └─────────────┘│
                       │  └─────────────┘ │    └─────────────────┘
                       └──────────────────┘
```

## Quick Start

### Prerequisites

- AWS CLI configured with appropriate permissions
- Docker installed and running
- Terraform >= 1.0
- Java 17+ and Maven 3.6+

### Deploy

1. **Build and deploy:**
   ```bash
   ./build-and-push.sh
   ./deploy.sh
   ```

2. **Test OAuth authentication and memory isolation:**
   ```bash
   ./test.sh
   ```

### Manual Testing

After deployment, get the runtime details:

```bash
cd terraform
RUNTIME_NAME=$(terraform output -raw runtime_name)
USER_POOL_ID=$(terraform output -raw cognito_user_pool_id)
CLIENT_ID=$(terraform output -raw cognito_client_id)
```

Create a test user and get OAuth token:

```bash
# Create user
aws cognito-idp admin-create-user \
  --user-pool-id $USER_POOL_ID \
  --username alice \
  --user-attributes Name=email,Value=alice@example.com \
  --temporary-password TempPass123! \
  --message-action SUPPRESS

# Set permanent password
aws cognito-idp admin-set-user-password \
  --user-pool-id $USER_POOL_ID \
  --username alice \
  --password AlicePass123! \
  --permanent

# Get OAuth token
TOKEN=$(aws cognito-idp admin-initiate-auth \
  --user-pool-id $USER_POOL_ID \
  --client-id $CLIENT_ID \
  --auth-flow ADMIN_NO_SRP_AUTH \
  --auth-parameters USERNAME=alice,PASSWORD=AlicePass123! \
  --query 'AuthenticationResult.AccessToken' \
  --output text)
```

Test the runtime:

```bash
curl -X POST \
  "https://bedrock-agentcore.us-east-1.amazonaws.com/runtimes/$RUNTIME_NAME/invocations?qualifier=DEFAULT" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -H "X-Amzn-Bedrock-AgentCore-Runtime-Session-Id: test-session-123" \
  -d '{"prompt": "Hi, I am Alice and I work at AWS"}'
```

## How It Works

### Authentication
- Extracts user identity from JWT tokens using Nimbus JOSE + JWT library
- Each authenticated user gets isolated conversation memory
- Falls back to anonymous user if no valid token provided

### Memory Integration
- Uses AgentCore Memory service for conversation persistence
- Memory is scoped per user: `userId:sessionId`
- Configurable message window size (default: 10 messages)

### Architecture
```
OAuth Client → AgentCore Runtime → Spring AI Chat Client
     ↓              ↓                      ↓
JWT Token → User Identity → Per-User Memory → Amazon Bedrock
```

## Configuration

Key configuration in `application.properties`:

```properties
# Spring AI Bedrock
spring.ai.bedrock.anthropic.chat.model=anthropic.claude-3-sonnet-20240229-v1:0

# Memory window size
agentcore.memory.window-size=10
```

## Monitoring

Check runtime status:
```bash
aws bedrock-agentcore-control get-runtime --runtime-name $RUNTIME_NAME --region us-east-1
```

View logs in AWS CloudWatch under `/aws/bedrock-agentcore/runtimes/` log group.

## Cleanup

Remove all deployed resources:
```bash
./cleanup.sh
```

## Development

### Local Development
```bash
export AGENTCORE_MEMORY_ID=your-memory-id
mvn spring-boot:run
```

### Building
```bash
mvn clean package
docker build --platform linux/arm64 -t spring-ai-extended .
```
