# Spring AI Bedrock AgentCore Memory

Spring AI ChatMemory integration with Amazon Bedrock AgentCore Memory service.

For quick start and usage examples, see the [main README](../README.md#agentcore-memory).

## Features

- **Spring AI Integration**: Implements `ChatMemoryRepository` interface
- **Auto-configuration**: Zero-configuration setup with Spring Boot
- **Short-Term Memory**: Conversation history with `MessageWindowChatMemory`
- **Long-Term Memory**: 4 consolidation strategies (Semantic, User Preference, Summary, Episodic)

## Memory Types

### Short-Term Memory (STM)
- Implements `ChatMemoryRepository` interface for conversation history
- Works with `MessageWindowChatMemory` for sliding window conversations

### Long-Term Memory (LTM)
- **Semantic**: Semantic search for user facts using the current query
- **User Preference**: Lists ALL stored preferences regardless of query — preferences should always apply
- **Summary**: Semantic search for conversation summaries by session
- **Episodic**: Semantic search for past interactions and reflections

### Advisor Execution Order

LTM advisors run **before** STM advisor (lower order = earlier execution):

| Order | Advisor | Target | Purpose |
|-------|---------|--------|---------|
| 100 | Semantic | System prompt | Add relevant facts |
| 101 | User Preference | System prompt | Add preferences |
| 102 | Summary | User prompt | Augment query with context |
| 103 | Episodic | System prompt | Add past interactions |
| 1000+ | STM (MessageChatMemoryAdvisor) | Messages | Add conversation history |

**Why LTM before STM?** LTM enriches the prompt with persistent knowledge (facts, preferences) before STM adds recent conversation history. This ensures the model has full context: who the user is (LTM) + what was just discussed (STM).

### System Prompt vs User Prompt

| Memory Type | Target | Reason |
|-------------|--------|--------|
| Semantic | System | Stable context about user, cacheable |
| User Preference | System | Stable settings, cacheable |
| Episodic | System | Background context, cacheable |
| Summary | User | Query-specific augmentation, varies per request |

**Prompt Caching Benefits**: Facts, preferences, and episodic memories go to the system prompt because they're relatively stable across requests. With Bedrock's prompt caching (`cache-options.strategy: SYSTEM_AND_TOOLS`), the system prompt is cached and reused, reducing latency and cost. Only summaries augment the user prompt since they're query-specific.

## Configuration Reference

### STM Configuration

```yaml
agentcore:
  memory:
    memory-id: your-memory-id                    # Required: AgentCore Memory ID
    total-events-limit: 100                      # Optional: Max events to retrieve (context window)
    default-session: default-session             # Optional: Default session name
    page-size: 50                               # Optional: API pagination size
    ignore-unknown-roles: false                 # Optional: Handle unknown message roles
```

### LTM Configuration

```yaml
agentcore:
  memory:
    long-term:
      semantic:
        strategy-id: ${SEMANTIC_STRATEGY_ID}     # Enables strategy (omit to disable)
        top-k: 3                                 # Default: 3
        scope: ACTOR                             # Default: ACTOR
      user-preference:
        strategy-id: ${USER_PREFERENCE_STRATEGY_ID}  # Enables strategy (no top-k: lists all)
        scope: ACTOR                             # Default: ACTOR
      summary:
        strategy-id: ${SUMMARY_STRATEGY_ID}      # Enables strategy
        top-k: 3                                 # Default: 3
        scope: SESSION                           # Default: SESSION
      episodic:
        strategy-id: ${EPISODIC_STRATEGY_ID}     # Enables strategy
        reflections-strategy-id: ${REFLECTIONS_STRATEGY_ID}  # Optional: enables reflections
        episodes-top-k: 3                        # Default: 3
        reflections-top-k: 2                     # Default: 2
        scope: ACTOR                             # Default: ACTOR
```

#### Scope Options

| Scope | Namespace Pattern | Use Case |
|-------|-------------------|----------|
| `ACTOR` | `/strategies/{memoryStrategyId}/actors/{actorId}` | Search across all sessions for the user |
| `SESSION` | `/strategies/{memoryStrategyId}/actors/{actorId}/sessions/{sessionId}` | Search only current session |

#### Defaults Summary

| Strategy | top-k | scope |
|----------|-------|-------|
| semantic | 3 | ACTOR |
| user-preference | n/a (lists all) | ACTOR |
| summary | 3 | SESSION |
| episodic | episodes: 3, reflections: 2 | ACTOR |

Set `enabled: true` to activate LTM, then configure individual strategies. Each strategy is optional - only configure the ones you need. Advisors are auto-created for configured strategies. Set `enabled: false` to temporarily disable all LTM without removing strategy configuration.

## Conversation ID Format

The repository supports flexible conversation ID formats:

- **Simple**: `user123` → actor: `user123`, session: `default-session`
- **With Session**: `user123:session456` → actor: `user123`, session: `session456`

## Error Handling

```yaml
agentcore:
  memory:
    ignore-unknown-roles: true   # Log warning for unsupported message types
```

All AWS SDK exceptions are wrapped in `AgentCoreMemoryException`.

## API Reference

### ChatMemoryRepository

```java
List<Message> findByConversationId(String conversationId);
void saveAll(String conversationId, List<Message> messages);
void deleteByConversationId(String conversationId);
```

### Configuration Properties

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `agentcore.memory.memory-id` | String | null | AgentCore Memory ID (required) |
| `agentcore.memory.total-events-limit` | Integer | null | Context window size |
| `agentcore.memory.default-session` | String | "default-session" | Default session |
| `agentcore.memory.page-size` | Integer | 100 | API pagination size |
| `agentcore.memory.ignore-unknown-roles` | Boolean | false | Handle unknown roles |

### Supported Message Types

| Spring AI Message | AgentCore Role |
|-------------------|----------------|
| `UserMessage`     | `USER` ✅      |
| `AssistantMessage`| `ASSISTANT` ✅ |
| `SystemMessage`   | Filtered ⚠️    |
| `ToolResponseMessage` | Filtered ⚠️ |

## Performance

- **Page Size**: Adjust `page-size` based on typical conversation length
- **Total Limit**: Use `total-events-limit` to control context window size
- **Logging**: Set `org.springaicommunity.agentcore.memory: DEBUG` for detailed logs

## Troubleshooting

1. **Memory ID not found**: Verify `AGENTCORE_MEMORY_MEMORY_ID` environment variable

2. **AWS Permissions**: Required:
   - `bedrock-agentcore:ListEvents`
   - `bedrock-agentcore:CreateEvent`
   - `bedrock-agentcore:DeleteEvent`
   - `bedrock-agentcore:RetrieveMemoryRecords` (for LTM)

3. **Debug logging**:
   ```yaml
   logging:
     level:
       org.springaicommunity.agentcore.memory: DEBUG
   ```

## Requirements

- Java 17+
- Spring Boot 3.x
- Spring AI 1.1.1+

## Testing

See [DEV.md](../DEV.md) for testing instructions.

## License

Apache License 2.0
