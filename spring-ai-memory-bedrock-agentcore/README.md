# Spring AI Bedrock AgentCore Memory Repository

A Spring Boot starter that provides seamless integration between Spring AI and Amazon Bedrock AgentCore Memory for persistent conversation storage.

## Features

- **Spring AI Integration**: Implements `ChatMemoryRepository` interface
- **Auto-configuration**: Zero-configuration setup with Spring Boot
- **Pagination Support**: Efficient handling of large conversation histories
- **Configurable Limits**: Control memory usage and retrieval behavior
- **Error Handling**: Robust error handling with configurable unknown role behavior
- **Logging**: Comprehensive debug and error logging
- **Production Ready**: Input validation, memory optimization, and proper exception handling

## Memory Types

This starter implements both **short-term** and **long-term** memory using AWS Bedrock AgentCore Memory.

### Short-Term Memory
- Implements `ChatMemoryRepository` interface for conversation history
- Works with `MessageWindowChatMemory` for sliding window conversations

### Long-Term Memory
- **Semantic Facts**: Semantic search for user facts (e.g., "User likes coffee")
- **User Preferences**: List all stored preferences (e.g., "Dark mode enabled")
- **Summaries**: Search conversation summaries by session
- **Episodic**: Search past interactions and reflections

### Advisor Execution Order

LTM advisors run **before** STM advisor (lower order = earlier execution):

| Order | Advisor | Target | Purpose |
|-------|---------|--------|---------|
| 100 | Semantic Facts | System prompt | Add relevant facts |
| 101 | User Preferences | System prompt | Add preferences |
| 102 | Summaries | User prompt | Augment query with context |
| 103 | Episodic | System prompt | Add past interactions |
| 1000+ | STM (MessageChatMemoryAdvisor) | Messages | Add conversation history |

**Why LTM before STM?** LTM enriches the prompt with persistent knowledge (facts, preferences) before STM adds recent conversation history. This ensures the model has full context: who the user is (LTM) + what was just discussed (STM).

### System Prompt vs User Prompt

| Memory Type | Target | Reason |
|-------------|--------|--------|
| Semantic Facts | System | Stable context about user, cacheable |
| User Preferences | System | Stable settings, cacheable |
| Episodic | System | Background context, cacheable |
| Summaries | User | Query-specific augmentation, varies per request |

**Prompt Caching Benefits**: Facts, preferences, and episodic memories go to the system prompt because they're relatively stable across requests. With Bedrock's prompt caching (`cache-options.strategy: SYSTEM_AND_TOOLS`), the system prompt is cached and reused, reducing latency and cost. Only summaries augment the user prompt since they're query-specific.

## Quick Start

### 1. Add Dependency

```xml
<dependency>
    <groupId>org.springaicommunity</groupId>
    <artifactId>spring-ai-memory-bedrock-agentcore</artifactId>
    <version>1.0.0-RC2</version>
</dependency>
```

### 2. Configure Memory ID

```yaml
agentcore:
  memory:
    memory-id: your-agentcore-memory-id
```

### 3. Use with Spring AI

```java
@Service
public class ChatService {

    private final ChatClient chatClient;
    private final ChatMemory chatMemory;

    public ChatService(ChatClient.Builder chatClientBuilder, ChatMemoryRepository memoryRepository) {
        this.chatClient = chatClientBuilder.build();
        this.chatMemory = MessageWindowChatMemory.builder()
            .chatMemoryRepository(memoryRepository)
            .maxMessages(10)
            .build();
    }

    public String chat(String conversationId, String message) {
        return chatClient.prompt()
            .user(message)
            .advisors(chatMemory.getChatMemoryAdvisor(conversationId))
            .call()
            .content();
    }
}
```

## Configuration

### Basic Configuration

```yaml
agentcore:
  memory:
    memory-id: your-memory-id                    # Required: AgentCore Memory ID
    total-events-limit: 100                      # Optional: Max events to retrieve
    default-session: default-session             # Optional: Default session name
    page-size: 50                               # Optional: API pagination size
    ignore-unknown-roles: false                 # Optional: Handle unknown message roles
```

### Advanced Configuration

