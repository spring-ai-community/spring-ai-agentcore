# Spring AI AgentCore SDK

📖 **[Documentation](https://springaicommunity.mintlify.app/projects/incubating/spring-ai-bedrock-agentcore)**

An open-source library that brings Amazon Bedrock AgentCore capabilities into Spring AI through familiar patterns: annotations, auto-configuration, and composable advisors.

## Modules

| Module | Description |
|--------|-------------|
| [Runtime Starter](spring-ai-agentcore-runtime-starter/) | Auto-configures `/invocations` and `/ping` endpoints, SSE streaming, health checks, rate limiting |
| [Memory](spring-ai-agentcore-memory/) | Short-term (conversation history) and long-term memory (semantic, preferences, summaries, episodic) |
| [Browser](spring-ai-agentcore-browser/) | Web navigation, content extraction, screenshots, form interaction via Playwright |
| [Code Interpreter](spring-ai-agentcore-code-interpreter/) | Secure Python/JavaScript/TypeScript execution with file retrieval |
| [Artifact Store](spring-ai-agentcore-artifact-store/) | Session-scoped, TTL-based storage for generated files |
| [BOM](spring-ai-agentcore-bom/) | Bill of Materials for version alignment |

## Quick Start

Add the BOM and the modules you need:

```xml
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>org.springaicommunity</groupId>
            <artifactId>spring-ai-agentcore-bom</artifactId>
            <version>1.0.0</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>

<dependencies>
    <dependency>
        <groupId>org.springaicommunity</groupId>
        <artifactId>spring-ai-agentcore-runtime-starter</artifactId>
    </dependency>
    <!-- Add as needed -->
    <dependency>
        <groupId>org.springaicommunity</groupId>
        <artifactId>spring-ai-agentcore-memory</artifactId>
    </dependency>
    <dependency>
        <groupId>org.springaicommunity</groupId>
        <artifactId>spring-ai-agentcore-browser</artifactId>
    </dependency>
    <dependency>
        <groupId>org.springaicommunity</groupId>
        <artifactId>spring-ai-agentcore-code-interpreter</artifactId>
    </dependency>
</dependencies>
```

Create an agent with memory, browser, and code interpreter:

```java
@Service
public class MyAgent {

    private final ChatClient chatClient;
    private final AgentCoreMemory agentCoreMemory;

    public MyAgent(
            ChatClient.Builder builder,
            AgentCoreMemory agentCoreMemory,
            @Qualifier("browserToolCallbackProvider") ToolCallbackProvider browserTools,
            @Qualifier("codeInterpreterToolCallbackProvider") ToolCallbackProvider codeInterpreterTools) {
        this.agentCoreMemory = agentCoreMemory;
        this.chatClient = builder
                .defaultToolCallbacks(browserTools, codeInterpreterTools)
                .build();
    }

    @AgentCoreInvocation
    public Flux<String> chat(PromptRequest request, AgentCoreContext context) {
        String sessionId = context.getHeader(AgentCoreHeaders.SESSION_ID);

        return chatClient.prompt()
                .user(request.prompt())
                .advisors(agentCoreMemory.advisors)
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, "user:" + sessionId))
                .stream()
                .content();
    }
}

record PromptRequest(String prompt) {}
```

This gives you a production-ready agent with streaming, conversation memory, web browsing, and code execution — deployed to AgentCore Runtime or standalone.

## Deployment

The SDK supports two deployment models:

- **AgentCore Runtime** — Fully managed: package as ARM64 container, push to ECR, create a runtime. Scales to zero, pay-per-use.
- **Standalone** — Use any module independently on EKS, ECS, EC2, or on-premises.

See [examples/terraform/](examples/terraform/) for infrastructure-as-code with IAM and OAuth2 authentication.

## Examples

| Example | Description |
|---------|-------------|
| [simple-spring-boot-app](examples/simple-spring-boot-app/) | Minimal agent with request handling |
| [spring-ai-sse-chat-client](examples/spring-ai-sse-chat-client/) | Streaming responses with SSE |
| [spring-ai-memory-integration](examples/spring-ai-memory-integration/) | Short-term and long-term memory |
| [spring-ai-extended-chat-client](examples/spring-ai-extended-chat-client/) | OAuth auth with per-user memory isolation |
| [spring-ai-browser](examples/spring-ai-browser/) | Web browsing and screenshots |
| [spring-ai-simple-chat-client](examples/spring-ai-simple-chat-client/) | Traditional Spring AI (without runtime starter) |
| [spring-ai-override-invocations](examples/spring-ai-override-invocations/) | Custom controller override |

## Requirements

- Java 17+ (Java 25 recommended)
- Spring Boot 3.5+
- An AWS account

## Development

```bash
mvn clean install              # Build
mvn test                       # Unit tests
mvn spring-javaformat:apply    # Format (required before commit)
```

### Building the examples

The [`examples/`](examples/) tree is a separate multi-module Maven build that depends on
the AgentCore modules. Run `mvn -DskipTests install` at the repo root first so the
examples can resolve the `1.1.0-SNAPSHOT` artifacts locally, then:

```bash
mvn clean verify -f examples/pom.xml
```

Each module also has an [AGENTS.md](AGENTS.md) file providing context for AI coding assistants (project structure, conventions, key classes).

## License

Apache License 2.0
