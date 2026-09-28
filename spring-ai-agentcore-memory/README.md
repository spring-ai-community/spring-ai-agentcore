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

Since 2.2.0 the module ships an opt-in bean stack backed by the community
`org.springaicommunity:spring-ai-session` artifact (0.8.x). When enabled, four beans
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
```

The remaining `agentcore.memory.session.*` properties (`total-events-limit`, `page-size`,
`ignore-unknown-roles`, `default-session`) are optional session-scoped overrides; when
unset they fall back to the `agentcore.memory.short-term.*` (then legacy) values.

**Required dependency.** The memory module declares `spring-ai-session` as an
`optional` dependency, so consumers on the Session API path add it to their own
`pom.xml`. If the artifact is missing while `agentcore.memory.session.enabled=true` is
set, the module logs a startup WARN and creates no session beans.

```xml
<dependency>
    <groupId>org.springaicommunity</groupId>
    <artifactId>spring-ai-session</artifactId>
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

**Upgrading from spring-ai-session-management.** This release moves from
`org.springaicommunity:spring-ai-session-management` 0.5.0 to
`org.springaicommunity:spring-ai-session` 0.8.0. The artifact was renamed upstream in
0.6.0 but still ships the `org.springframework.ai.session` packages, so the two jars
cannot be mixed. To upgrade:

1. Import `spring-ai-session-bom` 0.8.0, replace the `spring-ai-session-management`
   dependency with `spring-ai-session`, and remove every old declaration, including
   transitive ones (check with `mvn dependency:tree`).
2. Update code that calls `AgentCoreSessionRepository` directly. `findById` returns
   `null` instead of `Optional.empty()` for a session without events, and
   `replaceEvents` is gone (0.8.0 replaces it with `compactEvents`, which this repository
   rejects).
3. Keep compaction off, as before. On a session whose turns persisted nothing (blank,
   tool-only or unknown-role messages), a configured compaction now fails with
   `IllegalArgumentException("Session not found: ...")` before it reaches the
   configuration guidance.
4. Note the new advisor order. The auto-configured `SessionMemoryAdvisor` now runs at
   `Ordered.HIGHEST_PRECEDENCE + 200` instead of the upstream default
   `HIGHEST_PRECEDENCE + 1000`, so it wraps the tool loop (see **Tool calling** below).
   Without tools the prompt only changes if you have advisors ordered between those two
   values. With tools, each call now stores only the user message and the final answer;
   before, it also stored any text the model sent with a tool call.
   A `SessionMemoryAdvisor` you build yourself keeps the upstream default unless you set
   `.order(...)`.

The `AgentCoreSessionRepository` constructor checks the classpath once. It fails with an
`IllegalStateException` that names the fix when `spring-ai-session-management`, two
different `spring-ai-session` versions, or a Session API that does not match 0.8.x is
present, instead of failing on the first request with `NoSuchMethodError` or
`AbstractMethodError`. A `spring-ai-session` version outside 0.8.x whose API still matches
only logs a WARN, because pre-1.0 minor releases have changed the SPI every time so far.

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

**Security.** The sessionId/conversationId, and therefore the derived `Session.userId`,
is client-supplied input. The advisor's ownership check compares two values derived from
that same client string, so it does not by itself stop a hostile caller from reading
another user's session. Where an authenticated principal exists, the application must
derive the conversationId's actor segment from the principal, never from unvalidated
request input. Deriving only the `SessionMemoryAdvisor.USER_ID_CONTEXT_KEY` value from the
principal is not sufficient: the advisor's ownership check runs only when the target
session already exists, so a first write to a fresh sessionId passes regardless of the
context key.

**Reads and writes.** The event log is append-only: `appendEvent` and `delete` are the
only write paths, synthetic events (framework generated, for example compaction summaries)
are never persisted, and `compactEvents` and `getEventVersion` throw
`UnsupportedOperationException` (see the table below). To bound the context sent to
the model, give the advisor a read window instead of compaction and let AgentCore
long-term memory extraction carry older facts. Define your own `SessionMemoryAdvisor`
bean, which replaces the auto-configured one, and keep the module's order on it:

```java
@Bean
SessionMemoryAdvisor sessionMemoryAdvisor(SessionService sessionService) {
    return SessionMemoryAdvisor.builder(sessionService)
        .eventFilter(EventFilter.lastN(20))
        .order(AgentCoreSessionRepositoryAutoConfiguration.SESSION_MEMORY_ADVISOR_ORDER)
        .build();
}
```

