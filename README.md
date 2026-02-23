# Spring AI AgentCore

A Spring Boot starter that enables existing Spring Boot applications to conform to the Amazon AgentCore Runtime contract with minimal configuration.

## Features

- **Auto-configuration**: Automatically sets up AgentCore endpoints when added as dependency
- **Annotation-based**: Simple `@AgentCoreInvocation` annotation to mark agent methods
- **SSE Streaming**: Server-Sent Events support with `Flux<String>` return types
- **Smart health checks**: Built-in `/ping` endpoint with Spring Boot Actuator integration
- **Async task tracking**: Convenient methods for background task tracking
- **Rate limiting**: Built-in Bucket4j throttling for invocations and ping endpoints
- **AgentCore Memory integration**: Spring AI integration with Amazon AgentCore Memory service
- **Browser automation**: Headless browser tools for web navigation, screenshots, and page interaction
- **Code interpreter**: Execute Python, JavaScript, and TypeScript code in a secure sandbox

## Examples

See the `examples/` directory for complete working examples:

- **`simple-spring-boot-app/`** - Minimal AgentCore agent with async task tracking
- **`spring-ai-browser/`** - Browser automation with screenshots and content extraction
- **`spring-ai-sse-chat-client/`** - SSE streaming with Spring AI and Amazon Bedrock
- **`spring-ai-simple-chat-client/`** - Traditional Spring AI integration (without AgentCore starter)
- **`spring-ai-override-invocations/`** - Custom controller override using marker interfaces
- **`spring-ai-memory-integration/`** - Spring AI ChatMemory integration with Amazon AgentCore Memory service
- **`spring-ai-extended-chat-client/`** - Spring AI chat client with OAuth authentication and per-user memory isolation, deployable to Amazon AgentCore Runtime.

## Quick Start

### 1. Add Dependency

```xml
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>org.springaicommunity</groupId>
            <artifactId>spring-ai-agentcore-bom</artifactId>
            <version>${version}</version>  <!-- Use latest: 1.0.0-RC2, 1.0.0-RC3, etc. -->
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
</dependencies>
```

### 2. Create Agent Method

```java
@Service
public class MyAgentService {

    @AgentCoreInvocation
    public String handleUserPrompt(MyRequest request) {
        return "You said: " + request.prompt;
    }
}
```

### 3. Run Application

The application will automatically expose:
- `POST /invocations` - Agent processing endpoint
- `GET /ping` - Health check endpoint

## Supported Method Signatures

### Basic POJO Method
```java
@AgentCoreInvocation
public MyResponse processRequest(MyRequest request) {
    return new MyResponse("Processed: " + request.prompt());
}

record MyRequest(String prompt) {}
record MyResponse(String message) {}
```

### With AgentCore Context
```java
@AgentCoreInvocation
public MyResponse processWithContext(MyRequest request, AgentCoreContext context) {
    var sessionId = context.getHeader(AgentCoreHeaders.SESSION_ID);
    return new MyResponse("Session " + sessionId + ": " + request.prompt());
}
```

### Map Method (Flexible)
```java
@AgentCoreInvocation
public Map<String, Object> processData(Map<String, Object> data) {
    return Map.of(
        "input", data,
        "response", "Processed: " + data.get("message"),
        "timestamp", System.currentTimeMillis()
    );
}
```

### String Method (text/plain support)
```java
@AgentCoreInvocation
public String handlePrompt(String prompt) {
    return "Response: " + prompt;
}
```

### SSE Streaming with Spring AI
```java
@AgentCoreInvocation
public Flux<String> streamingAgent(String prompt) {
    return chatClient.prompt().user(prompt).stream().content();
}
```

## Configuration

The starter uses fixed configuration per AgentCore contract:
- **Port**: 8080 (required by AgentCore)
- **Endpoints**: `/invocations`, `/ping` (fixed paths)
- **Health Integration**: Automatically integrates with Spring Boot Actuator when available

### Health Monitoring

The `/ping` endpoint provides intelligent health monitoring:

**Without Spring Boot Actuator:**
- Returns static "Healthy" status
- Always responds with HTTP 200

**With Spring Boot Actuator:**
```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-actuator</artifactId>
</dependency>
```
- Integrates with Actuator health checks
- Maps Actuator status to AgentCore format:
    - `UP` → "Healthy" (HTTP 200)
    - `DOWN` → "Unhealthy" (HTTP 503)
    - Other → "Unknown" (HTTP 503)
- Tracks status change timestamps
- Thread-safe concurrent access

### Background Task Tracking

Amazon AgentCore Runtime monitors agent health and may shut down agents that appear idle. When your agent starts long-running background tasks (like file processing, data analysis, or calling other long-running agents), the runtime needs to know the agent is still actively working to avoid premature termination.

