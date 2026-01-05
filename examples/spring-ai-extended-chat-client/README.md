# Spring AI Extended Chat Client

Spring AI chat client deployable into Bedrock AgentCore and uses authentication context with short-term memory.

## Features

- **AgentCore Runtime Deployment**: Containerized deployment into AWS Bedrock AgentCore Runtime
- **OAuth Authentication**: JWT Bearer token authentication via AWS Cognito
- **Short-term Memory**: Per-user conversation memory using AgentCore Memory service
- **User Identity Extraction**: Extracts user identity from OAuth JWT tokens
- **Memory Isolation**: Each authenticated user has completely separate conversation history
- **Spring AI Integration**: Powered by Spring AI ChatClient with Amazon Bedrock
- **Containerized**: Docker-based deployment with ARM64 support

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
- Java 17+
- Maven 3.6+

### Deploy to AgentCore Runtime

1. **Deploy the runtime:**
   ```bash
   ./deploy.sh
   ```

2. **Test the deployment:**
   ```bash
   ./test.sh
   ```

The deployment script handles everything: building the app, creating the Docker image, pushing to ECR, and deploying to AgentCore Runtime with OAuth authentication.

### Manual Testing with OAuth

Test the deployed runtime using OAuth authentication:

```bash
# Get deployment info
cd terraform
RUNTIME_NAME=$(terraform output -raw runtime_name)
USER_POOL_ID=$(terraform output -raw cognito_user_pool_id)
CLIENT_ID=$(terraform output -raw cognito_client_id)

# Create test user
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

# Test with OAuth authentication
python3 -c "
import requests
import urllib.parse

runtime_name = '$RUNTIME_NAME'
token = '$TOKEN'

url = f'https://bedrock-agentcore.us-east-1.amazonaws.com/runtimes/{urllib.parse.quote(runtime_name, safe=\"\")}/invocations?qualifier=DEFAULT'

headers = {
    'Authorization': f'Bearer {token}',
    'Content-Type': 'application/json',
    'X-Amzn-Bedrock-AgentCore-Runtime-Session-Id': 'test-session-123'
}

response = requests.post(url, headers=headers, json={'prompt': 'Hi, I am Alice and I work at AWS'})
print(response.json())
"
```

## How It Works

### Memory Integration

The application uses AgentCore Memory service for conversation persistence:

```java
@AgentCoreInvocation
public Map<String, Object> chat(Map<String, Object> request, AgentCoreContext context) {
    String sessionId = context.getHeader(AgentCoreHeaders.SESSION_ID);
    String userId = extractUserFromContext(context);
    String conversationId = userId + ":" + sessionId;
    
    // Memory-enabled chat with per-user isolation
    String response = chatClient.prompt()
        .user(prompt)
        .advisors(new MessageChatMemoryAdvisor(chatMemory, conversationId, 10))
        .call()
        .content();
}
```

### Authentication Context

User identity is extracted from AgentCore context headers:

```java
private String extractUserFromContext(AgentCoreContext context) {
    String authHeader = context.getHeader("Authorization");
    if (authHeader != null && authHeader.startsWith("Bearer ")) {
        // Extract user from JWT token
        return parseJwtUsername(authHeader.substring(7));
    }
    return "anonymous";
}
```

### Container Configuration

The application is packaged as a Docker container optimized for AgentCore:

```dockerfile
FROM --platform=linux/arm64 amazoncorretto:21-alpine
WORKDIR /app
COPY target/spring-ai-extended-chat-client-1.0.0-SNAPSHOT.jar app.jar
USER appuser
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
```

## Configuration

### Environment Variables

The runtime is configured with:

- `AGENTCORE_MEMORY_ID`: AgentCore Memory service ID
- `SPRING_PROFILES_ACTIVE`: Spring profile (production)

### Memory Settings

Memory configuration in `application.properties`:

```properties
# AgentCore Memory integration
agentcore.memory.enabled=true
agentcore.memory.window-size=10

# Spring AI Bedrock configuration
spring.ai.bedrock.anthropic.chat.enabled=true
spring.ai.bedrock.anthropic.chat.model=anthropic.claude-3-sonnet-20240229-v1:0
```

## Monitoring

### Runtime Status

Check runtime status:

```bash
aws bedrock-agentcore-control get-runtime \
  --runtime-name $RUNTIME_NAME \
  --region us-east-1
```

### Memory Status

Check memory service status:

```bash
aws bedrock-agentcore-control get-memory \
  --memory-id $MEMORY_ID \
  --region us-east-1
```

### Logs

View runtime logs through AWS CloudWatch or AgentCore console.

## Cleanup

Remove all deployed resources:

```bash
./cleanup.sh
```

This will remove:
- AgentCore Runtime
- AgentCore Memory
- AWS Cognito User Pool and Client
- ECR Repository and images

## Development

### Local Development

For local development without AgentCore:

```bash
# Set memory ID environment variable
export AGENTCORE_MEMORY_ID=your-memory-id

# Run locally
mvn spring-boot:run
```

### Building

Build the application:

```bash
mvn clean package
```

Build Docker image:

```bash
docker build --platform linux/arm64 -t spring-ai-extended .
```

## Requirements

- Java 17+
- Spring Boot 3.x
- AWS Bedrock access
- AgentCore Runtime permissions
- Docker for containerization
