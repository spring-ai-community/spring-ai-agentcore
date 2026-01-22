# Development Guide

This document covers development setup, testing, and contribution guidelines.

## Prerequisites

- Java 17+
- Maven 3.8+
- AWS credentials configured (for integration tests)

## Building

```bash
mvn clean install
```

## Testing

### Unit Tests

Run unit tests (no AWS credentials required):

```bash
mvn test
```

### Integration Tests

Integration tests require AWS credentials and interact with real AgentCore resources.

**Full test suite** (unit + integration tests):
```bash
AGENTCORE_IT=true mvn clean verify -pl spring-ai-memory-bedrock-agentcore
```

**E2E Test only** (creates ephemeral memory, runs tests, cleans up):
```bash
AGENTCORE_IT=true mvn verify -pl spring-ai-memory-bedrock-agentcore -Dit.test=AgentCoreMemoryE2EIT
```

**Env Test** (uses pre-existing memory from environment variables):
```bash
AGENTCORE_MEMORY_MEMORY_ID=your_memory_id \
AGENTCORE_MEMORY_LONG_TERM_SEMANTIC_STRATEGY_ID=SemanticFacts-xxxxx \
AGENTCORE_MEMORY_LONG_TERM_USER_PREFERENCE_STRATEGY_ID=UserPreferences-xxxxx \
AGENTCORE_MEMORY_LONG_TERM_SUMMARY_STRATEGY_ID=ConversationSummary-xxxxx \
AGENTCORE_MEMORY_LONG_TERM_EPISODIC_STRATEGY_ID=EpisodicMemory-xxxxx \
mvn verify -pl spring-ai-memory-bedrock-agentcore -Dit.test=AgentCoreMemoryEnvIT
```

### Helper Scripts

```bash
# Run memory integration tests with auto-created memory
./scripts/it-memory.sh [app_prefix]
```

## Project Structure

```
spring-ai-bedrock-agentcore/
├── spring-ai-bedrock-agentcore-starter/   # Runtime starter (invocations, ping, SSE)
├── spring-ai-memory-bedrock-agentcore/    # Memory integration (STM + LTM)
├── examples/                               # Working examples
└── scripts/                                # Helper scripts
```

## Code Style

This project uses Spring Java Format. Run before committing:

```bash
mvn spring-javaformat:apply
```

## Release Process

1. Update version in all `pom.xml` files
2. Run full test suite: `AGENTCORE_IT=true mvn clean verify`
3. Create release tag
4. Deploy to Maven Central
