# Spring AI Bedrock AgentCore Browser

Spring AI integration with Amazon Bedrock AgentCore Browser. Headless browser automation for web page navigation, content extraction, screenshots, and page interaction using Playwright over CDP.

## Features

- Browse web pages and extract text content
- Take screenshots with session-scoped storage
- Click elements, fill forms, execute JavaScript
- Configurable tool descriptions for LLM
- TTL-based cache cleanup

## Quick Start

Add the dependency:

```xml
<dependency>
    <groupId>org.springaicommunity</groupId>
    <artifactId>spring-ai-bedrock-agentcore-browser</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

Inject the tool provider:

```java
@Service
public class ChatService {

    private final ChatClient chatClient;
    private final BrowserScreenshotStore screenshotStore;

    public ChatService(
            ChatClient.Builder chatClientBuilder,
            @Qualifier("browserToolCallbackProvider") ToolCallbackProvider browserTools,
            BrowserScreenshotStore screenshotStore) {

        this.screenshotStore = screenshotStore;
        this.chatClient = chatClientBuilder
            .defaultToolCallbacks(browserTools)
            .build();
    }

    public Flux<String> chat(String prompt, String sessionId) {
        return chatClient.prompt()
            .user(prompt)
            .toolContext(Map.of(BrowserScreenshotStore.SESSION_ID_KEY, sessionId))
            .stream().content()
            .concatWith(Flux.defer(() -> appendScreenshots(sessionId)));
    }

    private Flux<String> appendScreenshots(String sessionId) {
        List<BrowserScreenshot> screenshots = screenshotStore.retrieve(sessionId);
        if (screenshots == null || screenshots.isEmpty()) {
            return Flux.empty();
        }
        StringBuilder sb = new StringBuilder();
        for (BrowserScreenshot s : screenshots) {
            sb.append("\n\n![Screenshot of ").append(s.url()).append("](")
              .append(s.toDataUrl()).append(")");
        }
        return Flux.just(sb.toString());
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
agentcore.browser.session-timeout-seconds=900
agentcore.browser.browser-identifier=aws.browser.v1
agentcore.browser.viewport-width=1456
agentcore.browser.viewport-height=819
agentcore.browser.max-content-length=10000
agentcore.browser.screenshot-ttl-seconds=300

# Custom tool descriptions (optional)
agentcore.browser.browse-url-description=...
agentcore.browser.screenshot-description=...
agentcore.browser.click-description=...
agentcore.browser.fill-description=...
agentcore.browser.evaluate-description=...
```

## Integration Test

```bash
AGENTCORE_IT=true mvn verify -pl spring-ai-bedrock-agentcore-browser
```

## License

Apache License 2.0
