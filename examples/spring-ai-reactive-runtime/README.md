# Spring AI AgentCore Reactive Runtime

This example runs the AgentCore runtime on a **reactive (Netty) server** instead of the
default servlet stack, and shows that the reactive rate-limiting filter from
`spring-ai-agentcore-runtime-starter` applies automatically.

## How it runs reactively

`spring-ai-agentcore-runtime-starter` brings `spring-boot-starter-web` (servlet + Tomcat)
transitively. When `DispatcherServlet` is on the classpath Spring Boot always selects the
**servlet** stack, even if WebFlux is also present. To run on Netty, exclude the servlet
starter and add WebFlux:

```xml
<dependency>
    <groupId>org.springaicommunity</groupId>
    <artifactId>spring-ai-agentcore-runtime-starter</artifactId>
    <exclusions>
        <exclusion>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </exclusion>
    </exclusions>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-webflux</artifactId>
</dependency>
```

With `DispatcherServlet` gone, Spring Boot deduces `REACTIVE` and starts Netty. The
starter's `@ConditionalOnWebApplication(type = REACTIVE)` configuration then wires
`ReactiveRateLimitingWebFilter` in place of the servlet `RateLimitingFilter`.

## What this example shows

- The full AgentCore runtime (`/invocations`, `/ping`) on a reactive server.
- A streaming invocations endpoint returning `Flux<String>` (`ReactiveInvocationsController`).
- Per-client throttling applied by the reactive filter, configured via:

  ```properties
  agentcore.throttle.invocations-limit=2
  agentcore.throttle.ping-limit=3
  ```

`ReactiveThrottlingTests` boots the app on Netty and asserts that `DispatcherServlet` is
absent, the reactive filter bean is wired (and the servlet one is not), and `/ping` returns
`429` once the per-client limit is exceeded.

## API endpoints

- `POST /invocations` — streaming AI response (`text/event-stream`).
- `GET /ping` — built-in health check from the starter.

## Requirements

- Java 21+
- Spring Boot 4.x
- Spring AI
- Amazon Bedrock access (to call the model at `POST /invocations`)
