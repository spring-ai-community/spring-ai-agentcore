# Spring AI Override Invocations

This example demonstrates how to override `AgentCoreInvocationsController` from the `spring-ai-bedrock-agentcore-runtime-starter` while still benefiting from the ping endpoint functionality.

## Features

- **Custom Controller Override**: Extends `AgentCoreInvocationsController` to provide custom invocation handling
- **Ping Endpoint Reuse**: Leverages the built-in `/ping` endpoint from the starter for health monitoring
- **Spring AI Integration**: Direct integration with ChatClient for AI responses
- **Selective Starter Benefits**: Uses only the ping functionality while customizing the invocations endpoint

## What This Example Shows

The example demonstrates how to:

1. **Override the invocations controller** using marker interface approach
2. **Implement custom request handling** without inheritance constraints
3. **Maintain health monitoring** through the existing ping endpoint
4. **Integrate directly with Spring AI** ChatClient
5. **Zero configuration needed** - just implement the marker interface

```java
@RestController
public class CustomController implements AgentCoreInvocationsHandler {
    
    @PostMapping(value = "/invocations", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> handleJsonInvocation(@RequestBody Object request, @RequestHeader HttpHeaders headers) {
        return chatClient.prompt().user((String) request).stream().content();
    }
}
```

## How It Works

1. **Marker Interface**: `CustomController` implements `AgentCoreInvocationsHandler`
2. **Auto-Discovery**: Spring finds the controller via `@RestController` annotation
3. **Conditional Skip**: `@ConditionalOnMissingBean(AgentCoreInvocationsHandler.class)` prevents auto-configuration
4. **No Configuration**: No need for `@Configuration` classes or manual bean definitions

## API Endpoints

- **Custom invocations**: `POST /invocations` - Custom AI processing with streaming response
- **Health monitoring**: `GET /ping` - Built-in health check from the starter

## Benefits

- **Zero configuration**: Just implement the marker interface and add `@RestController`
- **No inheritance constraints**: Complete freedom in method signatures and mappings
- **Selective feature usage**: Use only the ping endpoint from the starter
- **Full control**: Complete control over request/response handling
- **No annotation constraints**: No need for `@AgentCoreInvocation` annotation
- **Direct Spring AI access**: Direct ChatClient integration without method discovery overhead

## Requirements

- Java 21+
- Spring Boot 3.x
- Spring AI
- Amazon Bedrock access