# AGENTS.md - Artifact Store Module

## Purpose

Shared session-scoped artifact storage used by browser and code interpreter modules.

## Key Classes

| Class | Purpose |
|-------|---------|
| `ArtifactStore<T>` | Interface for session-scoped storage |
| `ArtifactStoreFactory` | Factory interface for creating artifact stores |
| `CaffeineArtifactStore<T>` | Caffeine-backed implementation with TTL |
| `CaffeineArtifactStoreFactory` | Factory for creating Caffeine-backed stores |
| `GeneratedFile` | Immutable artifact record |
| `ArtifactMetadata` | Metadata extraction utility |
| `SessionConstants` | Session ID constants for Reactor context |

## Thread Safety

`CaffeineArtifactStore` is fully thread-safe. Caffeine handles synchronization internally.

## Configuration

Configured via consumer modules (browser, codeinterpreter):
- TTL: `screenshot-ttl-seconds` / `file-store-ttl-seconds`
- Max size: `artifact-store-max-size`

## Tests

```bash
mvn test -pl spring-ai-bedrock-agentcore-artifact-store
```