The starter includes `AgentCoreTaskTracker` to communicate this state to the runtime:

```java
@AgentCoreInvocation
public String asyncTaskHandling(MyRequest request, AgentCoreContext context) {
    agentCoreTaskTracker.increment();  // Tell runtime: "I'm starting background work"

    CompletableFuture.runAsync(() -> {
        // Long-running background work
    }).thenRun(agentCoreTaskTracker::decrement);  // Tell runtime: "Background work completed"

    return "Task started";
}
```

The '/ping' endpoint will return **HealthyBusy** while the AgentCoreTaskTracker is greater than 0.

**How the Runtime Uses This Information:**
- **"Healthy"**: Agent is ready, no background tasks → Runtime may scale down if idle
- **"HealthyBusy"**: Agent is healthy but actively processing → Runtime keeps agent alive
- **"Unhealthy"**: Agent has issues → Runtime may restart or replace agent

This prevents the runtime from shutting down your agent while it's processing important background work.

No additional configuration is required.

### Rate Limiting

The starter includes built-in rate limiting using Bucket4j to protect against excessive requests. Rate limiting is deactivated by default and will be active only if limits are defined in properties.

**Configuration:**
```properties
# Customize rate limits in requests per minute (optional)
agentcore.throttle.invocations-limit=50
agentcore.throttle.ping-limit=200
```

**Rate Limit Response (429):**
```json
{"error":"Rate limit exceeded"}
```

Rate limits are applied per client IP address and reset every minute.

## API Reference

### POST /invocations

**Request (defined by user):**
```json
{
  "prompt": "Your prompt here"
}
```

**Success Response (200) (defined by user):**
```json
{
  "response": "Agent response",
  "status": "success"
}
```

### GET /ping

**Response (200):**
```json
{
  "status": "Healthy",
  "time_of_last_update": 1697123456
}
```

**Response (503) - When Actuator detects issues:**
```json
{
  "status": "Unhealthy",
  "time_of_last_update": 1697123456
}
```

## Custom Controller Override

The starter provides marker interfaces to override the default auto-configured controllers with custom implementations:

### Override Invocations Controller

Implement `AgentCoreInvocationsHandler` to provide custom `/invocations` endpoint handling:

```java
@RestController
public class CustomInvocationsController implements AgentCoreInvocationsHandler {

    @PostMapping("/invocations")
    public ResponseEntity<?> handleInvocations(@RequestBody String request) {
        // Custom invocation logic
        return ResponseEntity.ok("Custom response");
    }
}
```

### Override Ping Controller

Implement `AgentCorePingHandler` to provide custom `/ping` endpoint handling:

```java
@RestController
public class CustomPingController implements AgentCorePingHandler {

    @GetMapping("/ping")
    public ResponseEntity<?> ping() {
        // Custom health check logic
        return ResponseEntity.ok(Map.of("status", "Custom Healthy"));
    }
}
```

When these marker interfaces are implemented, the corresponding auto-configured controllers are automatically disabled.

See `examples/spring-ai-override-invocations/` for a complete working example.

## AgentCore Memory

The `spring-ai-agentcore-memory` module provides Spring AI ChatMemory integration with Amazon AgentCore Memory service, supporting both Short-Term Memory (STM) and Long-Term Memory (LTM) with 4 consolidation strategies.

### Add Dependency

```xml
<dependency>
    <groupId>org.springaicommunity</groupId>
    <artifactId>spring-ai-agentcore-memory</artifactId>
</dependency>
```

### Short-Term Memory (STM)

STM stores recent conversation history using Spring AI's `ChatMemoryRepository` interface.

**Configuration:**
```yaml
agentcore:
  memory:
    memory-id: ${AGENTCORE_MEMORY_MEMORY_ID}
    total-events-limit: 100  # Context window size
```

**Usage:**
```java
@Service
public class ChatService {

    public ChatService(ChatClient.Builder builder, ChatMemoryRepository memoryRepository) {
        ChatMemory chatMemory = MessageWindowChatMemory.builder()
                .chatMemoryRepository(memoryRepository)
                .build();

        this.chatClient = builder
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
                .build();
    }

    public Flux<String> chat(String userId, String sessionId, String message) {
        return chatClient.prompt()
                .user(message)
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, userId + ":" + sessionId))
                .stream()
                .content();
    }
}
```

### Long-Term Memory (LTM)

