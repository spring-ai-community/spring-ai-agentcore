# Spring AI AgentCore Identity

Spring Boot integration for the Amazon Bedrock AgentCore Identity **data plane**. It retrieves workload access tokens, API keys, and OAuth 2.0 credentials and integrates workload tokens delivered by AgentCore Runtime with synchronous and reactive `@AgentCoreInvocation` methods.

## Dependency

```xml
<dependency>
    <groupId>org.springaicommunity</groupId>
    <artifactId>spring-ai-agentcore-identity</artifactId>
</dependency>
```

The runtime starter is optional. Add it when the application is hosted on AgentCore Runtime and should consume the automatically delivered workload access token.

## Configuration

The auto-configured `BedrockAgentCoreClient` uses the standard AWS SDK region and credentials provider chains. A custom `BedrockAgentCoreClient` bean overrides it.

## Workload access tokens

```java
String jwtToken = identity.getWorkloadAccessTokenForJwt(jwt, workloadName);
String userToken = identity.getWorkloadAccessTokenForUserId(userId, workloadName);
String iamToken = identity.getWorkloadAccessToken(workloadName);
```

JWT identification is recommended for production because AgentCore validates the JWT. User-ID identification treats the ID as an opaque caller-supplied value and therefore requires appropriately restricted IAM policies.

## API keys

Use an explicit workload token outside AgentCore Runtime:

```java
String apiKey = identity.getApiKey(workloadAccessToken, credentialProviderName);
```

Inside an `@AgentCoreInvocation`, the ambient overload uses the workload token from the Runtime request header:

```java
@AgentCoreInvocation
String invoke(Request request) {
    return identity.getApiKey(credentialProviderName);
}
```

The ambient token is cleared after every invocation. For a reactive `@AgentCoreInvocation`, Identity captures it into the returned publisher's Reactor `Context` under `WorkloadAccessTokenAccessor.KEY`. Identity deliberately does not enable Reactor's JVM-global automatic context-propagation hook or register an accessor with Micrometer's global registry. Consequently, the ambient `getApiKey(credentialProviderName)` overload is only available on the synchronous invocation thread; it is not transparently restored on scheduler threads.

`AgentCoreIdentityTemplate` uses the synchronous AWS client. Call it directly from normal servlet handlers. In WebFlux or another reactive pipeline, read the token from Reactor `Context`, pass it explicitly, and move the blocking call off the event loop:

```java
Mono<String> apiKey = Mono.deferContextual(context -> Mono
    .fromCallable(() -> identity.getApiKey(
        context.get(WorkloadAccessTokenAccessor.KEY), credentialProviderName))
    .subscribeOn(Schedulers.boundedElastic()));
```

AWS SDK service and client exceptions propagate unchanged so applications retain the original AWS error details and retry metadata.

## OAuth 2.0

### M2M and on-behalf-of token exchange

```java
String accessToken = identity.getOauthAccessToken(oauth -> oauth
    .workloadIdentityToken(workloadAccessToken)
    .resourceCredentialProviderName(credentialProviderName)
    .oauth2Flow(Oauth2FlowType.M2_M)
    .scopes("read"));
```

For on-behalf-of token exchange, use `Oauth2FlowType.ON_BEHALF_OF_TOKEN_EXCHANGE` and configure `resources(...)` and `audiences(...)` when required by the provider.

### User federation

USER_FEDERATION can return either an access token or an authorization session. Use the complete response API:

```java
GetResourceOauth2TokenResponse response = identity.getOauthToken(oauth -> oauth
    .workloadIdentityToken(workloadAccessToken)
    .resourceCredentialProviderName(credentialProviderName)
    .oauth2Flow(Oauth2FlowType.USER_FEDERATION)
    .scopes("calendar.read")
    .resourceOauth2ReturnUrl("https://app.example.com/oauth/callback")
    .customState(csrfState));

if (response.authorizationUrl() != null) {
    // Send the user to response.authorizationUrl() and retain response.sessionUri().
}
```

After validating the browser session and CSRF state in the HTTPS callback, bind the authorization session to the same user:

```java
identity.completeResourceTokenAuth(response.sessionUri(), originalUserJwt);
// Or: identity.completeResourceTokenAuthForUserId(response.sessionUri(), originalUserId);
```

Call `getOauthToken(...)` again after authorization to obtain the access token. Authorization URLs and session URIs are short-lived; do not expose workload access tokens or downstream access tokens to browser code.

## Scope

This module wraps the AgentCore Identity data-plane operations:

- `GetWorkloadAccessToken`
- `GetWorkloadAccessTokenForJWT`
- `GetWorkloadAccessTokenForUserId`
- `GetResourceApiKey`
- `GetResourceOauth2Token`
- `CompleteResourceTokenAuth`

Workload identity and credential-provider administration is intentionally control-plane infrastructure. Create and update workload identities, allowed OAuth return URLs, API-key providers, and OAuth providers with the AWS CLI, CloudFormation/CDK/Terraform, or `BedrockAgentCoreControlClient`.
