# Spring AI AgentCore Memory Example

A complete example demonstrating Amazon AgentCore Short-Term & Long-Term Memory integration with Spring AI for persistent conversation history.

This example creates and uses the AgentCore Long-Term Memory Summary Strategy.

## Prerequisites

- Java 17+
- Maven 3.6+
- AWS credentials configured locally

## Architecture

```
  User          ChatClient       STM Advisor      LTM Advisor      AgentCore       LLM
   │                │                │                │                │            │
   │  prompt        │                │                │                │            │
   │───────────────>│                │                │                │            │
   │                │                │                │                │            │
   │                │  1. get history│                │                │            │
   │                │───────────────>│                │                │            │
   │                │                │  listEvents    │                │            │
   │                │                │────────────────────────────────>│            │
   │                │                │<────────────────────────────────│            │
   │                │<───────────────│  messages      │                │            │
   │                │                │                │                │            │
   │                │  2. retrieve LTM                │                │            │
   │                │────────────────────────────────>│                │            │
   │                │                │                │  searchMemories│            │
   │                │                │                │───────────────>│            │
   │                │                │                │<───────────────│            │
   │                │<────────────────────────────────│  memories      │            │
   │                │                │                │                │            │
   │                │  3. invoke (history + LTM + prompt)              │            │
   │                │──────────────────────────────────────────────────────────────>│
   │                │<──────────────────────────────────────────────────────────────│
   │                │                │                │                │  response  │
   │                │                │                │                │            │
   │                │  4. save messages               │                │            │
   │                │───────────────>│                │                │            │
   │                │                │  putEvents     │                │            │
   │                │                │────────────────────────────────>│            │
   │                │                │<────────────────────────────────│            │
   │                │                │                │                │            │
   │<───────────────│  response      │                │                │            │
   │                │                │                │                │            │
```

## Quick Start

### Local Setup

1. Setup your local AWS credentials / auth

1. Create the AgentCore Memory:
    ```bash
    mvn spring-boot:test-run
    ```
1. Export the `AGENTCORE_MEMORY_ID` and `SUMMARY_STRATEGY_ID` env vars
1. Start the Spring web application
    ```bash
    mvn spring-boot:run
    ```
1. Test the application:
    ```bash
    # --- Short-Term Memory (STM) ---
    # Tell your name
    curl -X POST http://localhost:8080/api/short \
        -H "Content-Type: application/json" \
        -d '{"message": "My name is Andrei"}'
    
    # Ask for your name (memory recall)
    curl -X POST http://localhost:8080/api/short \
        -H "Content-Type: application/json" \
        -d '{"message": "What is my name?"}'

    # --- Long-Term Memory (LTM) ---
    # Ask something to be persisted in LTM
    curl -X POST http://localhost:8080/api/long \
        -H "Content-Type: application/json" \
        -d '{"message": "I love hiking in the Alps"}'

    # Get conversation history
    curl http://localhost:8080/api/history
 
    # Get stored LTM memories
    curl http://localhost:8080/api/memories

    # Clear conversation
    curl -X DELETE http://localhost:8080/api/history
    ```

## Cleanup

With the `AGENTCORE_MEMORY_ID` env var set, run:
```bash
mvn spring-boot:test-run
```

## Custom Namespace Pattern

This example demonstrates using a custom namespace pattern for the Summary strategy. 

In `application.properties`:
```properties
agentcore.memory.long-term.summary.namespace-pattern=custom-namespace/strategies/{memoryStrategyId}/actors/{actorId}/sessions/{sessionId}
```

The same pattern is configured in `SetupTeardown.java` when creating the memory strategy:
```java
.namespaces(List.of("custom-namespace/strategies/{memoryStrategyId}/actors/{actorId}/sessions/{sessionId}"))
```

**Important**: The namespace pattern in your Spring configuration must match the namespace configured in your AgentCore Memory strategy. The library validates this at startup and will fail with a clear error if there's a mismatch.

### Auto-Registration

If you want the library to automatically update AWS Memory namespaces when they don't match, enable auto-registration:

```properties
agentcore.memory.long-term.namespace.auto-register=true
```

This is useful during development when namespace patterns may change. Use with caution in production.

## Autodiscovery Mode

Instead of manually configuring each strategy, you can use autodiscovery to automatically detect strategies from your AgentCore Memory:

```properties
agentcore.memory.long-term.auto-discovery=true
```

**Autodiscovery behavior:**
- Queries AWS to discover all strategies in your memory
- Creates advisors for supported types: `SEMANTIC`, `SUMMARIZATION`, `USER_PREFERENCE`, `EPISODIC`
- Uses discovered namespace and default `topK` values

**Overriding discovered settings:**

```properties
agentcore.memory.long-term.auto-discovery=true
# Override topK for a discovered strategy (strategy-id must match)
agentcore.memory.long-term.summary.strategy-id=${SUMMARY_STRATEGY_ID}
agentcore.memory.long-term.summary.top-k=5
```

The override only applies if `strategy-id` matches the discovered strategy ID.
