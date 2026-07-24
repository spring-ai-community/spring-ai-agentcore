# Spring AI AgentCore Memory

Spring AI ChatMemory integration with Amazon AgentCore Memory service.

For quick start and usage examples, see the [main README](../README.md#agentcore-memory).

## Features

- **Spring AI Integration**: Implements `ChatMemoryRepository` interface
- **Auto-configuration**: Zero-configuration setup with Spring Boot
- **Short-Term Memory**: Conversation history with `MessageWindowChatMemory`
- **Long-Term Memory**: 4 consolidation strategies (Semantic, User Preference, Summary, Episodic)
- **Session API (incubating)**: Optional Spring AI Session API bean stack (opt-in via `agentcore.memory.session.enabled=true`)

## Session API (spring-ai-session, incubating)

Since 2.1.0 the module ships an opt-in bean stack backed by the community
`org.springaicommunity:spring-ai-session-management` artifact. When enabled, four beans
are added to the context: `AgentCoreSessionRepository` (implements
`org.springframework.ai.session.SessionRepository`), `DefaultSessionService`,
`SessionMemoryAdvisor`, and `AgentCoreSessionMemory` (bundles the session advisor with
any configured long-term memory advisors). The existing ChatMemory stack is unaffected;
both stacks can coexist and are wired independently.

Enable it with:

```yaml
agentcore:
  memory:
    memory-id: your-memory-id
    session:
      enabled: true
      default-user-id: default-user   # optional
      persist-synthetic: false        # optional; synthetic events are skipped by default
```

**Required dependency.** The memory module declares `spring-ai-session-management` as an
`optional` dependency, so consumers on the Session API path add it to their own
`pom.xml`. If the artifact is missing while `agentcore.memory.session.enabled=true` is
set, the module logs a startup WARN and creates no session beans.

```xml
<dependency>
    <groupId>org.springaicommunity</groupId>
    <artifactId>spring-ai-session-management</artifactId>
</dependency>
```

The snippet omits a `<version>` because the version is expected to come from the
`spring-ai-session-bom`. This project already imports that BOM at the version pinned by
the `spring-ai-session.version` property in the root `pom.xml`; standalone consumers should
import the BOM in their own `dependencyManagement` and let it manage the version rather
than pinning it inline:

```xml
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>org.springaicommunity</groupId>
            <artifactId>spring-ai-session-bom</artifactId>
            <version>${spring-ai-session.version}</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>
```

**Usage.** `SessionMemoryAdvisor.SESSION_ID_CONTEXT_KEY` equals `ChatMemory.CONVERSATION_ID`,
so the same conversation-id constant works for both stacks:

```java
chatClient.prompt()
    .user("Hi")
    .advisors(a -> a
        .param(SessionMemoryAdvisor.SESSION_ID_CONTEXT_KEY, "alice:conv-1")
        .param(SessionMemoryAdvisor.USER_ID_CONTEXT_KEY, "alice"))
    .call()
    .content();
```

**User id and session id.** `AgentCoreSessionRepository` has no session-metadata store,
so it derives `Session.userId` from the actor (userId) segment of the sessionId (parsed
by `AgentCoreMemoryConversationIdParser`). The sessionId format is therefore
`"userId:sessionSuffix"` (for example `"alice:conv-1"`, where `alice` is the userId).
When you set `USER_ID_CONTEXT_KEY` per-request, pass the same value as the userId prefix.
On the second turn `SessionMemoryAdvisor.before()` runs an ownership check that compares
`USER_ID_CONTEXT_KEY` against the derived `Session.userId`; a mismatch throws
`IllegalStateException("...does not belong to user...")`. The usual way to hit this is
reusing a sessionId while changing the advisor's user id. If you never set
`USER_ID_CONTEXT_KEY`, the check passes.

**replaceEvents: non-destructive branch-swap (opt-in) or legacy delete-then-recreate.**
AgentCore has no server-side transactional replace or compare-and-swap on the event log.
Two strategies are selected by `agentcore.memory.session.branch-swap-enabled` (default
`false`).

- **Branch-swap (opt-in, `true`).** `replaceEvents` writes the full replacement timeline
  to a fresh branch named `gen-<counter>-<8hex>`, then makes that branch current by writing
  a small pointer marker on the main line carrying `agentcore.currentBranch`, `agentcore.gen`,
  and `agentcore.pointer=true`. Discovery is highest-generation-wins over the pointer ledger
  (ties broken deterministically by lexicographic branch name, not list position). It is
  non-destructive: a failed replacement leaves the old branch current, so readers never see a
  partial timeline. It is NOT a CAS: concurrent replacers each write an isolated branch and no
  events are lost between replacers, but a whole replacement can be silently superseded by a
  concurrent higher-generation one, and a concurrent `appendEvent` can land on a branch that is
  immediately superseded (silently invisible to later reads). Migration is one-way per session;
  prove it out in a non-production environment first. Ledger compaction reaps each superseded
  generation's branch events as its marker is compacted (coupled so no branch outlives its
  marker), so the steady state is roughly one live branch.
