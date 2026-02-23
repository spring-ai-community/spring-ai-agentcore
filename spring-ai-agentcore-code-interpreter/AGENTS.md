# AGENTS.md

Context for AI coding assistants working on this module.

## Module Overview

Spring AI integration with Amazon AgentCore Code Interpreter. Executes Python, JavaScript, and TypeScript code in a secure sandbox with automatic file retrieval.

## Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                     CodeInterpreterTools                        │
│                   (Tool implementation logic)                   │
├─────────────────────────────────────────────────────────────────┤
│  SESSION_ID_CONTEXT_KEY = "sessionId"                           │
│  executeCode(language, code) → String                           │
│    - Validates language (python, javascript, typescript)        │
│    - Gets sessionId from ToolCallReactiveContextHolder          │
│    - Executes code via client                                   │
│    - Stores files in FileStore                                  │
│    - Returns text-only result to LLM                            │
└───────────────────────────┬─────────────────────────────────────┘
                            │ uses
                            ▼
┌─────────────────────────────────────────────────────────────────┐
│                  AgentCoreCodeInterpreterClient                 │
│                    (Low-level SDK wrapper)                      │
├─────────────────────────────────────────────────────────────────┤
│  startSession(name) → sessionId                                 │
│  executeCode(sessionId, language, code) → CodeExecutionResult   │
│  listFiles(sessionId, path) → List<String>                      │
│  readFiles(sessionId, paths) → List<GeneratedFile>              │
│  stopSession(sessionId)                                         │
│  executeInEphemeralSession(language, code) → CodeExecutionResult│
└───────────────────────────┬─────────────────────────────────────┘
                            │ returns
                            ▼
┌─────────────────────────────────────────────────────────────────┐
│                     CodeExecutionResult                         │
├─────────────────────────────────────────────────────────────────┤
│  String textOutput          // stdout/stderr combined           │
│  boolean isError            // from SDK isError flag            │
│  List<GeneratedFile> files  // images, PDFs, CSVs, etc.         │
└─────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────┐
│                       GeneratedFile                             │
├─────────────────────────────────────────────────────────────────┤
│  String mimeType            // "image/png", "application/pdf"   │
│  byte[] data                // raw bytes (defensively copied)   │
│  String name                // filename                         │
│  isImage(), isText(), toDataUrl(), size()                       │
└─────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────┐
│                 ArtifactStore<GeneratedFile>                    │
│            (Session-scoped file storage with TTL)               │
├─────────────────────────────────────────────────────────────────┤
│  DEFAULT_CATEGORY = "default"                                   │
│  Caffeine cache with TTL (default 5 min) and max entries        │
│  store(sessionId, file)  // store with default category         │
│  store(sessionId, category, file)  // store with category       │
│  retrieve(sessionId) → List<GeneratedFile>  // get and clear    │
│  retrieve(sessionId, category) → List<GeneratedFile>            │
│  hasArtifacts(sessionId) → boolean                              │
└─────────────────────────────────────────────────────────────────┘
```

## Key Classes

| Class | Purpose |
|-------|---------|
| `AgentCoreCodeInterpreterAutoConfiguration` | Spring Boot auto-config with `ToolCallbackProvider` |
| `AgentCoreCodeInterpreterClient` | Low-level SDK wrapper with configurable timeouts |
| `AgentCoreCodeInterpreterConfiguration` | Config properties (timeouts, TTL, identifier, description) |
| `CodeInterpreterTools` | Tool implementation with session context and optional category support |
| `CodeInterpreterArtifacts` | Helper for creating artifacts with metadata |
| `CodeExecutionResult` | Record for execution results with null-safe defaults |
| `GeneratedFile` | Record for file data with defensive copy and helper methods |
| `ExecuteCodeRequest` | Input schema record for the tool (language, code) |

## Design Decisions

1. **ToolCallbackProvider pattern** - Programmatic tool registration with configurable description
2. **File handling in ChatService** - Files appended after stream completes, outside memory flow
3. **Reactor context for session ID** - Session ID passed via `ToolCallReactiveContextHolder`, not `ToolContext`, to avoid leaking metadata to MCP tools
4. **No advisor** - Avoids files being stored in conversation memory (context overflow)
5. **Null-safe records** - `CodeExecutionResult` and `GeneratedFile` use defensive copies
6. **TTL-based cleanup** - Caffeine cache with 5-minute TTL prevents memory leaks
7. **Input validation** - Tool validates language and code parameters before execution

## Request Flow

```
1. User: "Create a chart showing Q1 sales"
2. ChatService stores sessionId in Reactor context via .contextWrite()
3. LLM decides to call executeCode tool
4. Spring AI captures Reactor context, stores in ThreadLocal via ToolCallReactiveContextHolder
5. CodeInterpreterTools.executeCode(language, code):
   a. Get sessionId from ToolCallReactiveContextHolder (or use DEFAULT_SESSION_ID)
   b. client.executeInEphemeralSession(language, code)
   c. artifactStore.store(sessionId, files)
   d. Return text-only result to LLM
