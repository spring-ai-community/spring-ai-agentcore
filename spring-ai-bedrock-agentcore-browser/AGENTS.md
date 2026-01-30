# AGENTS.md

Context for AI coding assistants working on this module.

## Module Overview

Spring AI integration with Amazon Bedrock AgentCore Browser. Provides headless browser automation for web page navigation, content extraction, screenshots, and page interaction using Playwright over CDP (Chrome DevTools Protocol).

## Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                       BrowserTools                              │
├─────────────────────────────────────────────────────────────────┤
│  browseUrl(url) → String                                        │
│  takeScreenshot(url, ToolContext) → String (metadata)           │
│  clickElement(url, selector) → String                           │
│  fillForm(url, selector, value) → String                        │
│  evaluateScript(url, script) → String                           │
└───────────────────────────┬─────────────────────────────────────┘
                            │ uses
                            ▼
┌─────────────────────────────────────────────────────────────────┐
│                   AgentCoreBrowserClient                        │
├─────────────────────────────────────────────────────────────────┤
│  browseAndExtract(url) → String                                 │
│  screenshotBytes(url) → byte[]                                  │
│  click(url, selector) → String                                  │
│  fill(url, selector, value) → String                            │
│  evaluate(url, script) → String                                 │
└───────────────────────────┬─────────────────────────────────────┘
                            │ uses
                            ▼
┌─────────────────────────────────────────────────────────────────┐
│                 BedrockAgentCoreClient (SDK)                    │
├─────────────────────────────────────────────────────────────────┤
│  startBrowserSession(request) → response                        │
│  stopBrowserSession(request)                                    │
└─────────────────────────────────────────────────────────────────┘
```

## Key Classes

| Class | Purpose |
|-------|---------|
| `AgentCoreBrowserAutoConfiguration` | Spring Boot auto-config with `ToolCallbackProvider` |
| `AgentCoreBrowserClient` | Low-level SDK wrapper with SigV4 WebSocket signing |
| `AgentCoreBrowserConfiguration` | Config properties with default constants |
| `BrowserTools` | Tool implementation logic (no annotations) |
| `BrowserScreenshotStore` | Session-scoped screenshot storage with Caffeine cache |
| `BrowserScreenshot` | Record for screenshot data with defensive copy |
| `BrowserOperationException` | Exception for browser failures |
| `BrowseUrlRequest`, `ScreenshotRequest`, etc. | Input schema records for tools |

## Design Decisions

1. **ToolCallbackProvider pattern** - Programmatic tool registration with configurable descriptions
2. **Playwright over CDP** - Uses Playwright's `connectOverCDP()` for remote browser control
3. **SigV4 WebSocket signing** - AWS authentication for secure WebSocket connection
4. **Ephemeral sessions** - Each tool call creates/destroys a session
5. **Screenshot store** - Screenshots stored in Caffeine cache, appended to response after stream
6. **Lazy Playwright init** - Playwright instance created on first use, reused across calls
7. **Thread-safe screenshot store** - Uses `CopyOnWriteArrayList` for concurrent access
8. **Consistent error handling** - All tools return "Error: ..." strings on failure

## Request Flow

```
1. User: "Take a screenshot of example.com"
2. ChatService passes sessionId via toolContext
3. LLM calls takeScreenshot tool
4. BrowserTools.takeScreenshot():
   a. Extract sessionId from toolContext
   b. client.screenshotBytes(url) → PNG bytes
   c. screenshotStore.store(sessionId, screenshot)
   d. Return metadata to LLM: "Screenshot captured: 16752 bytes, 1456x819"
5. Memory stores: user message + LLM response (NO screenshot bytes)
6. ChatService.appendScreenshots(sessionId)
7. User sees: LLM response + embedded image
```

## Configuration

```properties
agentcore.browser.session-timeout-seconds=900
agentcore.browser.browser-identifier=aws.browser.v1
agentcore.browser.viewport-width=1456
agentcore.browser.viewport-height=819
agentcore.browser.max-content-length=10000
agentcore.browser.screenshot-ttl-seconds=300
agentcore.browser.browse-url-description=...
agentcore.browser.screenshot-description=...
agentcore.browser.click-description=...
agentcore.browser.fill-description=...
agentcore.browser.evaluate-description=...
```

## Build & Test

```bash
# Compile
mvn compile -pl spring-ai-bedrock-agentcore-browser

# Format (required before commit)
mvn spring-javaformat:apply -pl spring-ai-bedrock-agentcore-browser

# Integration test (requires AWS credentials)
AGENTCORE_IT=true mvn verify -pl spring-ai-bedrock-agentcore-browser
```

## Not Implemented

| Feature | Use Case |
|---------|----------|
| Session reuse | Multi-page navigation with shared state |
| Cookie handling | Authenticated pages |
| File download | Retrieve files from pages |
| PDF generation | Save pages as PDF |
