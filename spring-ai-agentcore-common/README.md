# Spring AI AgentCore Common

Shared utilities for Spring AI AgentCore modules. Automatically tags all AWS SDK clients with a `spring-ai-agentcore/<version>` User-Agent identifier via the `sdk.ua.appId` system property.

## How It Works

`AgentCoreUserAgentInterceptor` is registered as an AWS SDK `ExecutionInterceptor` through the Java service loader. When any SDK client is created, the interceptor's static initializer calls `UserAgentProvider.configure()`, which sets (or appends to) the `sdk.ua.appId` system property. No application code or configuration is required — adding this module as a dependency is sufficient.

## Key Classes

| Class | Purpose |
|-------|---------|
| `UserAgentProvider` | Sets the `sdk.ua.appId` system property; appends to any existing value; idempotent |
| `AgentCoreUserAgentInterceptor` | SDK `ExecutionInterceptor` auto-loaded via service loader |

## License

Apache License 2.0
