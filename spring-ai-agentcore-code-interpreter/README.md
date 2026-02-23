# Spring AI AgentCore Code Interpreter

Spring AI integration with Amazon AgentCore Code Interpreter. Execute Python, JavaScript, and TypeScript code in a secure sandbox with automatic file retrieval.

## Features

- Execute code in Python, JavaScript, or TypeScript
- Pre-installed libraries: numpy, pandas, matplotlib (Python)
- Automatic file retrieval (charts, CSVs, PDFs)
- Session-scoped artifact storage for multi-user environments
- Configurable tool description for LLM
- TTL-based artifact cache cleanup
- Thread-safe multi-session support

## Quick Start

Add the BOM and dependency:

```xml
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>org.springaicommunity</groupId>
            <artifactId>spring-ai-agentcore-bom</artifactId>
            <version>${version}</version>  <!-- Use latest: 1.0.0-RC2, 1.0.0-RC3, etc. -->
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>

<dependencies>
    <dependency>
        <groupId>org.springaicommunity</groupId>
        <artifactId>spring-ai-agentcore-code-interpreter</artifactId>
    </dependency>
</dependencies>
```

Inject the tool provider:

```java
@Service
public class ChatService {

    private final ChatClient chatClient;
    private final ArtifactStore<GeneratedFile> artifactStore;

    public ChatService(
            ChatClient.Builder chatClientBuilder,
            @Qualifier("codeInterpreterToolCallbackProvider") ToolCallbackProvider codeInterpreterTools,
            @Qualifier("codeInterpreterArtifactStore") ArtifactStore<GeneratedFile> artifactStore) {

        this.artifactStore = artifactStore;
        this.chatClient = chatClientBuilder
            .defaultToolCallbacks(codeInterpreterTools)
            .build();
    }

    public Flux<String> chat(String prompt, String sessionId) {
        return chatClient.prompt()
            .user(prompt)
            .stream().content()
            .concatWith(Flux.defer(() -> appendGeneratedFiles(sessionId)))
            // Store sessionId in Reactor context for tools
            .contextWrite(ctx -> ctx.put(SessionConstants.SESSION_ID_KEY, sessionId));
    }

    private Flux<String> appendGeneratedFiles(String sessionId) {
        List<GeneratedFile> files = artifactStore.retrieve(sessionId);
        if (files == null || files.isEmpty()) {
            return Flux.empty();
        }
        StringBuilder sb = new StringBuilder();
        for (GeneratedFile file : files) {
            if (file.isImage()) {
                sb.append("\n\n![").append(file.name()).append("](")
                  .append(file.toDataUrl()).append(")");
            } else {
                sb.append("\n\n[Download ").append(file.name()).append("](")
                  .append(file.toDataUrl()).append(")");
            }
        }
        return Flux.just(sb.toString());
    }
}
```

## Session Context

Session ID is propagated via `ToolCallReactiveContextHolder` (not `@RequestScope`) because tool execution happens on `Schedulers.boundedElastic()` threads, not HTTP request threads.

**How it works:**
1. `ChatService.chat()` stores sessionId in Reactor context via `.contextWrite()`
2. Spring AI captures Reactor context before tool execution
3. Spring AI stores it in ThreadLocal via `ToolCallReactiveContextHolder.setContext(ctx)`
4. Tools read sessionId from `ToolCallReactiveContextHolder.getContext()` using `SessionConstants.SESSION_ID_KEY`
5. Spring AI clears ThreadLocal in `finally` block

**Multi-user support:**
- AgentCore Runtime (one instance per user): uses `SessionConstants.DEFAULT_SESSION_ID`
- EKS/ECS (shared instance): uses conversation `sessionId` from Reactor context

## Artifact Store

Generated files are stored in a session-scoped `ArtifactStore<GeneratedFile>` from the [artifact-store module](../spring-ai-agentcore-artifact-store/README.md).

Helper methods in `CodeInterpreterArtifacts`:
- `CodeInterpreterArtifacts.sourcePath(file)` - Extract source path from metadata

## Configuration

```properties
# All optional - defaults shown
agentcore.code-interpreter.session-timeout-seconds=900
agentcore.code-interpreter.async-timeout-seconds=300
agentcore.code-interpreter.file-store-ttl-seconds=300
agentcore.code-interpreter.artifact-store-max-size=10000  # max sessions in artifact store
agentcore.code-interpreter.code-interpreter-identifier=aws.codeinterpreter.v1
agentcore.code-interpreter.tool-description=Custom tool description...
```

## Output Format

- **Images**: Inline preview using data URL (`![name](data:image/png;base64,...)`)
- **Other files**: Download link using data URL (`[Download name](data:mime/type;base64,...)`)

## Integration Test

```bash
AGENTCORE_IT=true mvn verify -pl spring-ai-agentcore-code-interpreter
```

## License

Apache License 2.0