```yaml
agentcore:
  memory:
    memory-id: ${AGENTCORE_MEMORY_ID}
    total-events-limit: 500
    default-session: main
    page-size: 100
    ignore-unknown-roles: true

logging:
  level:
    org.springaicommunity.agentcore.memory: DEBUG
```

### Long-Term Memory Configuration

Configure LTM strategies by providing strategy IDs from your AgentCore Memory setup:

```yaml
agentcore:
  memory:
    memory-id: ${AGENTCORE_MEMORY_ID}

    # Long-term memory strategies
    long-term:
      semantic-facts:
        strategy-id: ${SEMANTIC_STRATEGY_ID}  # Semantic memory strategy
        top-k: 3                               # Number of facts to retrieve
      user-preferences:
        strategy-id: ${PREFERENCE_STRATEGY_ID} # User preference strategy
      summary:
        strategy-id: ${SUMMARY_STRATEGY_ID}    # Summary strategy
        top-k: 3
      episodic:
        strategy-id: ${EPISODIC_STRATEGY_ID}   # Episodic memory strategy
        episodes-top-k: 3                       # Number of episodes
        reflections-top-k: 2                    # Number of reflections
```

Each strategy is optional - only configure the ones you need. Advisors are auto-created for configured strategies.

### Using Long-Term Memory

LTM advisors require user ID (and optionally session ID) to be passed via advisor params:

```java
@Service
public class ChatService {

    private final ChatClient chatClient;

    public Flux<String> chat(String userId, String sessionId, String message) {
        return chatClient.prompt()
            .user(message)
            .advisors(a -> a
                .param(AgentCoreLongMemoryAdvisor.USER_ID_PARAM, userId)
                .param(AgentCoreLongMemoryAdvisor.SESSION_ID_PARAM, sessionId))  // Required for summaries
            .stream()
            .content();
    }
}
```

## Conversation ID Format

The repository supports flexible conversation ID formats:

- **Simple**: `user123` → actor: `user123`, session: `default-session`
- **With Session**: `user123:session456` → actor: `user123`, session: `session456`

## Memory Management

### Pagination

The repository automatically handles pagination for large conversation histories:

```java
// Retrieves all events across multiple pages
List<Message> messages = memoryRepository.findByConversationId("user123");

// With total events limit
agentcore.memory.total-events-limit=50  // Only retrieve first 50 events
```

### Memory Optimization

- **Efficient pagination**: Uses configurable page sizes
- **Smart limits**: Stops early when total limit is reached
- **Memory-efficient**: Avoids unnecessary object creation

## Error Handling

### Input Validation

```java
// Throws IllegalArgumentException for invalid inputs
memoryRepository.findByConversationId(null);        // ❌ Null conversation ID
memoryRepository.findByConversationId("");          // ❌ Empty conversation ID
memoryRepository.saveAll("conv1", null);            // ❌ Null messages
```

### Unknown Role Handling

```yaml
agentcore:
  memory:
    ignore-unknown-roles: true   # Log warning and continue
    # ignore-unknown-roles: false  # Throw exception (default)
```

### AWS SDK Errors

All AWS SDK exceptions are wrapped in `AgentCoreMemoryException` with meaningful messages:

```java
try {
    List<Message> messages = memoryRepository.findByConversationId("user123");
} catch (AgentCoreMemoryException e) {
    log.error("Failed to retrieve conversation: {}", e.getMessage(), e);
}
```

## Supported Message Types

### Spring AI to AgentCore Mapping

| Spring AI Message | AgentCore Role | Supported |
|-------------------|----------------|-----------|
| `UserMessage`     | `USER`         | ✅        |
| `AssistantMessage`| `ASSISTANT`    | ✅        |
| `SystemMessage`   | N/A            | ⚠️ Filtered/Exception |
| `ToolResponseMessage` | N/A        | ⚠️ Filtered/Exception |

**Note**: AgentCore currently only supports USER and ASSISTANT roles. Other message types are either filtered out (if `ignore-unknown-roles=true`) or cause exceptions.

## API Reference

### ChatMemoryRepository Methods

```java
public interface ChatMemoryRepository {

    // Retrieve conversation history
    List<Message> findByConversationId(String conversationId);

    // Save messages to conversation
    void saveAll(String conversationId, List<Message> messages);

    // Delete entire conversation
    void deleteByConversationId(String conversationId);

    // Not supported - throws UnsupportedOperationException
    List<String> findConversationIds();
}
```

