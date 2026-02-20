# AGENTS.md

Context for AI coding assistants working on this module.

## Module Overview

Spring AI integration with Amazon AgentCore Browser. Provides headless browser automation for web page navigation, content extraction, screenshots, and page interaction using Playwright over CDP (Chrome DevTools Protocol).

## Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                       BrowserTools                              │
├─────────────────────────────────────────────────────────────────┤
│  browseUrl(url) → String                                        │
│  takeScreenshot(url) → String (metadata)                        │
│  clickElement(url, selector) → String                           │
│  fillForm(url, selector, value) → String                        │
│  evaluateScript(url, script) → String                           │
└───────────────────────────┬─────────────────────────────────────┘
                            │ uses BrowserClient interface
                            ▼
              ┌─────────────┴─────────────┐
              │                           │
┌─────────────┴───────────┐ ┌─────────────┴───────────┐
│  AgentCoreBrowserClient │ │   LocalBrowserClient    │
│  (mode=agentcore)       │ │   (mode=local)          │
├─────────────────────────┤ ├─────────────────────────┤
│  SigV4 + CDP over WS   │ │  Local Chromium launch  │
│  AgentCore sessions     │ │  No AWS dependencies    │
└───────────┬─────────────┘ └─────────────────────────┘
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
| `BrowserClient` | Interface for browser operations (agentcore or local) |
| `AgentCoreBrowserClient` | Remote implementation with SigV4 WebSocket signing |
| `LocalBrowserClient` | Local implementation using Playwright Chromium launch |
| `AgentCoreBrowserConfiguration` | Config properties with default constants |
| `BrowserTools` | Tool implementation with Reactor context session handling and optional category |
| `BrowserArtifacts` | Helper for creating screenshots with metadata |
| `PageContentFormatter` | Package-private utility for formatting extracted page content |
| `BrowserOperationException` | Exception for browser failures |
| `BrowseUrlRequest`, `ScreenshotRequest`, etc. | Input schema records for tools |

## Design Decisions

1. **ToolCallbackProvider pattern** - Programmatic tool registration with configurable descriptions
2. **Playwright over CDP** - Uses Playwright's `connectOverCDP()` for remote browser control
3. **SigV4 WebSocket signing** - AWS authentication for secure WebSocket connection
4. **Ephemeral sessions** - Each tool call creates/destroys a session
5. **Artifact store** - Screenshots stored in shared `ArtifactStore<GeneratedFile>` with optional category support
6. **Lazy Playwright init** - Playwright managed as Spring `@Bean` with `destroyMethod="close"`; lazy in agentcore mode, eager in local mode
7. **BrowserClient interface** - Abstracts browser operations; `AgentCoreBrowserClient` for remote, `LocalBrowserClient` for local dev
8. **Mode-based auto-configuration** - `@ConditionalOnProperty` switches between agentcore and local implementations
9. **Thread-safe artifact store** - Uses Caffeine cache for concurrent access
10. **Consistent error handling** - All tools return "Error: ..." strings on failure
11. **Reactor context for session ID** - Session ID passed via `ToolCallReactiveContextHolder`, not `ToolContext`, to avoid leaking metadata to MCP tools
12. **Shared content formatting** - `PageContentFormatter` extracts title + body text + truncation logic, shared by both `BrowserClient` implementations

## Session ID Handling

Tool execution happens on `Schedulers.boundedElastic()` thread pool, not the HTTP request thread. `@RequestScope` beans throw `ScopeNotActiveException` because no HTTP request context exists on those threads.

Spring AI's `ToolCallReactiveContextHolder` bridges Reactor context to ThreadLocal:

```java
// ChatService stores sessionId in Reactor context
.contextWrite(ctx -> ctx.put(BrowserTools.SESSION_ID_CONTEXT_KEY, sessionId))

// BrowserTools reads from ToolCallReactiveContextHolder
ContextView ctx = ToolCallReactiveContextHolder.getContext();
String sessionId = ctx.getOrDefault(SESSION_ID_CONTEXT_KEY, DEFAULT_SESSION_ID);
```

## Request Flow