Long-term memory (LTM) is derived from short-term memory (STM) and is automatically consolidated by AgentCore through an asynchronous process. For more details, see the [Long-Term Memory documentation](https://docs.aws.amazon.com/bedrock-agentcore/latest/devguide/long-term-memory-long-term.html).

LTM provides persistent knowledge across sessions with 4 consolidation strategies:

| Strategy | Purpose | Retrieval |
|----------|---------|-----------|
| **Semantic** | User facts (e.g., "likes coffee") | Semantic search |
| **User Preference** | Settings (e.g., "dark mode") | Lists all |
| **Summary** | Conversation summaries | Semantic search |
| **Episodic** | Past interactions & reflections | Semantic search |

**Configuration:**
```yaml
agentcore:
  memory:
    memory-id: ${AGENTCORE_MEMORY_MEMORY_ID}
    long-term:
      namespace:
        auto-register: true  # Auto-register namespaces on mismatch (default: false)
      semantic:
        strategy-id: ${AGENTCORE_MEMORY_LONG_TERM_SEMANTIC_STRATEGY_ID}
      user-preference:
        strategy-id: ${AGENTCORE_MEMORY_LONG_TERM_USER_PREFERENCE_STRATEGY_ID}
      summary:
        strategy-id: ${AGENTCORE_MEMORY_LONG_TERM_SUMMARY_STRATEGY_ID}
      episodic:
        strategy-id: ${AGENTCORE_MEMORY_LONG_TERM_EPISODIC_STRATEGY_ID}
```

> **Note:** The `namespace.auto-register` option automatically updates AWS Memory namespaces when they don't match the expected pattern. This is useful during development but should be used with caution in production.

**Usage with STM + LTM:**
```java
@Service
public class ChatService {

    private final ChatClient chatClient;

    public ChatService(ChatClient.Builder builder,
                       AgentCoreMemory agentCoreMemory) {

        this.chatClient = builder
                .defaultAdvisors(agentCoreMemory.advisors)
                .build();
    }

    public Flux<String> chat(String conversationId, String message) {
        return chatClient.prompt()
                .user(message)
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
                .stream()
                .content();
    }
}
```

The `AgentCoreMemory` bean is auto-configured when you set the required properties.

For detailed configuration options and API reference, see [spring-ai-agentcore-memory/README.md](spring-ai-agentcore-memory/README.md).

## Browser Automation

The `spring-ai-agentcore-browser` module provides headless browser tools for web navigation, content extraction, screenshots, and page interaction using Playwright over CDP.

### Add Dependency

```xml
<dependency>
    <groupId>org.springaicommunity</groupId>
    <artifactId>spring-ai-agentcore-browser</artifactId>
</dependency>
```

### Available Tools

| Tool | Description |
|------|-------------|
| `browseUrl` | Navigate to URL and extract page content |
| `takeScreenshot` | Capture screenshot (stored in artifact cache) |
| `clickElement` | Click element by CSS selector |
| `fillForm` | Fill form field by selector |
| `evaluateScript` | Execute JavaScript on page |

### Modes

- **agentcore** (default): Uses AgentCore Browser managed service
- **local**: Uses locally launched Chromium for development

```properties
agentcore.browser.mode=local  # for local development
```

For detailed configuration and usage, see [spring-ai-agentcore-browser/README.md](spring-ai-agentcore-browser/README.md).

## Code Interpreter

The `spring-ai-agentcore-code-interpreter` module provides secure code execution in Python, JavaScript, and TypeScript with automatic file retrieval.

### Add Dependency

```xml
<dependency>
    <groupId>org.springaicommunity</groupId>
    <artifactId>spring-ai-agentcore-code-interpreter</artifactId>
</dependency>
```

### Features

- Execute code in Python, JavaScript, or TypeScript
- Pre-installed libraries: numpy, pandas, matplotlib (Python)
- Automatic file retrieval (charts, CSVs, PDFs)
- Session-scoped artifact storage

For detailed configuration and usage, see [spring-ai-agentcore-code-interpreter/README.md](spring-ai-agentcore-code-interpreter/README.md).

## Artifact Store

Both browser and code interpreter modules use the shared `spring-ai-agentcore-artifact-store` module for session-scoped artifact storage. The store provides:

- Thread-safe multi-session support
- TTL-based automatic cleanup
- Configurable max size per module

```java
// Inject artifact store
@Qualifier("browserArtifactStore") ArtifactStore<GeneratedFile> browserArtifacts
@Qualifier("codeInterpreterArtifactStore") ArtifactStore<GeneratedFile> codeArtifacts

// Store and retrieve artifacts
artifactStore.store(sessionId, generatedFile);
List<GeneratedFile> files = artifactStore.retrieve(sessionId);  // destructive read
```

## Development

See [AGENTS.md](AGENTS.md) for build commands, testing, and project structure.

## License

This project is licensed under the Apache License 2.0.