`total-events-limit` is a coarser cap. It applies to every read without `lastN`,
including keyword and pattern searches and `CrossSessionRecallTools`, which then see only
the newest events of each session and silently miss older matches. Do not configure a
`compactionTrigger` or `compactionStrategy` on `SessionMemoryAdvisor` with this
repository: the compaction runs after the turn has been persisted and the model has
answered, and then fails. `findEvents` pushes `EventFilter.branch()` down to AgentCore,
applies every other `EventFilter` predicate (time range, message types, `keyword`,
`keywords`/`matchMode`, `pattern`, `excludeArchived`) client-side, and stops paginating
early for plain `lastN` queries. Keyword and pattern searches without `lastN` read the
whole session log (up to `total-events-limit`), and `CrossSessionRecallTools` repeats
that for every session of the user after a `ListSessions` scan, which also needs the
`bedrock-agentcore:ListSessions` IAM permission.

**Tool calling.** This repository stores message text only, so tool calls and tool
results are never persisted or read back, and `SessionMemoryAdvisor` has to wrap the tool
loop instead of running inside it. When a request has tools, the `ChatClient` registers a
`ToolCallingAdvisor` at `Ordered.HIGHEST_PRECEDENCE + 300`. If a memory advisor is ordered
after it, the `ChatClient` switches off the tool advisor's own conversation history and
leaves each tool round to the memory advisor. At the upstream default order
(`HIGHEST_PRECEDENCE + 1000`) every tool round would then be rebuilt from AgentCore, and
the model would receive a tool result without the tool call that produced it, which chat
APIs such as Bedrock Converse reject. Any text the model sent along with the tool call
would also be stored as an extra assistant turn. The auto-configured advisor therefore
uses `AgentCoreSessionRepositoryAutoConfiguration.SESSION_MEMORY_ADVISOR_ORDER`
(`HIGHEST_PRECEDENCE + 200`). It loads the history and stores the user message and the
final answer once per call, while the tool exchange stays in the prompt for the rounds of
that call only. Any `SessionMemoryAdvisor` you build yourself, as a bean or inline, needs
the same `.order(...)`, and so does a `ToolCallingAdvisor` you register explicitly: keep
the session advisor's order below it.

**Known limitations.** AgentCore imposes several behaviors that differ from the
`SessionRepository` SPI. All are documented in Javadoc on `AgentCoreSessionRepository`:

| Method / field | Behavior | Caller impact |
|----------------|----------|---------------|
| `save(Session)` | no-op (no session-metadata store) | Metadata mutated on the `Session` (e.g. `session.withMetadata(...)`) is not persisted and will not reappear on `findById`. Do not use `save` for metadata persistence. |
| `findByUserId(String)` | maps `userId` to the AgentCore actor and paginates `ListSessions` | Returns compound ids `"userId:sessionId"` that round-trip through the other methods; `createdAt` from each `SessionSummary`, falling back to the `Instant.EPOCH` sentinel when the summary has none (the same fallback documented on the `Session.createdAt` row); unknown user yields an empty list. |
| `findExpiredSessionIds(Instant)` | throws `UnsupportedOperationException` | Expiry is memory-level retention (`eventExpiryDuration`), not re-derivable per session; use `findByUserId(userId)` to enumerate a user's sessions. |
| `findById(String)` | returns `null` when the session has no events | AgentCore has no notion of an empty session; the first `appendEvent` creates it. |
| `compactEvents(String, List, List, long)` | throws `UnsupportedOperationException` | AgentCore events are immutable (nothing can be marked archived in place) and the log has no CAS, so the `expectedVersion` check cannot be made atomic; bound context via read-windowing (`EventFilter.lastN` on the advisor, or `totalEventsLimit`) and long-term memory extraction instead. Every event read back reports `isArchived() == false`. |
| `getEventVersion(String)` | throws `UnsupportedOperationException` | Its only SPI purpose is supplying the `expectedVersion` for `compactEvents`; a count would suggest an optimistic-lock capability the backend does not have. `DefaultSessionService.compact` calls it first, so a configured compaction fails here. |
| `appendEvent(SessionEvent)` | does not throw when session is unknown | First append implicitly creates the session server-side. |
| `appendEvent(SessionEvent)` | idempotent by `SessionEvent.getId()`, best effort, expected rather than verified | The id feeds a deterministic CreateEvent `clientToken`. Per the CreateEvent API reference, AgentCore should then ignore a retry with the same id (for example with `IdempotentSessionEventIdGenerator`); the reference does not say what happens when the retry carries a different timestamp, and only the live `AgentCoreSessionRepositoryIT` checks it. If AgentCore rejects such a retry, it fails with `StorageException` and `IdempotentSessionEventIdGenerator` is unsafe with this repository. Unlike the SPI reference, AgentCore remembers tokens only for an undocumented window: a replay after it would be stored again, a re-append after `delete` inside it would be dropped, and with `IdempotentSessionEventIdGenerator` a user who repeats the same text in one session would be dropped inside it. The advisor's default random ids never collide. |
| events read back (`findEvents`, advisor history) | ids derived from the AgentCore `eventId`, `null` branch, text only | No tool calls, media or custom metadata come back (only `agentcore.eventId`). Re-appending a loaded event with a rebuilt `Message` is not deduplicated. Branch reads rely on the server-side `EventFilter.forBranch` filter. |
| `total-events-limit` | caps every read without `EventFilter.lastN` to the newest N events | Keyword and pattern searches and `CrossSessionRecallTools` silently miss older matches; bound the advisor's context with `EventFilter.lastN` instead. |
| `Session.createdAt` | `findByUserId`: real instant from each `SessionSummary`; `findById`: the tail (most recent) event timestamp, without calling `ListSessions` | Either path falls back to the `Instant.EPOCH` sentinel when its source carries no timestamp; the last-event timestamp is also exposed under metadata key `agentcore.lastEventAt`. |
| `Session.expiresAt` | `null` | TTL is managed on the memory resource itself. |