- **Legacy (default, `false`).** For a true v1 session (no pointer markers), `replaceEvents`
  performs the non-atomic delete-then-recreate: it deletes the existing event log and then
  recreates it in separate AgentCore calls. If a `createEvent` fails after the delete phase,
  the original events are gone and the log is left partial and unrecoverable by the repository
  (logged at ERROR, not retryable). On a session that was already migrated to branch mode, the
  flag-off path REFUSES with a `StorageException` and migrate-back guidance rather than
  destroying the ledger; it issues zero AWS writes.

For strict single-writer needs under either strategy, hold an external lock (for example a
DynamoDB conditional write or Redis SETNX) covering `appendEvent` and both `replaceEvents`
variants per sessionId. The clientToken idempotency applies within AgentCore's clientToken
retention window.

**Known limitations.** AgentCore imposes several behaviors that differ from the
`SessionRepository` SPI. All are documented in Javadoc on `AgentCoreSessionRepository`:

| Method / field | Behavior | Caller impact |
|----------------|----------|---------------|
| `save(Session)` | no-op (no session-metadata store) | Metadata mutated on the `Session` (e.g. `session.withMetadata(...)`) is not persisted and will not reappear on `findById`. Do not use `save` for metadata persistence. |
| `findByUserId(String)` | maps `userId` to the AgentCore actor and paginates `ListSessions` | Returns compound ids `"userId:sessionId"` that round-trip through the other methods; `createdAt` from each `SessionSummary`, falling back to the `Instant.EPOCH` sentinel when the summary has none (the same fallback documented on the `Session.createdAt` row); unknown user yields an empty list. |
| `findExpiredSessionIds(Instant)` | throws `UnsupportedOperationException` | Expiry is memory-level retention (`eventExpiryDuration`), not re-derivable per session; use `findByUserId(userId)` to enumerate a user's sessions. |
| `replaceEvents(String, List)` | non-destructive branch-swap when enabled; legacy delete-then-recreate otherwise (refuses on a migrated session when disabled) | Branch-swap leaves the prior timeline intact; legacy risks partial data on mid-flight failure. Hold an external lock (see above). |
| `replaceEvents(String, List, long)` | best-effort check-then-act, not a true CAS | Race window; hold an external lock (see above). |
| `appendEvent(SessionEvent)` | does not throw when session is unknown | First append implicitly creates the session server-side. |
| `Session.createdAt` | real instant from `SessionSummary`, falling back to the tail event timestamp | Only when neither exists does it fall back to the `Instant.EPOCH` sentinel; the last-event timestamp is also exposed under metadata key `agentcore.lastEventAt`. |
| `Session.expiresAt` | `null` | TTL is managed on the memory resource itself. |

**Branch-swap rollback / migrate-back.** Branch-swap migration is one-way per session: once
a session has a pointer marker, its live timeline is on a `gen-*` branch. To roll back to the
legacy main line, do it in two phases. First, with `agentcore.memory.session.branch-swap-enabled=true`,
read the current branch of each affected session with `findEvents(sessionId, EventFilter.all())`.
Then re-write those events onto a fresh session id that has never been branched via a plain
`appendEvent` loop; because a never-branched session has no pointer markers, the append lands on
the main line regardless of the flag value. Do NOT simply flip the flag off and call
`replaceEvents` on a migrated session: that path deliberately refuses with a `StorageException`
(and issues zero AWS writes) precisely so it never runs the destructive main-line delete
against a live branch. Superseded branches and stale markers are reaped by the memory-level
`eventExpiryDuration`; lower that duration as a backstop for high replace rates.