### Configuration Properties

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `agentcore.memory.memory-id` | String | null | AgentCore Memory ID (required) |
| `agentcore.memory.total-events-limit` | Integer | null | Max events to retrieve (unlimited if null) |
| `agentcore.memory.default-session` | String | "default-session" | Default session name |
| `agentcore.memory.page-size` | Integer | 100 | API pagination page size |
| `agentcore.memory.ignore-unknown-roles` | Boolean | false | Handle unknown message roles gracefully |

## Integration Examples

### With MessageWindowChatMemory

```java
@Configuration
public class ChatConfig {

    @Bean
    public ChatMemory chatMemory(ChatMemoryRepository memoryRepository) {
        return MessageWindowChatMemory.builder()
            .chatMemoryRepository(memoryRepository)
            .maxMessages(20)  // Keep last 20 messages in memory
            .build();
    }
}
```

### With Custom Memory Strategy

```java
@Service
public class ConversationService {

    private final ChatMemoryRepository memoryRepository;

    public void archiveOldConversations() {
        // Custom logic to manage conversation lifecycle
        List<Message> messages = memoryRepository.findByConversationId("user123");
        if (messages.size() > 100) {
            // Archive or summarize old messages
            memoryRepository.deleteByConversationId("user123");
        }
    }
}
```

## Performance Considerations

### Pagination Optimization

- **Page Size**: Adjust `page-size` based on your typical conversation length
- **Total Limit**: Use `total-events-limit` to prevent memory issues with very long conversations
- **Early Termination**: Repository stops fetching when limit is reached

### Memory Usage

```yaml
# For high-volume applications
agentcore:
  memory:
    page-size: 50              # Smaller pages for better memory usage
    total-events-limit: 200    # Limit conversation history

# For comprehensive history
agentcore:
  memory:
    page-size: 100             # Larger pages for fewer API calls
    total-events-limit: null   # No limit (retrieve all)
```

## Monitoring and Observability

### Logging

```yaml
logging:
  level:
    org.springaicommunity.agentcore.memory: DEBUG  # Detailed operation logs
    software.amazon.awssdk: INFO                   # AWS SDK logs
```

### Metrics

The repository logs key metrics:
- Number of events retrieved per conversation
- API call performance
- Error rates and types

## Troubleshooting

### Common Issues

1. **Memory ID not found**
   ```
   Solution: Verify AGENTCORE_MEMORY_ID environment variable or configuration
   ```

2. **AWS Permissions**
   ```
   Required permissions:
   - bedrock-agentcore:ListEvents
   - bedrock-agentcore:CreateEvent
   - bedrock-agentcore:DeleteEvent
   ```

3. **Unknown Role Errors**
   ```yaml
   # Enable graceful handling
   agentcore:
     memory:
       ignore-unknown-roles: true
   ```

4. **Large Conversation Performance**
   ```yaml
   # Optimize for large conversations
   agentcore:
     memory:
       total-events-limit: 100
       page-size: 50
   ```

### Debug Mode

Enable comprehensive logging:

```yaml
logging:
  level:
    org.springaicommunity.agentcore.memory: DEBUG
    org.springframework.ai: DEBUG
```

## Requirements

- **Java**: 17+
- **Spring Boot**: 3.x
- **AWS SDK**: 2.40.3+
- **Spring AI**: 1.1.1+

## AWS Permissions

Required IAM permissions for the application:

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": [
        "bedrock-agentcore:ListEvents",
        "bedrock-agentcore:CreateEvent",
        "bedrock-agentcore:DeleteEvent"
      ],
      "Resource": "arn:aws:bedrock-agentcore:*:*:memory/*"
    }
  ]
}
```

## Testing

### Unit Tests
Run unit tests (excludes integration tests by default):
```bash
mvn test
```

### Integration Tests
Integration tests require AWS credentials and create real AgentCore Memory resources.

Run integration tests only:
```bash
mvn test -Pintegration
```

**Note:** Integration tests may take 2-3 minutes and will create/delete AWS resources.

## License

This project is licensed under the Apache License 2.0.