6. Spring AI clears ThreadLocal in finally block
7. LLM responds: "Here's your Q1 sales chart..."
8. Memory stores: user message + LLM response (NO files)
9. ChatService.appendGeneratedFiles(sessionId)
10. User sees: LLM response + chart image
```

## Why ToolCallReactiveContextHolder (not @RequestScope)

Tool execution happens on `Schedulers.boundedElastic()` thread pool, not the HTTP request thread. `@RequestScope` beans throw `ScopeNotActiveException` because no HTTP request context exists on those threads.

Spring AI's `ToolCallReactiveContextHolder` bridges Reactor context to ThreadLocal:
1. `ChatService.chat()` stores sessionId in Reactor context via `.contextWrite()`
2. Spring AI captures Reactor context before tool execution
3. Spring AI stores it in ThreadLocal via `ToolCallReactiveContextHolder.setContext(ctx)`
4. Tools read sessionId from `ToolCallReactiveContextHolder.getContext()`
5. Spring AI clears ThreadLocal in `finally` block before thread returns to pool

## Configuration

```properties
agentcore.code-interpreter.session-timeout-seconds=900
agentcore.code-interpreter.async-timeout-seconds=300
agentcore.code-interpreter.file-store-ttl-seconds=300
agentcore.code-interpreter.code-interpreter-identifier=aws.codeinterpreter.v1
agentcore.code-interpreter.tool-description=Custom tool description...
```

## Build & Test

```bash
# Compile
mvn compile -pl spring-ai-agentcore-code-interpreter

# Format (required before commit)
mvn spring-javaformat:apply -pl spring-ai-agentcore-code-interpreter

# Integration test (requires AWS credentials)
AGENTCORE_IT=true mvn verify -pl spring-ai-agentcore-code-interpreter
```

## Integration Tests

**CodeInterpreterToolsIT (21 tests):**

| Test | Coverage |
|------|----------|
| `shouldExecutePythonCode` | executeCode("python") |
| `shouldExecuteJavaScriptCode` | executeCode("javascript") |
| `shouldExecuteTypeScriptCode` | executeCode("typescript") |
| `shouldStoreImageFileBySessionId` | chart + store + isImage() |
| `shouldStoreCsvFileBySessionId` | CSV + store + isText() |
| `shouldUseDefaultSessionWhenNull` | null → DEFAULT |
| `shouldRetrieveFilesOnlyFromOwnSession` | session isolation |
| `shouldClearFilesAfterRetrieve` | retrieve() clears |
| `shouldAccumulateFilesInSameSession` | multiple stores append |
| `shouldHasFilesReturnCorrectly` | hasFiles() |
| `shouldGeneratedFileToDataUrlReturnValidFormat` | GeneratedFile.toDataUrl() |
| `shouldGeneratedFileSizeReturnCorrectValue` | GeneratedFile.size() |
| `shouldCodeExecutionResultHasFilesWork` | CodeExecutionResult.hasFiles() |
| `shouldRejectNullLanguage` | input validation |
| `shouldRejectBlankLanguage` | input validation |
| `shouldRejectUnsupportedLanguage` | input validation |
| `shouldRejectNullCode` | input validation |
| `shouldRejectBlankCode` | input validation |
| `shouldHandleExecutionError` | isError=true |
| `shouldFormatErrorOutputCorrectly` | error prefix |
| `shouldIsolateFilesBetweenParallelSessions` | Parallel session isolation |

**CodeInterpreterChatFlowIT (3 tests):**

| Test | Coverage |
|------|----------|
| `shouldPropagateSessionIdThroughChatClientStreamingFlow` | Session ID flows from ChatClient to tool |
| `shouldStoreFilesUnderCorrectSessionIdThroughChatClientFlow` | Files stored by session |
| `shouldIsolateFilesBetweenSessionsThroughChatClientFlow` | Parallel session isolation |

## Not Implemented

SDK capabilities not yet exposed:

| Feature | SDK Operation | Use Case |
|---------|---------------|----------|
| File upload | `writeFiles` | Upload user files for processing |
| File deletion | `deleteFiles` | Clean up session files |
| Persistent sessions | Session reuse | Multi-turn code execution with shared state |
| Session listing | `listCodeInterpreterSessions` | Manage active sessions |