**Deprecation notice.** The ChatMemory-facing beans (`chatMemoryRepository`, `chatMemory`,
`AgentCoreMemory.shortTermMemoryAdvisor`) and the
`AgentCoreShortTermMemoryRepository implements ChatMemoryRepository` declaration are
marked `@Deprecated(since = "2.1.0", forRemoval = true)` and are scheduled for removal in
3.0.0. Migrate to the Session API stack (`agentcore.memory.session.enabled=true`) before
upgrading to the next major. They remain fully supported in the 2.x line.
See [issue #152](https://github.com/spring-ai-community/spring-ai-agentcore/issues/152).

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
    memory-id: your-memory-id                    # Required: AgentCore Memory ID (shared with LTM)
    short-term:
      total-events-limit: 100                    # Optional: Max events to retrieve (context window)
      default-session: default-session           # Optional: Default session name
      page-size: 50                              # Optional: API pagination size
```

> **Migration (1.1.0):** STM-only properties moved from `agentcore.memory.*` to
> `agentcore.memory.short-term.*` for consistency with `agentcore.memory.long-term.*`.
> The old keys still work in 1.1.x but log a deprecation warning at startup and will
> be removed in a future release. See
> [issue #49](https://github.com/spring-ai-community/spring-ai-agentcore/issues/49).
>
> The default of `ignore-unknown-roles` has changed from `false` to `true`. Spring AI
> 2.0.0-M7+ runs tool execution at the advisor layer, which routes `ToolResponseMessage`
> through `ChatMemory.add(...)`. With the previous default the repository threw
> `IllegalStateException: Unsupported message type` on any tool-using turn. The new
> default skips non-dialogue messages (`TOOL`, `OTHER`) instead, which is the only
> sensible behaviour for M7+ agents — tool results are point-in-time facts that
> should not be persisted into conversation history. The property itself
> (`ignore-unknown-roles`) is now deprecated and will be removed in a future major:
> skipping non-dialogue messages becomes hardcoded behaviour. The misnomer
> ("unknown roles" — `TOOL`/`OTHER` are first-class AgentCore roles) and the lack of
> a useful `false` mode mean the toggle has no production value. See
> [issue #109](https://github.com/spring-ai-community/spring-ai-agentcore/issues/109).

### LTM Configuration

There are two ways to configure Long-Term Memory:

#### Option 1: Autodiscovery (Recommended)

Automatically discover all strategies from your AgentCore Memory:

```yaml
agentcore:
  memory:
    memory-id: ${MEMORY_ID}
    long-term:
      auto-discovery: true                        # Discovers strategies from AWS
```

**Autodiscovery behavior:**
- Queries AWS to discover all strategies configured in your memory
- Creates advisors only for supported types: `SEMANTIC`, `SUMMARIZATION`, `USER_PREFERENCE`, `EPISODIC`
- Skips `CUSTOM` strategy types (not supported by autodiscovery)
- Uses the first namespace if a strategy has multiple namespaces
- Uses default `topK` values for each strategy type

**Overriding discovered defaults:**

You can override specific settings for discovered strategies by providing explicit configuration. The explicit config is applied only when the `strategy-id` matches the discovered one:

```yaml
agentcore:
  memory:
    memory-id: ${MEMORY_ID}
    long-term:
      auto-discovery: true
      semantic:
        strategy-id: discovered-semantic-id      # Must match discovered ID
        top-k: 5                                  # Override default topK
        namespace-pattern: /custom/namespace     # Override namespace (must exist in AWS)
      summary:
        strategy-id: discovered-summary-id
        top-k: 3
```

**Override rules:**
- `strategy-id` must match the discovered strategy ID for overrides to apply
- If `strategy-id` doesn't match, the explicit config is ignored
- `namespace-pattern` must match one of the namespaces discovered from AWS, or use `auto-register=true`

**Namespace auto-registration:**

If you want to use a namespace that doesn't exist in AWS yet:

```yaml
agentcore:
  memory:
    long-term:
      auto-discovery: true
      namespace:
        auto-register: true                      # Register new namespaces in AWS
      semantic:
        strategy-id: discovered-semantic-id
        namespace-pattern: /new/custom/namespace # Will be registered in AWS
```

#### Option 2: Explicit Configuration

Manually specify each strategy:

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

#### Custom Namespace Patterns

You can override the default namespace patterns with custom ones using the `namespace-pattern` property:

```yaml
agentcore:
  memory:
    long-term:
      summary:
        strategy-id: ${SUMMARY_STRATEGY_ID}
        namespace-pattern: custom-namespace/strategies/{memoryStrategyId}/actors/{actorId}/sessions/{sessionId}
```

**Important**: The custom namespace pattern must match the namespace configured in your AgentCore Memory strategy. At startup, the library validates that the configured pattern matches what's in AWS. If there's a mismatch, the application will fail to start with a clear error message.

Available placeholders:
- `{memoryStrategyId}` - The strategy ID
- `{actorId}` - The user/actor ID  
- `{sessionId}` - The session ID (required for session-scoped patterns)

**Note**: Only these predefined placeholders are supported. Custom placeholders are not allowed.

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

Messages with non-dialogue roles (e.g. `ToolResponseMessage`, system messages) are skipped with a `WARN` log line rather than persisted, since they are point-in-time facts that should not be replayed from conversation history. This is the only behaviour in 1.1.x and will be hardcoded in the next major.

The legacy `agentcore.memory[.short-term].ignore-unknown-roles` property is **deprecated** in 1.1.0 (`@Deprecated(since = "1.1.0", forRemoval = true)`); setting it logs a deprecation warning at startup and will be removed in the next major. See [issue #109](https://github.com/spring-ai-community/spring-ai-agentcore/issues/109).

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
| `agentcore.memory.memory-id` | String | null | AgentCore Memory ID (required, shared with LTM) |
| `agentcore.memory.short-term.total-events-limit` | Integer | null | Context window size |
| `agentcore.memory.short-term.default-session` | String | "default-session" | Default session |
| `agentcore.memory.short-term.page-size` | Integer | 100 | API pagination size |
| `agentcore.memory.short-term.ignore-unknown-roles` | Boolean | true | **Deprecated** (since 1.1.0, for removal). Skipping non-dialogue messages will become hardcoded — see [#109](https://github.com/spring-ai-community/spring-ai-agentcore/issues/109) |

### Supported Message Types

| Spring AI Message | AgentCore Role |
|-------------------|----------------|
| `UserMessage`     | `USER` ✅      |
| `AssistantMessage`| `ASSISTANT` ✅ |
| `SystemMessage`   | Filtered ⚠️    |
| `ToolResponseMessage` | Filtered ⚠️ |

## Performance

- **Page Size**: Adjust `agentcore.memory.short-term.page-size` based on typical conversation length
- **Total Limit**: Use `agentcore.memory.short-term.total-events-limit` to control context window size
- **Logging**: Set `org.springaicommunity.agentcore.memory: DEBUG` for detailed logs

## Troubleshooting

1. **Memory ID not found**: Verify `AGENTCORE_MEMORY_MEMORY_ID` environment variable

2. **AWS Permissions**: Required:
   - `bedrock-agentcore:ListEvents`
   - `bedrock-agentcore:CreateEvent`
   - `bedrock-agentcore:DeleteEvent`
   - `bedrock-agentcore:ListSessions` (for `findByUserId` only; `findById` derives `createdAt` from the event tail and does not call `ListSessions`)
   - `bedrock-agentcore:RetrieveMemoryRecords` (for LTM)

3. **Debug logging**:
   ```yaml
   logging:
     level:
       org.springaicommunity.agentcore.memory: DEBUG
   ```

## Requirements

- Java: this module follows the `java.version` set in the root `pom.xml`
- Spring Boot 4.x
- Spring AI 2.0.0+

## Testing

See the [Build &amp; Test](../AGENTS.md#build--test) and [Integration Test Environment Variables](../AGENTS.md#integration-test-environment-variables) sections in `AGENTS.md` for testing instructions.

## License

Apache License 2.0
