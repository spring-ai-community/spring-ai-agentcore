# Spring AI Bedrock AgentCore Examples

This directory contains example Spring Boot applications and Terraform deployment configurations for Amazon Bedrock AgentCore.

## Quick Start

### 1. Build and Push Application

```bash
cd terraform
# Interactive selection of example app
./build-and-push.sh

# Or specify directly
./build-and-push.sh simple-spring-boot-app
```

### 2. Deploy Infrastructure

```bash
terraform init
terraform apply
```

This creates **both** IAM and OAuth2 authenticated AgentCore runtimes in a single deployment.

### 3. Test Both Authentication Methods

**IAM Authentication:**
```bash
./invoke-iam.sh
./invoke-iam.sh "custom-session-id-123456789012345678901" "What is Spring AI?"
```
_Note_: When invoking annotated methods with a String argument, make sure to update the contentType in `aws bedrock-agentcore` command to `text/plain` instead of `application/json`.

**OAuth2 Authentication:**
```bash
./invoke-oauth.sh
./invoke-oauth.sh myuser MyPassword123! user@example.com "What is Spring AI?"
```
_Note_: When invoking annotated methods with a String argument, make sure to update the contentType in `aws bedrock-agentcore` command to `text/plain` instead of `application/json`.

## Example Applications

### simple-spring-boot-app
Basic AgentCore integration with minimal configuration. Demonstrates:
- `@AgentCoreInvocation` annotation
- Simple request/response handling
- Health checks and task tracking

### spring-ai-sse-chat-client
Server-Sent Events streaming with Spring AI integration. Features:
- `Flux<String>` streaming responses
- Spring AI ChatClient integration
- Real-time response streaming

### spring-ai-simple-chat-client
Traditional Spring AI integration without streaming. Shows:
- Standard Spring AI usage patterns
- Synchronous response handling
- Basic chat functionality

## Infrastructure Details

The Terraform deployment creates:

### Shared Resources
- **ECR Repository**: `springaiagentjava` (shared by both runtimes)
- **Image Versioning**: Automatic version tracking via `image-version.txt`

### IAM Stack (`SpringAiAgentJavaIam`)
- **Authentication**: AWS IAM credentials
- **Invocation**: AWS CLI with SigV4 signing
- **Headers**: `X-Amzn-Bedrock-AgentCore-Runtime-Custom-Test`

### OAuth2 Stack (`SpringAiAgentJavaOauth`)
- **Authentication**: Cognito User Pool with JWT tokens
- **Invocation**: Public HTTPS endpoint with Bearer tokens
- **Headers**: `Authorization`, `X-Amzn-Bedrock-AgentCore-Runtime-Custom-Test`
- **User Management**: Automatic user creation and token handling

## Configuration

Edit `terraform/terraform.tfvars`:
```hcl
region   = "us-east-1"
app_name = "SpringAiAgentJava"
```

## Deployment Workflow

1. **Initial setup**: 
   - `./build-and-push.sh` (creates ECR repo and pushes image)
   - `terraform init && terraform apply` (creates runtimes)
2. **Development cycle**:
   - Modify application code
   - Run `./build-and-push.sh` (builds, pushes, updates version)
   - Run `terraform apply` (detects version change, updates runtimes)
   - Test with `./invoke-iam.sh` or `./invoke-oauth.sh`

## Authentication Comparison

| Feature | IAM | OAuth2 |
|---------|-----|--------|
| **Setup** | AWS credentials | Cognito user creation |
| **Invocation** | AWS CLI | HTTPS + Bearer token |
| **Security** | SigV4 signing | JWT validation |
| **Use Case** | Internal/service-to-service | External/user-facing |
| **Endpoint** | AWS SDK | Public HTTPS |

## Troubleshooting

### Common Issues

**"No agent runtime found"**
- Run `terraform apply` first to create infrastructure

**"Invalid session ID"**
- Session IDs must be 33+ characters long
- Scripts auto-generate valid session IDs

**"OAuth authorization failed"**
- Ensure user exists: `./invoke-oauth.sh` creates users automatically
- Check Cognito configuration in Terraform outputs

**"Image not found"**
- Run `./build-and-push.sh` to build and push container image
- Verify ECR repository exists and image is pushed

### Useful Commands

```bash
# Check Terraform outputs
terraform output

# View runtime status
aws bedrock-agentcore-control list-agent-runtimes --region us-east-1

# Check ECR images
aws ecr list-images --repository-name springaiagentjava --region us-east-1

# View Cognito users (OAuth2)
aws cognito-idp list-users --user-pool-id $(terraform output -raw cognito_user_pool_id)
```

## Next Steps

- Modify example applications in their respective directories
- Customize IAM permissions in `terraform/main.tf`
- Add additional Cognito configuration for OAuth2 flows
- Integrate with your existing Spring Boot applications using the starter dependency
