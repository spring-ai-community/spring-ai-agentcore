# Spring AI AgentCore Memory

Spring AI ChatMemory integration with Amazon AgentCore Memory service.

For quick start and usage examples, see the [main README](../README.md#agentcore-memory).

## Features

- **Spring AI Integration**: Implements `ChatMemoryRepository` interface
- **Auto-configuration**: Zero-configuration setup with Spring Boot
- **Short-Term Memory**: Conversation history with `MessageWindowChatMemory`
- **Long-Term Memory**: 4 consolidation strategies (Semantic, User Preference, Summary, Episodic)
- **Session API (incubating)**: Optional Spring AI Session API bean stack (opt-in via `agentcore.memory.session.enabled=true`)

## Session API (Spring AI 2.1 `spring-ai-session`, incubating)

The module ships an opt-in bean stack backed by the Spring AI Session API
(`org.springframework.ai:spring-ai-session` and `spring-ai-session-advisor`, Spring AI
2.1). When enabled, four beans are added to the context: `AgentCoreSessionRepository`
(implements `org.springframework.ai.session.SessionRepository`), `DefaultSessionService`,
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
```

The remaining `agentcore.memory.session.*` properties (`total-events-limit`, `page-size`,
`ignore-unknown-roles`, `default-session`) are optional session-scoped overrides; when
unset they fall back to the `agentcore.memory.short-term.*` (then legacy) values.

**Required dependencies.** The memory module declares both artifacts as `optional`
dependencies, so consumers on the Session API path add them to their own `pom.xml`. Their
versions come from `org.springframework.ai:spring-ai-bom`. If one is missing while
`agentcore.memory.session.enabled=true` is set, the module logs a startup WARN and creates
no session beans.

```xml
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-session</artifactId>
</dependency>
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-session-advisor</artifactId>
</dependency>
```

**Upgrading from the community `spring-ai-session` (0.10.x).** Spring AI 2.1 ships the
Session API itself, in the same `org.springframework.ai.session` packages but with a
changed API, so this module requires Spring AI 2.1 and no longer works with
`org.springaicommunity:spring-ai-session`. To upgrade:

1. Remove `org.springaicommunity:spring-ai-session` (and any `spring-ai-session-management`)
   and the `org.springaicommunity:spring-ai-session-bom` import; add the two
   `org.springframework.ai` artifacts above. The `AgentCoreSessionRepository` constructor
   fails fast with an `IllegalStateException` naming the fix if a community jar is still on
   the classpath (check transitive ones with `mvn dependency:tree`).
2. The property `agentcore.memory.session.default-user-id` is removed: the 2.1
   `SessionMemoryAdvisor` has no user id and no ownership check
   (`USER_ID_CONTEXT_KEY` is gone). The session's principal is derived from the sessionId.
3. Code that calls `AgentCoreSessionRepository` directly: `findByUserId(userId)` becomes
   `findByMetadata(SessionAttributes.PRINCIPAL_NAME, userId)`, `Session.userId()` becomes
   `SessionAttributes.principalName(session)`, and keyword search moves from `EventFilter`
   to `searchEvents(sessionId, EventQuery)`. `applyCompaction` is replaced by
   `appendEvents`, which this repository rejects like before.
4. Only message events are stored: `appendEvent` rejects events of another `EventType`
   with `IllegalArgumentException`.

**Usage.** `SessionMemoryAdvisor.SESSION_ID_CONTEXT_KEY` equals `ChatMemory.CONVERSATION_ID`,
so the same conversation-id constant works for both stacks:

```java
chatClient.prompt()
    .user("Hi")
    .advisors(a -> a.param(SessionMemoryAdvisor.SESSION_ID_CONTEXT_KEY, "alice:conv-1"))
    .call()
    .content();
```

**User id and session id.** `AgentCoreSessionRepository` has no session-metadata store,
so it derives the session's principal (`SessionAttributes.PRINCIPAL_NAME`) from the actor
(userId) segment of the sessionId (parsed by `AgentCoreMemoryConversationIdParser`). The
sessionId format is therefore `"userId:sessionSuffix"` (for example `"alice:conv-1"`,
where `alice` is the userId). The sessionId is split at its last colon, so the userId may
contain colons (`"tenant:alice:conv-1"`) but the suffix may not; see
[Conversation ID Format](#conversation-id-format) for the allowed characters.

**Security.** The sessionId/conversationId, and therefore the derived principal, is
client-supplied input: a caller that controls it chooses whose session it reads. Where an
authenticated principal exists, the application must derive the conversationId's actor
segment from the principal, never from unvalidated request input.

**Reads and writes.** The event log is append-only: `appendEvent` and `delete` are the
only write paths, only message events are stored, synthetic events (framework generated)
are never persisted, and `appendEvents` and `getEventVersion` throw
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

`total-events-limit` is a coarser cap. It applies to every read without a last-N
window, including `searchEvents`, which then sees only the newest events of the session
and silently misses older matches. Do not configure compaction on the session advisors
(`SessionTurnAdvisor`, `SessionAdvisors`) with this repository: the compaction runs after
the turn has been persisted and the model has answered, and then fails. `findEvents`
applies every `EventFilter` predicate (message and event types, synthetic events)
client-side and stops paginating early for last-N windows. A last-N window keeps turns
whole: when the newest N events start mid-turn, the window extends back to the turn's
user message, so the advisor may return more than N events. `searchEvents` uses the SPI
default, which reads the whole session log (up to `total-events-limit`) and matches in
memory, because AgentCore has no text search.

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
| `findByMetadata(String, String)` | answers for `SessionAttributes.PRINCIPAL_NAME` and `agentcore.actorId`: maps the value to the AgentCore actor and paginates `ListSessions` | Returns compound ids `"userId:sessionId"` that round-trip through the other methods; `createdAt` from each `SessionSummary`, falling back to the `Instant.EPOCH` sentinel when the summary has none (the same fallback documented on the `Session.createdAt` row); unknown user yields an empty list. Any other key returns an empty list, because no other metadata is stored. Needs the `bedrock-agentcore:ListSessions` IAM permission. |
| `saveIfAbsent(Session)` | returns `true` when the session has no events, stores nothing | Like `save`, there is no session-metadata store; the check is not atomic (AgentCore has no compare-and-set), so two concurrent `SessionService.create` calls for the same id can both succeed. |
| `deleteExpiredSessions(Instant)` | deletes nothing, returns `0` | Sessions have no TTL; AgentCore removes events itself through memory-level retention (`eventExpiryDuration`). A scheduled `SessionService.deleteExpiredSessions` keeps running. |
| `searchEvents(String, EventQuery)` | SPI default: `findEvents`, then matched in memory | AgentCore has no text search, so a search reads the session log (up to `total-events-limit`). |
| `findById(String)` | returns `null` when the session has no events | AgentCore has no notion of an empty session; the first `appendEvent` creates it. |
| `appendEvents(String, List, long)` | throws `UnsupportedOperationException` | The batch must be appended atomically at an expected log version (how a compaction is recorded); the AgentCore log has no CAS and no atomic multi-event write. Bound context via read-windowing (`EventFilter.lastN`, or `totalEventsLimit`) and long-term memory extraction instead. No compaction boundary is ever written, so the active view is the whole log. |
| `getEventVersion(String)` | throws `UnsupportedOperationException` | Its only SPI purpose is supplying the `expectedVersion` for `appendEvents`; a count would suggest an optimistic-lock capability the backend does not have. `DefaultSessionService.compact` calls it first, so a configured compaction fails here. |
| `appendEvent(SessionEvent)` | rejects events that are not `EventType.MESSAGE` with a message | AgentCore events hold conversation messages; other event types throw `IllegalArgumentException`. |
| `appendEvent(SessionEvent)` | does not throw when session is unknown | First append implicitly creates the session server-side. |
| `appendEvent(SessionEvent)` | idempotent by `SessionEvent.getId()`, best effort, expected rather than verified | The id feeds a deterministic CreateEvent `clientToken`. Per the CreateEvent API reference, AgentCore should then ignore an application retry with the same id; the reference does not say what happens when the retry carries a different timestamp, and only the live `AgentCoreSessionRepositoryIT` checks it. Unlike the SPI reference, AgentCore remembers tokens only for an undocumented window: a replay after it would be stored again, and a re-append after `delete` inside it would be dropped. The advisor's random ids never collide. |
| events read back (`findEvents`, advisor history) | ids derived from the AgentCore `eventId`, text only | No tool calls, media or custom metadata come back (only `agentcore.eventId`). Re-appending a loaded event with a rebuilt `Message` is not deduplicated. |
| `total-events-limit` | caps every read without `EventFilter.lastN` to the newest N events | `searchEvents` silently misses older matches; bound the advisor's context with `EventFilter.lastN` instead. |
| `Session.createdAt` | `findByMetadata`: real instant from each `SessionSummary`; `findById`: the tail (most recent) event timestamp, without calling `ListSessions` | Either path falls back to the `Instant.EPOCH` sentinel when its source carries no timestamp; the last-event timestamp is also exposed under metadata key `agentcore.lastEventAt`. |
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
- **User Preference**: Lists ALL stored preferences regardless of query (reads at most 200 records) — preferences should always apply
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
| 300 | Summary | Current user message | Augment the current question with session summaries |
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
context reaches the model without being written to short-term memory. The Summary advisor
changes only the current (last) user message; the user messages of the history that STM
added stay unchanged. The LTM advisors
sit inside the tool loop and fetch again on every tool round; each round starts from the
prompt as it was before they ran, so the injected context is not duplicated.

### System Prompt vs User Prompt

| Memory Type | Target | Reason |
|-------------|--------|--------|
| Semantic | System | Stable context about user, cacheable |
| User Preference | System | Stable settings, cacheable |
| Episodic | System | Background context, cacheable |
| Summary | Current user message | Query-specific augmentation, varies per request |

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
- Treats a `CUSTOM` strategy as the built-in type it overrides (`SEMANTIC_OVERRIDE`, `SUMMARY_OVERRIDE`, `USER_PREFERENCE_OVERRIDE` or `EPISODIC_OVERRIDE`), including the reflection namespaces of an episodic override
- Skips `CUSTOM` strategies that are self-managed (`SELF_MANAGED`), which have no built-in retrieval
- Uses the first namespace if a strategy has multiple namespaces
- Uses default `topK` values for each strategy type
- Skips a strategy whose namespace does not contain `{actorId}`, and an episodic reflection namespace without it (the episodes are still used), because their records are shared by all actors and would be added to every user's prompt. To use such a namespace on purpose, set the strategy's `strategy-id` with `namespace-pattern` or `reflections-namespace-pattern` (the startup warning shows the values), or set `agentcore.memory.long-term.namespace.allow-shared=true` to use all shared namespaces; a warning is then logged for each one at startup

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
- `namespace-pattern` must match one of the namespaces discovered from AWS; otherwise startup fails

**Namespace auto-registration (deprecated):**

`agentcore.memory.long-term.namespace.auto-register=true` is deprecated and will be removed in 3.0.0. On a namespace mismatch it writes the configured pattern to the memory strategy in AWS at startup. A strategy has exactly one namespace, so this replaces the existing one: records extracted before stay under the old namespace and are no longer retrieved, and other applications using the same memory are affected. A pattern without `{actorId}` is never registered, in either mode.

Instead, configure `namespace-pattern` to match the memory (the startup error shows the property and value to use), or omit it and let auto-discovery use the memory's namespace. To change the namespace of a memory strategy, use the AWS console, CLI or your infrastructure tooling.

#### Option 2: Explicit Configuration

Manually specify each strategy:

```yaml
agentcore:
  memory:
    long-term:
      semantic:
        strategy-id: ${SEMANTIC_STRATEGY_ID}     # Enables strategy (omit to disable)
        top-k: 3                                 # Default: 3
        namespace: ACTOR                         # Default: ACTOR
      user-preference:
        strategy-id: ${USER_PREFERENCE_STRATEGY_ID}  # Enables strategy (no top-k: lists all)
        namespace: ACTOR                         # Default: ACTOR
      summary:
        strategy-id: ${SUMMARY_STRATEGY_ID}      # Enables strategy
        top-k: 3                                 # Default: 3
        namespace: SESSION                       # Default: SESSION
      episodic:
        strategy-id: ${EPISODIC_STRATEGY_ID}     # Enables strategy
        reflections-namespace-pattern: ${REFLECTIONS_NAMESPACE}  # Optional: enables reflections; must match the memory
        episodes-top-k: 3                        # Default: 3
        reflections-top-k: 2                     # Default: 2
        namespace: ACTOR                         # Default: ACTOR
```

#### Namespace Options

| `namespace` | Namespace Pattern | Use Case |
|-------|-------------------|----------|
| `ACTOR` | `/strategies/{memoryStrategyId}/actors/{actorId}` | Search across all sessions for the user |
| `SESSION` | `/strategies/{memoryStrategyId}/actors/{actorId}/sessions/{sessionId}` | Search only current session |

AgentCore Memory matches the namespace of a retrieval request as a string prefix. It stores namespaces as written in the strategy's template, with or without a trailing `/`, and a request that ends with `/` matches nothing in a memory whose namespaces don't. So requests follow the memory's form: auto-discovery uses the memory's templates as patterns, and with explicit configuration, startup validation gives each configured pattern the trailing slash of the memory namespace it matched.

The trailing slash decides what a request can match:

- If the template ends with `/`, as AWS [recommends](https://docs.aws.amazon.com/bedrock-agentcore/latest/devguide/specify-long-term-memory-organization.html#memory-namespace-format) (for example `/strategies/{memoryStrategyId}/actors/{actorId}/`), the request for actor `alice` is `/strategies/abc123/actors/alice/` and matches only the records of `alice`.
- If it doesn't (for example `/strategies/{memoryStrategyId}/actors/{actorId}`), the request `/strategies/abc123/actors/alice` also matches the records of actors such as `alice2` and `alice-admin`. The library drops them after retrieval, but they still count toward `top-k` and toward the 200 records a listing reads, so a request can return fewer than `top-k` of the records of `alice`, or none.

AWS documents the values of a `bedrock-agentcore:namespace` condition in IAM policies with the trailing slash. For the template `/strategies/{memoryStrategyId}/actors/{actorId}/`, a condition for actor `alice` uses `/strategies/abc123/actors/alice/`, the namespace the library sends, or `/strategies/abc123/actors/alice/*` to include the namespaces below it.

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

**Note**: Only these predefined placeholders are supported. Custom placeholders are not allowed: with explicit configuration, startup fails if a pattern uses any other placeholder (for example a custom namespace key), because retrieval cannot fill it.

With explicit configuration (`auto-discovery: false`), startup validation compares placeholders by name, so a pattern that puts `{sessionId}` where the memory has `{actorId}` (or the reverse) fails instead of reading the wrong scope. A placeholder in the pattern does not match a literal segment in the memory, except `{memoryStrategyId}`, which matches a literal equal to the configured strategy ID. All other segments must be identical. The episodic `reflections-namespace-pattern` is checked against the strategy's reflection namespaces, whether they come from `episodicReflectionConfiguration` or from the `episodicReflectionOverride` of a `customReflectionConfiguration`. With the deprecated `namespace.auto-register=true`, a mismatching pattern is registered only if it contains `{actorId}`, and only after all checks have passed.

#### Defaults Summary

| Strategy | top-k | namespace |
|----------|-------|-----------|
| semantic | 3 | ACTOR |
| user-preference | n/a (lists all, reads at most 200) | ACTOR |
| summary | 3 | SESSION |
| episodic | episodes: 3, reflections: 2 | ACTOR |

Long-term memory turns on when `auto-discovery` is `true` or when at least one strategy has a `strategy-id`, and advisors are created only for the configured strategies. Each strategy is optional, so configure only the ones you need. There is no separate `enabled` switch.

## Conversation ID Format

The repository supports flexible conversation ID formats:

- **Simple**: `user123` → actor: `user123`, session: `default-session`
- **With Session**: `user123:session456` → actor: `user123`, session: `session456`

The conversation ID is split at its **last** colon. AgentCore allows colons inside an actor ID (for example a tenant prefix or a URN-style subject) but never inside a session ID, so `tenant:alice:conv-1` means actor `tenant:alice` and session `conv-1`. Two rules follow from this:

- An actor ID that contains a colon must always come with a session. On its own, `tenant:alice` means actor `tenant` and session `alice`.
- A session ID cannot contain a colon.

Build compound IDs with `AgentCoreMemoryConversationIdParser.of(userId, sessionSuffix)` rather than by concatenating strings. It trims both parts, accepts colons in the user ID, and throws `IllegalArgumentException` when a part is blank, the session suffix contains a colon, or either part contains a control character:

```java
String conversationId = AgentCoreMemoryConversationIdParser.of("tenant:alice", "conv-1"); // "tenant:alice:conv-1"
```

This matters most when the session part comes from the client, such as the `X-Amzn-Bedrock-AgentCore-Runtime-Session-Id` header. Concatenated, a session ID of `bob:x` turns user `alice` into actor `alice:bob`, which can be another user's actor. `of()` rejects that session ID.

### Allowed Characters

AgentCore rejects a request whose actor ID or session ID breaks the rules below (see the [CreateEvent API reference](https://docs.aws.amazon.com/bedrock-agentcore/latest/APIReference/API_CreateEvent.html)).

| ID | Length | Allowed characters |
|----|--------|--------------------|
| Actor ID | 1 to 255 | ASCII letters, digits, `-`, `_`, `/` and `:`. Must start with a letter or digit. A colon must be followed by a letter, digit, `-`, `_` or `/`, so an actor ID cannot end with `:` or contain `::`. |
| Session ID | 1 to 100 | ASCII letters, digits, `-` and `_`. Must start with a letter or digit. |

This module checks a few rules before calling AgentCore. Long-term memory rejects an actor ID that contains `/` or `*`, because the namespace of actor `bob/x` would sit beneath that of actor `bob`. The Session API repository rejects control characters and empty parts (such as a leading colon) up front. Everything else is left to AgentCore.

User IDs from identity providers often break these rules: e-mail addresses contain `@` and `.`, and subjects such as `auth0|123` contain `|`. Map such a subject to a safe, stable value before using it as the actor ID, for example your application's internal user ID or a hex-encoded SHA-256 hash of the subject. Use the same mapped value everywhere the user ID appears: in the conversation ID of the ChatMemory stack and in the actor segment of Session API session IDs, from which `AgentCoreSessionRepository` derives the session's principal.

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
   - `bedrock-agentcore:ListSessions` (for `findByMetadata`; `findById` derives `createdAt` from the event tail and does not call `ListSessions`)
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
