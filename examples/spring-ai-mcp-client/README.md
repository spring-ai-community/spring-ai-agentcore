# Spring AI MCP Client Agent

A Spring Boot application demonstrating integration with Model Context Protocol (MCP) servers. This example connects to the AWS Documentation MCP Server over stdio to provide AI-powered AWS documentation assistance.

## Features

- **MCP Client Integration**: Connects to MCP servers over stdio transport
- **AWS Documentation Tools**: 
  - Search AWS documentation
  - Read full documentation pages
  - Get related documentation recommendations
- **AgentCore Integration**: Uses `@AgentCoreInvocation` for automatic endpoint setup
- **Amazon Bedrock**: Integration with Claude 3 Sonnet model
- **Tool Wrapping**: Wraps MCP server tools as Spring AI tools

## Prerequisites

- Java 21
- Maven
- AWS account with access to Amazon Bedrock
- AWS credentials configured locally
- Python with `uv` and `uvx` installed (for MCP server)

### Installing uv/uvx

The MCP server runs via `uvx`, which requires `uv` to be installed:

**macOS/Linux:**
```bash
curl -LsSf https://astral.sh/uv/install.sh | sh
```

**Or with Homebrew:**
```bash
brew install uv
```

**Or with pip:**
```bash
pip install uv
```

After installation, `uvx` will be available automatically.

## Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                    Spring Boot Application                   │
│                                                               │
│  ┌──────────────┐      ┌─────────────────┐                 │
│  │ ChatClient   │─────▶│ AwsDocsMcpTools │                 │
│  │ (Spring AI)  │      │  (Tool Wrapper) │                 │
│  └──────────────┘      └────────┬────────┘                 │
│                                  │                           │
│                         ┌────────▼────────┐                 │
│                         │ McpStdioClient  │                 │
│                         │  (MCP Client)   │                 │
│                         └────────┬────────┘                 │
└──────────────────────────────────┼──────────────────────────┘
                                   │ stdio
                         ┌─────────▼──────────┐
                         │   uvx process      │
                         │  AWS Docs MCP      │
                         │     Server         │
                         └────────────────────┘
```

## Configuration

The MCP client connects to the AWS Documentation server with these settings:

```java
command: "uvx"
args: ["awslabs.aws-documentation-mcp-server@latest"]
env:
  FASTMCP_LOG_LEVEL: "ERROR"
  AWS_DOCUMENTATION_PARTITION: "aws"
```

## Building and Running

```bash
mvn clean package
java -jar target/mcp-client-agent-0.0.1-SNAPSHOT.jar
```

Or use Maven directly:

```bash
mvn spring-boot:run
```

The application starts on port 8080.

## API Endpoints

### AWS Documentation Query Endpoint

```bash
# Ask about AWS services
curl -X POST http://localhost:8080/invocations \
  -H "Content-Type: application/json" \
  -d '{"prompt":"What is Amazon S3?"}'

# Ask about specific features
curl -X POST http://localhost:8080/invocations \
  -H "Content-Type: application/json" \
  -d '{"prompt":"How do I configure S3 bucket versioning?"}'

# Ask about best practices
curl -X POST http://localhost:8080/invocations \
  -H "Content-Type: application/json" \
  -d '{"prompt":"What are the best practices for Lambda function configuration?"}'

# Compare services
curl -X POST http://localhost:8080/invocations \
  -H "Content-Type: application/json" \
  -d '{"prompt":"What is the difference between ECS and EKS?"}'
```

### Health Endpoints

```bash
# AgentCore health check
curl http://localhost:8080/ping

# Actuator health check
curl http://localhost:8080/actuator/health
```

## How It Works

1. **MCP Client Initialization**: On startup, `McpStdioClient` connects to the AWS Documentation MCP server via stdio
2. **Tool Discovery**: The client lists available tools from the MCP server
3. **Tool Wrapping**: `AwsDocsMcpTools` wraps MCP tools as Spring AI `@Tool` methods
4. **AI Processing**: When a user asks about AWS:
   - The AI model analyzes the query
   - Determines which MCP tool(s) to call
   - Calls the tools via the MCP client
   - Synthesizes a response with documentation

## Available MCP Tools

### search_documentation
Search AWS documentation for specific topics.

**Parameters:**
- `search_phrase` (required): Search query
- `limit` (optional): Max results (default: 5)

### read_documentation
Read full content of an AWS documentation page.

**Parameters:**
- `url` (required): Documentation URL
- `max_length` (optional): Max content length (default: 5000)
- `start_index` (optional): Starting position (default: 0)

### recommend
Get related documentation recommendations.

**Parameters:**
- `url` (required): Documentation URL to get recommendations for

## Example Interactions

**User**: "What is Amazon S3?"
**Agent**: Searches AWS docs, reads relevant pages, provides comprehensive answer with links

**User**: "How do I enable S3 versioning?"
**Agent**: Searches for versioning docs, reads the guide, provides step-by-step instructions

**User**: "What's the difference between ECS and EKS?"
**Agent**: Searches for both services, compares features, provides clear explanation

## Project Structure

```
src/main/java/com/unicorn/mcp/
├── McpClientApplication.java    # Main application
├── McpController.java           # AgentCore controller
├── McpStdioClient.java          # MCP client (stdio transport)
├── AwsDocsMcpTools.java         # Tool wrappers for Spring AI
└── PromptRequest.java           # Request model
```

## Troubleshooting

### MCP Server Connection Issues

If the MCP server fails to start:

1. Verify `uvx` is installed: `uvx --version`
2. Test the MCP server manually:
   ```bash
   uvx awslabs.aws-documentation-mcp-server@latest
   ```
3. Check logs in the application output

### Tool Call Failures

If tools fail to execute:
- Check the MCP server logs (set `FASTMCP_LOG_LEVEL=DEBUG`)
- Verify the tool arguments match the expected schema
- Ensure network connectivity for documentation fetching

## Deployment to AWS

This example can be deployed to Amazon Bedrock AgentCore Runtime. See:

- **[QUICKSTART.md](QUICKSTART.md)** - 5-minute deployment guide
- **[DEPLOYMENT.md](DEPLOYMENT.md)** - Comprehensive deployment documentation

Quick deploy:

```bash
./deploy.sh
```

Or manually:

```bash
cd ../terraform
./build-and-push.sh spring-ai-mcp-client
terraform apply
./invoke-iam.sh "test" "What is Amazon S3?"
```

## Notes

- The MCP server is started automatically by the Java application
- The stdio transport provides reliable communication between Java and Python
- Tool calls are synchronous and may take a few seconds for documentation fetching
- The AI model automatically determines when to use MCP tools vs. its own knowledge
- The container includes Python and uv for running MCP servers
