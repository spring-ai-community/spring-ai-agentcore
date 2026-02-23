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
# Build
mvn clean install

# Unit tests only
mvn test

# Full test suite (requires AWS credentials)
AGENTCORE_IT=true mvn clean verify -pl spring-ai-agentcore-memory

# Format code (required before commit)
mvn spring-javaformat:apply
```

## Code Conventions

- **Java version**: 17+
- **Code style**: Spring Java Format (run `mvn spring-javaformat:apply`)
- **License**: Apache 2.0 headers required on all Java files
- **Testing**: Unit tests in `src/test`, integration tests with `IT` suffix
- **Properties prefix**: `agentcore.memory.*` for memory module

## Key Dependencies

- Spring Boot 3.x
- Spring AI 1.0.0
- AWS SDK v2 (`software.amazon.awssdk:bedrockagentcore`, `bedrockagentcorecontrol`)

## Integration Test Environment Variables

```bash
AGENTCORE_MEMORY_MEMORY_ID=<memory-id>
AGENTCORE_MEMORY_LONG_TERM_SEMANTIC_STRATEGY_ID=SemanticFacts-xxxxx
AGENTCORE_MEMORY_LONG_TERM_USER_PREFERENCE_STRATEGY_ID=UserPreferences-xxxxx
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
| Run memory IT | `./scripts/it-memory.sh` |
