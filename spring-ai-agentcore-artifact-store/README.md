# Spring AI AgentCore Artifact Store

Shared session-scoped artifact storage for AgentCore modules. Provides thread-safe, TTL-based caching for generated files like screenshots, charts, and documents.

## Features

- Session-scoped artifact storage
- Thread-safe multi-session support
- TTL-based automatic cleanup
- Configurable max size
- Destructive and non-destructive retrieval

## Usage

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
        <artifactId>spring-ai-agentcore-artifact-store</artifactId>
    </dependency>
</dependencies>
```

### ArtifactStore Interface

```java
public interface ArtifactStore<T> {
    void store(String sessionId, T artifact);
    List<T> retrieve(String sessionId);  // destructive read
    List<T> peek(String sessionId);      // non-destructive
    int count(String sessionId);
    void clear(String sessionId);
    boolean hasArtifacts(String sessionId);
}
```

### CaffeineArtifactStore

Default implementation using Caffeine cache with TTL expiration:

```java
// Create with 5-minute TTL and 10,000 max sessions
ArtifactStore<GeneratedFile> store = new CaffeineArtifactStore<>(
    Duration.ofSeconds(300),
    10_000
);

// Store artifacts
store.store(sessionId, generatedFile);

// Retrieve and clear (destructive)
List<GeneratedFile> files = store.retrieve(sessionId);

// View without clearing (non-destructive)
List<GeneratedFile> files = store.peek(sessionId);
```

### GeneratedFile Record

Immutable artifact record for storing file data:

```java
public record GeneratedFile(
    String mimeType,
    byte[] data,
    String name,
    Map<String, String> metadata
) {
    public String toDataUrl();      // data:mime/type;base64,...
    public boolean isImage();       // checks mime type
}
```

### SessionConstants

Constants for session ID handling in Reactor context:

```java
// Key for storing session ID in Reactor context
SessionConstants.SESSION_ID_KEY

// Default session ID when none provided
SessionConstants.DEFAULT_SESSION_ID
```

## Thread Safety

`CaffeineArtifactStore` is fully thread-safe. All operations are safe for concurrent access from multiple threads. The underlying Caffeine cache handles synchronization internally.

## Configuration

When used via browser or code interpreter modules, configure via their properties:

```properties
# Browser module
agentcore.browser.screenshot-ttl-seconds=300
agentcore.browser.artifact-store-max-size=10000

# Code interpreter module
agentcore.code-interpreter.file-store-ttl-seconds=300
agentcore.code-interpreter.artifact-store-max-size=10000
```

## License

Apache License 2.0
