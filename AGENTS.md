# AGENTS.md

This file provides context for AI coding assistants working on this project.

## Project Overview

Spring Boot starter that enables Spring Boot applications to conform to the Amazon AgentCore Runtime contract. Provides auto-configuration for AgentCore endpoints and Spring AI integration with AgentCore Memory service.

## Architecture

```
spring-ai-agentcore/
├── spring-ai-agentcore-artifact-store/    # Shared artifact storage
├── spring-ai-agentcore-runtime-starter/   # Runtime starter (invocations, ping, SSE)
├── spring-ai-agentcore-memory/            # Memory integration (STM + LTM)
├── spring-ai-agentcore-browser/           # Browser automation tools
├── spring-ai-agentcore-code-interpreter/  # Code interpreter tools
├── examples/                                       # Working examples
└── scripts/                                        # Helper scripts
```

### Key Components

| Module | Purpose | Entry Point |
|--------|---------|-------------|
| `artifact-store` | Session-scoped artifact storage | `ArtifactStore.java`, `CaffeineArtifactStore.java` |
| `starter` | AgentCore Runtime contract | `AgentCoreAutoConfiguration.java` |
| `memory` | Spring AI ChatMemory integration | `AgentCoreShortTermMemoryRepositoryAutoConfiguration.java`, `AgentCoreLongTermMemoryAutoConfiguration.java` |
| `browser` | Browser automation tools | `AgentCoreBrowserAutoConfiguration.java` |
| `codeinterpreter` | Code execution tools | `AgentCoreCodeInterpreterAutoConfiguration.java` |

### Artifact Store Classes

| Class | Purpose |
|-------|---------|
| `ArtifactStore<T>` | Interface for session-scoped artifact storage with optional category support |
| `ArtifactStoreFactory<T>` | Factory interface for creating artifact stores |
| `CaffeineArtifactStore<T>` | Caffeine-backed implementation with TTL |
| `CaffeineArtifactStoreFactory<T>` | Factory for creating CaffeineArtifactStore instances |
| `GeneratedFile` | Immutable artifact record (mimeType, data, name, metadata) |
| `ArtifactMetadata` | Utility for metadata extraction |
| `SessionConstants` | Session ID constants |

### Memory Module Classes

| Class                                | Purpose |
|--------------------------------------|---------|
| `AgentCoreShortTermMemoryRepository` | STM - implements `ChatMemoryRepository` |
| `AgentCoreLongTermMemoryAdvisor`     | LTM - Spring AI advisor for prompt augmentation |
| `AgentCoreLongTermMemoryRetriever`   | LTM - retrieves memories from AgentCore |
| `AgentCoreMemory`                    | Combines STM + LTM advisors |

## Build & Test

```bash
# Build a single module (preferred for development)
mvn clean verify -pl spring-ai-agentcore-memory

# Build with upstream dependencies
mvn clean verify -pl spring-ai-agentcore-memory -am

# Fix style before committing
mvn spring-javaformat:apply rewrite:run -pl <module>

# Full build (CI does this — rarely needed locally)
mvn clean verify

# Integration tests (requires AWS credentials in us-east-1)
# ITs self-provision and tear down their own AWS resources; no pre-existing
# resources are needed for the default path.
AGENTCORE_IT=true AWS_REGION=us-east-1 mvn verify -pl spring-ai-agentcore-memory

# Run integration tests across all modules
AGENTCORE_IT=true AWS_REGION=us-east-1 mvn clean verify
```

> **Integration test prerequisites**
> - AWS credentials with AgentCore access, in **us-east-1** (the browser ITs assert this region).
> - The browser module ITs need Playwright/Chromium (downloaded automatically on first run).
> - The default memory IT (`AgentCoreMemoryE2EIT`) **creates and deletes** its own memory and
>   long-term strategies — it needs only `AGENTCORE_IT=true` plus credentials, no pre-existing IDs.
> - After switching git branches, run `mvn clean ...` — the OpenRewrite recipe module compiles
>   test sources incrementally and stale `target/` output from another branch can break the build.

## Code Conventions

- **Java version**: 17+
- **Code style**: Spring Java Format + Checkstyle + OpenRewrite (see `build-tools/`)
- **License**: Apache 2.0 headers required on all Java files
- **Testing**: Unit tests `*Tests.java`, integration tests `*IT.java`
- **Mocking**: Use `BDDMockito.given/then` (not `Mockito.when/verify`)
- **Assertions**: Use AssertJ `assertThat(...)` (not JUnit `assertEquals`)
- **Properties prefix**: `agentcore.memory.*` for memory module

### Style enforcement

After writing code, always run:
```bash
mvn spring-javaformat:apply rewrite:run -pl <module>
```

This fixes: import ordering, `this.` qualifiers, lambda formatting, inner type positioning, ternary style, catch formatting, method visibility, overload grouping, fully-qualified type references, and unused imports.

## Key Dependencies

- Spring Boot 3.x
- Spring AI 1.0.0
- AWS SDK v2 (`software.amazon.awssdk:bedrockagentcore`, `bedrockagentcorecontrol`)

## Integration Test Environment Variables

Most ITs self-provision their AWS resources and need only `AGENTCORE_IT=true` plus
credentials. The variables below are **only** for the optional `AgentCoreMemoryEnvIT`
path, which runs against a pre-existing memory instead of creating one (see
`scripts/it-memory.sh`, which discovers and exports them automatically):

```bash
AGENTCORE_MEMORY_MEMORY_ID=<memory-id>
AGENTCORE_MEMORY_LONG_TERM_SEMANTIC_FACTS_STRATEGY_ID=SemanticFacts-xxxxx
AGENTCORE_MEMORY_LONG_TERM_USER_PREFERENCES_STRATEGY_ID=UserPreferences-xxxxx
AGENTCORE_MEMORY_LONG_TERM_SUMMARY_STRATEGY_ID=ConversationSummary-xxxxx
AGENTCORE_MEMORY_LONG_TERM_EPISODIC_STRATEGY_ID=EpisodicMemory-xxxxx
```

## Common Tasks

| Task | Command |
|------|---------|
| Build | `mvn clean install` |
| Test | `mvn test` |
| Integration test | `AGENTCORE_IT=true mvn verify` |
| Format | `mvn spring-javaformat:apply` |
| Fix all style | `mvn spring-javaformat:apply rewrite:run -pl <module>` |
| Run memory IT | `./scripts/it-memory.sh` |
