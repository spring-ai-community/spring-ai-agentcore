# AGENTS.md - Artifact Store Module

## Purpose

Shared session-scoped artifact storage used by browser and code interpreter modules.

## Key Classes

| Class | Purpose |
|-------|---------|
| `ArtifactStore<T>` | Interface for session-scoped storage with category support |
| `ArtifactStoreFactory` | Factory interface for creating artifact stores |
| `CaffeineArtifactStore<T>` | Caffeine-backed implementation with TTL |
| `CaffeineArtifactStoreFactory` | Factory for creating Caffeine-backed stores |
| `GeneratedFile` | Immutable artifact record |
| `ArtifactMetadata` | Metadata constants and utilities |
| `SessionConstants` | Session ID constants for Reactor context |

## Category Support

Three usage patterns for artifact storage:

### 1. One shared store, default category (simplest)
```java
// Tools store without category
store.store(sessionId, artifact);

// Consumer retrieves all artifacts
List<GeneratedFile> all = store.retrieve(sessionId);
```

### 2. One shared store, separate categories
```java
// Tools store with explicit category
store.store(sessionId, BrowserTools.CATEGORY, screenshot);
store.store(sessionId, CodeInterpreterTools.CATEGORY, chart);

// Consumer retrieves by category
List<GeneratedFile> screenshots = store.retrieve(sessionId, BrowserTools.CATEGORY);
List<GeneratedFile> charts = store.retrieve(sessionId, CodeInterpreterTools.CATEGORY);
```

### 3. Separate store per tool
```java
// Each tool gets its own store
ArtifactStore<GeneratedFile> browserStore = factory.create(300, "BrowserStore");
ArtifactStore<GeneratedFile> codeStore = factory.create(300, "CodeStore");

// Tools store in their own store
browserStore.store(sessionId, screenshot);
codeStore.store(sessionId, chart);

// Consumer retrieves from each store
List<GeneratedFile> screenshots = browserStore.retrieve(sessionId);
List<GeneratedFile> charts = codeStore.retrieve(sessionId);
```

## Metadata Constants

`ArtifactMetadata` provides standard keys:
- `META_TIMESTAMP` - creation timestamp
- `META_TOOL_CALL_ID` - group artifacts by tool invocation

## Thread Safety

`CaffeineArtifactStore` is fully thread-safe. Caffeine handles synchronization internally.

## Configuration

Configured via consumer modules (browser, codeinterpreter):
- TTL: `screenshot-ttl-seconds` / `file-store-ttl-seconds`
- Max size: `artifact-store-max-size`

## Tests

```bash
mvn test -pl spring-ai-agentcore-artifact-store
```