```
1. User: "Take a screenshot of example.com"
2. ChatService stores sessionId in Reactor context via .contextWrite()
3. LLM calls takeScreenshot tool
4. BrowserTools.takeScreenshot():
   a. Read sessionId from ToolCallReactiveContextHolder
   b. client.screenshotBytes(url) → PNG bytes
   c. artifactStore.store(sessionId, screenshot)
   d. Return metadata to LLM: "Screenshot captured: 16752 bytes, 1456x819"
5. Memory stores: user message + LLM response (NO screenshot bytes)
6. ChatService.appendScreenshots(sessionId)
7. User sees: LLM response + embedded image
```

## Configuration

```properties
agentcore.browser.mode=agentcore                # "agentcore" (default) or "local"
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
mvn compile -pl spring-ai-agentcore-browser

# Format (required before commit)
mvn spring-javaformat:apply -pl spring-ai-agentcore-browser

# Local browser tests (no AWS credentials needed)
mvn test -pl spring-ai-agentcore-browser

# Full integration test (requires AWS credentials)
AGENTCORE_IT=true mvn verify -pl spring-ai-agentcore-browser
```

## Integration Tests

**BrowserToolsIT (16 tests, requires AGENTCORE_IT=true):**

| Test | Coverage |
|------|----------|
| `shouldBrowseUrlAndReturnContent` | browseUrl() |
| `shouldBrowseUrlReturnErrorForInvalidUrl` | browseUrl() error |
| `shouldTakeScreenshotAndStoreBySessionId` | takeScreenshot() + store |
| `shouldTakeScreenshotUseDefaultSessionWhenNull` | takeScreenshot() + null session |
| `shouldTakeScreenshotReturnErrorOnFailure` | takeScreenshot() error |
| `shouldRetrieveScreenshotOnlyFromOwnSession` | session isolation |
| `shouldClearScreenshotsAfterRetrieve` | retrieve() clears |
| `shouldHasScreenshotsReturnCorrectly` | hasScreenshots() |
| `shouldScreenshotToDataUrlReturnValidFormat` | BrowserScreenshot.toDataUrl() |
| `shouldIsolateScreenshotsBetweenParallelSessions` | Parallel session isolation |
| `shouldClickElement` | clickElement() |
| `shouldClickElementReturnErrorOnFailure` | clickElement() error |
| `shouldFillForm` | fillForm() |
| `shouldFillFormReturnErrorOnFailure` | fillForm() error |
| `shouldEvaluateScript` | evaluateScript() |
| `shouldEvaluateScriptReturnErrorOnFailure` | evaluateScript() error |

**BrowserChatFlowIT (2 tests, requires AGENTCORE_IT=true):**

| Test | Coverage |
|------|----------|
| `shouldPropagateSessionIdThroughChatClientFlow` | Session ID flows from ChatClient to tool |
| `shouldIsolateSessionsBetweenConcurrentRequests` | Parallel requests maintain session isolation |

**LocalBrowserClientTest (8 tests, no AWS credentials needed):**

| Test | Coverage |
|------|----------|
| `shouldBrowseAndExtract` | browseAndExtract() with local Chromium |
| `shouldTakeScreenshot` | screenshotBytes() + PNG validation |
| `shouldClickElement` | click() |
| `shouldFillFormField` | fill() |
| `shouldEvaluateScript` | evaluate() |
| `shouldThrowForInvalidUrl` | BrowserOperationException on failure |
| `shouldTruncateContent` | maxContentLength truncation |
| `shouldImplementBrowserClientInterface` | Interface contract |

**LocalBrowserToolsIT (2 tests, no AWS credentials needed):**

| Test | Coverage |
|------|----------|
| `shouldWireLocalBrowserClient` | Spring wiring with mode=local |
| `shouldStoreScreenshotUnderSessionId` | Screenshot storage via BrowserTools |

## Not Implemented

| Feature | Use Case |
|---------|----------|
| Session reuse | Multi-page navigation with shared state |
| Cookie handling | Authenticated pages |
| File download | Retrieve files from pages |
| PDF generation | Save pages as PDF |
