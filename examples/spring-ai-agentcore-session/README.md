# Spring AI AgentCore Session API Example

A runnable Spring Boot example demonstrating the Spring AI **Session API** bean stack
(incubating) backed by Amazon AgentCore Memory. This is the sibling of the
`spring-ai-memory-integration` example, but wired through the newer
`SessionMemoryAdvisor` API instead of the deprecated `ChatMemory` advisors.

## What this example shows

When `agentcore.memory.session.enabled=true` is set and the
`spring-ai-session-management` artifact is on the classpath, the
`spring-ai-agentcore-memory` module auto-configures four beans:

- `AgentCoreSessionRepository` (implements `org.springframework.ai.session.SessionRepository`)
- `DefaultSessionService` (`SessionService`)
- `SessionMemoryAdvisor`
- `AgentCoreSessionMemory` (bundles the advisor with any configured long-term memory advisors)

The example injects the auto-configured `ChatClient.Builder` and
`AgentCoreSessionMemory` into a REST controller that exposes a `/api/chat` endpoint.
Each request sets the session id via
`SessionMemoryAdvisor.SESSION_ID_CONTEXT_KEY`, which equals
`ChatMemory.CONVERSATION_ID` (same key, different backing stack).

## Required user-side dependency

`spring-ai-agentcore-memory` declares `spring-ai-session-management` as an `optional`
dependency, so consumers on the Session API path add it explicitly. This example does so
in its `pom.xml`:

```xml
<dependency>
    <groupId>org.springaicommunity</groupId>
    <artifactId>spring-ai-session-management</artifactId>
</dependency>
```

The version is managed by `spring-ai-session-bom`, imported by the examples parent pom,
so this project never pins the version directly.

## The `userId:sessionSuffix` session id

`AgentCoreSessionRepository` has no separate session-metadata store, so it derives
`Session.userId` from the actor (userId) segment of the sessionId parsed by
`AgentCoreMemoryConversationIdParser`. The sessionId has the form
`userId:sessionSuffix`; the example uses `"testActor:testSession"`, where `testActor`
is the userId and `testSession` is the suffix.

If you pass `SessionMemoryAdvisor.USER_ID_CONTEXT_KEY` on a request, its value must equal
the userId prefix of the sessionId. On the second turn the advisor runs an ownership
check comparing `USER_ID_CONTEXT_KEY` against the derived `Session.userId`; a mismatch
throws `IllegalStateException("...does not belong to user...")`. This controller does not
set `USER_ID_CONTEXT_KEY`, so the check passes. The common trap is copying this example,
reusing a sessionId, then passing a different `USER_ID_CONTEXT_KEY` on turn two. Keep the
userId prefix and any `USER_ID_CONTEXT_KEY` value identical.

## Prerequisites

- Java 21+
- Maven 3.6+
- AWS credentials with permission to call AgentCore Memory
- A real `AGENTCORE_MEMORY_ID` (the placeholder in `application.properties` is
  `your-memory-id-here` and will not work against AWS)

## Quick start

1. Configure your AWS credentials.
2. Create an AgentCore Memory resource (the helper in `src/test/java` does this):
   ```bash
   mvn spring-boot:test-run
   ```
   Copy the printed `AGENTCORE_MEMORY_ID` and export it:
   ```bash
   export AGENTCORE_MEMORY_ID=...
   ```
3. Start the Spring Boot app:
   ```bash
   mvn spring-boot:run
   ```
4. Talk to it:
   ```bash
   curl -X POST http://localhost:8080/api/chat \
       -H "Content-Type: application/json" \
       -d '{"message": "My name is Andrei"}'

   curl -X POST http://localhost:8080/api/chat \
       -H "Content-Type: application/json" \
       -d '{"message": "What is my name?"}'
   ```

## Cleanup

With `AGENTCORE_MEMORY_ID` set, re-run the helper and answer `yes` when it asks
whether to delete the memory:
```bash
mvn spring-boot:test-run
```

## Notes on the offline build

The example's `SetupTeardown` helper in `src/test/java` is a Spring Boot main app, not
a JUnit test. Maven Surefire skips it during `mvn test` because there are no
`@Test`-annotated classes. That keeps the examples CI build green without any AWS
credentials or network access, exactly like the sibling
`spring-ai-memory-integration` example.
