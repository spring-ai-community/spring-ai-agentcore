# Spring AI Bedrock AgentCore Starter

A Spring Boot starter that enables existing Spring Boot applications to conform to the Amazon Bedrock AgentCore Runtime contract with minimal configuration.

## Features

- **Auto-configuration**: Automatically sets up AgentCore endpoints when added as dependency
- **Annotation-based**: Simple `@AgentCoreInvocation` annotation to mark agent methods
- **SSE Streaming**: Server-Sent Events support with `Flux<String>` return types
- **Smart health checks**: Built-in `/ping` endpoint with Spring Boot Actuator integration
- **Async task tracking**: Convenient methods for background task tracking
- **Rate limiting**: Built-in Bucket4j throttling for invocations and ping endpoints

## Quick Start

### 1. Add Dependency

```xml
<dependency>
    <groupId>org.springaicommunity</groupId>
    <artifactId>spring-ai-bedrock-agentcore-starter</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
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

### Binary Response with byte[]
#### Option A: return byte[] directly, requires to add parameter "--accept" with value "application/octet-stream" in invoke-agent-runtime
```java
@AgentCoreInvocation
public byte[] generateBinaryData(MyRequest request) {
  return ("Binary response for: " + request.prompt()).getBytes();
}
```

#### Option B: wrap byte[] into ResponseEntity
```java
@AgentCoreInvocation
public ResponseEntity<byte[]> generateBinaryData(MyRequest request) {
    return ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_OCTET_STREAM)
            .body(("Binary response for: " + request.prompt()).getBytes());
}
```

### Binary Streaming with InputStream
#### Option A: return InputStreamResource directly, requires to add parameter "--accept" with value "application/octet-stream" in invoke-agent-runtime
```java
@AgentCoreInvocation
public InputStreamResource streamBinaryData(String prompt) {
  InputStream stream = new ByteArrayInputStream(("Stream: " + prompt).getBytes());
  return new InputStreamResource(stream);
}
```

#### Option B: wrap InputStreamResource into ResponseEntity
```java
@AgentCoreInvocation
public ResponseEntity<InputStreamResource> streamBinaryData(String prompt) {
    InputStream stream = new ByteArrayInputStream(("Stream: " + prompt).getBytes());
    return ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_OCTET_STREAM)
            .body(new InputStreamResource(stream));
}
```

### SSE Streaming with Spring AI
```java
@AgentCoreInvocation
public Flux<String> streamingAgent(String prompt) {
    return chatClient.prompt().user(prompt).stream().content();
}
```

### Reactor Dependency Note

The starter includes `reactor-core` as a dependency to properly handle newline characters in SSE streaming responses. This ensures markdown tables, code blocks, and other formatted content render correctly in streaming clients.

If you're using Spring MVC without any reactive features, you can exclude the reactor dependency:

```xml
<dependency>
    <groupId>org.springaicommunity</groupId>
    <artifactId>spring-ai-bedrock-agentcore-starter</artifactId>
    <version>1.0.0-SNAPSHOT</version>
    <exclusions>
        <exclusion>
            <groupId>io.projectreactor</groupId>
            <artifactId>reactor-core</artifactId>
        </exclusion>
    </exclusions>
</dependency>
```

> **Note:** In a future release, the starter will be split into separate `web-mvc` and `web-flux` modules for cleaner dependency management.

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

Amazon Bedrock AgentCore Runtime monitors agent health and may shut down agents that appear idle. When your agent starts long-running background tasks (like file processing, data analysis, or calling other long-running agents), the runtime needs to know the agent is still actively working to avoid premature termination.

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

## Examples

See the `examples/` directory for complete working examples:

- **`simple-spring-boot-app/`** - Minimal AgentCore agent with async task tracking
- **`spring-ai-sse-chat-client/`** - SSE streaming with Spring AI and Amazon Bedrock
- **`spring-ai-simple-chat-client/`** - Traditional Spring AI integration (without AgentCore starter)

## Requirements

- Java 17+
- Spring Boot 3.x
- Maven or Gradle

## License

This project is licensed under the Apache License 2.0.