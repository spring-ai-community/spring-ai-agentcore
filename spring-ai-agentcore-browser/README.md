# Spring AI AgentCore Browser

Spring AI integration with Amazon AgentCore Browser. Headless browser automation for web page navigation, content extraction, screenshots, and page interaction using Playwright over CDP.

Supports two modes:
- **agentcore** (default) — uses AgentCore Browser managed service
- **local** — uses a locally launched Chromium browser for development and testing

## Features

- Browse web pages and extract text content
- Take screenshots with session-scoped artifact storage
- Click elements, fill forms, execute JavaScript
- Configurable tool descriptions for LLM
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
        <artifactId>spring-ai-agentcore-browser</artifactId>
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
            @Qualifier("browserToolCallbackProvider") ToolCallbackProvider browserTools,
            @Qualifier("browserArtifactStore") ArtifactStore<GeneratedFile> artifactStore) {

        this.artifactStore = artifactStore;
        this.chatClient = chatClientBuilder
            .defaultToolCallbacks(browserTools)
            .build();
    }

    public Flux<String> chat(String prompt, String sessionId) {
        return chatClient.prompt()
            .user(prompt)
            .stream()
            .content()
            .concatWith(Flux.defer(() -> appendScreenshots(sessionId)))
            // Store sessionId in Reactor context for tools
            .contextWrite(ctx -> ctx.put(SessionConstants.SESSION_ID_KEY, sessionId));
    }

    private Flux<String> appendScreenshots(String sessionId) {
        List<GeneratedFile> screenshots = artifactStore.retrieve(sessionId);
        if (screenshots == null || screenshots.isEmpty()) {
            return Flux.empty();
        }
        StringBuilder sb = new StringBuilder();
        for (GeneratedFile s : screenshots) {
            String url = BrowserArtifacts.url(s).orElse("unknown");
            sb.append("\n\n![Screenshot of ").append(url).append("](")
              .append(s.toDataUrl()).append(")");
        }
        return Flux.just(sb.toString());
    }
}
```

## Session ID Handling

Session ID is passed via Reactor context and read using Spring AI's `ToolCallReactiveContextHolder`. This is required because tool execution happens on `Schedulers.boundedElastic()` thread pool, not the HTTP request thread.

```java
// BrowserTools reads session ID from Reactor context using SessionConstants
public class BrowserTools {

    public String takeScreenshot(String url) {
        ContextView ctx = ToolCallReactiveContextHolder.getContext();
        String sessionId = ctx.getOrDefault(SessionConstants.SESSION_ID_KEY, SessionConstants.DEFAULT_SESSION_ID);
        // ... store screenshot by sessionId
    }
}
```

## Tools

| Tool | Parameters | Returns |
|------|------------|---------|
| `browseUrl` | url | Page title + text content |
| `takeScreenshot` | url | Metadata (bytes stored in cache) |
| `clickElement` | url, selector | Result message |
| `fillForm` | url, selector, value | Result message |
| `evaluateScript` | url, script | Script result |

## Configuration

```properties
# All optional - defaults shown
agentcore.browser.mode=agentcore          # or "local" for local Chromium
agentcore.browser.session-timeout-seconds=900
agentcore.browser.browser-identifier=aws.browser.v1
agentcore.browser.viewport-width=1456
agentcore.browser.viewport-height=819
agentcore.browser.max-content-length=10000
agentcore.browser.screenshot-ttl-seconds=300
agentcore.browser.artifact-store-max-size=10000  # max sessions in artifact store

# Custom tool descriptions (optional)
agentcore.browser.browse-url-description=...
agentcore.browser.screenshot-description=...
agentcore.browser.click-description=...
agentcore.browser.fill-description=...
agentcore.browser.evaluate-description=...
```

## Artifact Store

Screenshots are stored in a session-scoped `ArtifactStore<GeneratedFile>` from the [artifact-store module](../spring-ai-agentcore-artifact-store/README.md).

Helper methods in `BrowserArtifacts`:
- `BrowserArtifacts.url(file)` - Extract URL from screenshot metadata
- `BrowserArtifacts.width(file)` / `height(file)` - Get dimensions

## Local Development

For local development without AWS credentials, use local mode:

```properties
agentcore.browser.mode=local
```

This launches a headless Chromium browser locally via Playwright. Same tools, same behavior — just no AgentCore service dependency. Useful for development and testing.

## Example Application

See [`examples/spring-ai-browser`](../examples/spring-ai-browser) for a minimal standalone app that browses a URL, extracts content, takes a screenshot, and saves it to a local folder. Defaults to local mode.

## Integration Test

```bash
# AgentCore mode (requires AWS credentials)
AGENTCORE_IT=true mvn verify -pl spring-ai-agentcore-browser

# Local mode (no AWS credentials needed)
mvn test -pl spring-ai-agentcore-browser
```

## License

Apache License 2.0