**Deprecation notice.** The ChatMemory-facing beans (`chatMemoryRepository`, `chatMemory`,
`AgentCoreMemory.shortTermMemoryAdvisor`) and the
`AgentCoreShortTermMemoryRepository implements ChatMemoryRepository` declaration are
marked `@Deprecated(since = "2.2.0", forRemoval = true)` and are scheduled for removal in
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

A lower order runs earlier and wraps every advisor after it. The STM advisors sit near
`Ordered.HIGHEST_PRECEDENCE`, so they run **before** the LTM advisors:

| Order | Advisor | Target | Purpose |
|-------|---------|--------|---------|
| `HIGHEST_PRECEDENCE + 200` | STM (`SessionMemoryAdvisor` on the Session API stack, `MessageChatMemoryAdvisor` on the ChatMemory stack) | Messages | Add conversation history, store the turn |
| `HIGHEST_PRECEDENCE + 300` | `ToolCallingAdvisor` (added by the `ChatClient` when tools are configured) | Messages | Run the tool loop |
| 100 | Semantic | System prompt | Add relevant facts |
| 200 | User Preference | System prompt | Add preferences |
| 300 | Summary | User prompt | Augment query with context |
| 400 | Episodic | System prompt | Add past interactions |

Both STM advisors wrap the tool loop, for the reason given under **Tool calling** above:
AgentCore stores message text only, so an STM advisor ordered after the
`ToolCallingAdvisor` would rebuild every tool round from storage and send the model a
tool result without its tool call. Spring AI 2.0 already defaults `MessageChatMemoryAdvisor`
to `HIGHEST_PRECEDENCE + 200`, so the auto-configured ChatMemory advisor needs no override;
only `SessionMemoryAdvisor` (upstream default `HIGHEST_PRECEDENCE + 1000`) gets one. If
you build either advisor yourself with `.order(...)`, keep it below
`HIGHEST_PRECEDENCE + 300`. A `MessageChatMemoryAdvisor` ordered after it also stores any
text sent with a tool call as an extra assistant turn, and with
`ignore-unknown-roles=false` it fails the call with `IllegalStateException` on the tool
result.

STM stores the user message before any LTM advisor edits the prompt, so the long-term
context reaches the model without being written to short-term memory. The LTM advisors
sit inside the tool loop and fetch again on every tool round; each round starts from the
prompt as it was before they ran, so the injected context is not duplicated.

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
   - `bedrock-agentcore:ListSessions` (for `findByUserId` and `CrossSessionRecallTools`; `findById` derives `createdAt` from the event tail and does not call `ListSessions`)
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
