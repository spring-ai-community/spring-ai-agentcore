# Spring AI AgentCore Payments

Spring Boot integration for the [Amazon Bedrock AgentCore Payments](https://docs.aws.amazon.com/bedrock-agentcore/latest/devguide/payments.html) **data plane**. It lets Spring AI agents pay for paid APIs, MCP servers and content that answer `HTTP 402 Payment Required`, using the [x402](https://www.x402.org/) protocol (versions 1 and 2) and stablecoin wallets, within the spending limits of a payment session.

> **Preview.** x402 only; APIs and properties may change in future releases. See [Next steps](#next-steps).

## Dependency

```xml
<dependency>
    <groupId>org.springaicommunity</groupId>
    <artifactId>spring-ai-agentcore-payments</artifactId>
</dependency>
```

## Prerequisites

Payment resources are created outside the application (AgentCore console, CLI, or AWS SDK control plane):

1. **Payment manager** with a **payment connector** (Coinbase CDP or Stripe Privy credentials, stored in AgentCore Identity).
2. **Payment instrument** (embedded crypto wallet) per user. The end user must fund it and grant signing permission, for Coinbase via the `redirectUrl` returned on creation.
3. **Payment session** per conversation or task: maximum spend (USD) and expiry (15–480 minutes), created by the application with `AgentCorePaymentsTemplate.createPaymentSession(...)`.

## Configuration

The module is enabled by setting the payment manager ARN.

| Property | Default | Description |
|---|---|---|
| `agentcore.payments.payment-manager-arn` | — | Payment manager ARN; enables the module |
| `agentcore.payments.user-id` | — | Fallback user for calls without one in the tool context or request attribute. Local runs and tests only: in production, every such call would pay from this user's wallet (logged as WARN at startup) |
| `agentcore.payments.payment-instrument-id` | — | Default payment instrument (wallet) |
| `agentcore.payments.payment-session-id` | — | Fallback payment session, local runs and tests only (logged as WARN at startup); applications create sessions per conversation (see [Payment sessions](#payment-sessions-budgets)) |
| `agentcore.payments.session.max-spend` | `1.00` | Budget in USD of sessions created by `PaymentSessionRegistry` |
| `agentcore.payments.session.expiry` | `60m` | Lifetime of those sessions, 15 to 480 minutes |
| `agentcore.payments.session.max-entries` | `10000` | Maximum number of sessions the registry remembers |
| `agentcore.payments.agent-name` | — | Agent name sent with data plane calls |
| `agentcore.payments.network-preferences` | Solana / Base mainnets first, then testnets | Order used to choose between the networks a merchant accepts |
| `agentcore.payments.permit2-allowance-limit` | — | Permit2 allowance for the x402 `upto` scheme, in the asset's smallest unit |
| `agentcore.payments.post-payment-delay` | `3s` | Blocking wait before the paid request is sent again. x402 authorizations carry a `validAfter` time; merchants that settle on chain right away can reject an authorization that is not valid yet. `0` disables the wait |
| `agentcore.payments.paid-http-tool.enabled` | `false` | Register the `paidHttpRequest` tool; requires `allowed-hosts` |
| `agentcore.payments.paid-http-tool.allowed-hosts` | — | Hosts the tool may call and pay: exact hostnames (case-insensitive, port ignored) or `*.example.com` for subdomains. Startup fails if the tool is enabled without it |
| `agentcore.payments.paid-http-tool.max-response-length` | `10000` | Maximum response body returned to the model |

The auto-configured `BedrockAgentCoreClient` uses the standard AWS SDK region and credentials provider chains. A custom `BedrockAgentCoreClient` bean overrides it.

## How payments are made

The model never decides to pay. It calls a tool, and the HTTP call behind that tool pays when the server answers `402`: the payment is signed through AgentCore Payments within the session budget and the request is sent again with the payment header (`X-PAYMENT` for x402 v1, `PAYMENT-SIGNATURE` for v2). A request is paid at most once; a second `402` means the server rejected the payment and fails instead of paying twice.

There are three ways to put this into your agent:

| | Way | Use it for |
|---|---|---|
| 1 | **HTTP client interceptor** (recommended) | Your own tools for known paid APIs, built on Spring `RestClient` / `RestTemplate` / `@HttpExchange` |
| 2 | **`paidHttpRequest` tool** | Agents that fetch arbitrary URLs chosen by the model |
| 3 | **Template or tool wrapper** | Tools using any other HTTP client (OkHttp, JDK `HttpClient`, vendor SDKs) |

```
 1. interceptor    model ──► your tool ──► RestClient + interceptor ──► paid API
 2. paidHttpRequest  model ──► paidHttpRequest ──► RestClient + interceptor ──► any URL
 3. other clients  model ──► your tool ──► any HTTP client ──► paid API
                                │  402 → template.generatePaymentHeader(...) → retry
                                └─ or: return PAYMENT_REQUIRED marker, PaymentToolCallback pays
```

Whatever the way, every tool that can reach a paid endpoint must pay this way. Do not give the agent an additional, non-paying fetch tool: a `402` there is not paid and the model cannot recover from it reliably.

### 1. HTTP client interceptor (recommended)

`AgentCorePaymentsClientHttpRequestInterceptor` is auto-configured. Add it to the `RestClient` your tools use; the tools contain no payment code and the model only sees domain tools:

```java
@Bean
RestClient marketApi(AgentCorePaymentsClientHttpRequestInterceptor payments) {
    return RestClient.builder()
        .baseUrl("https://api.market.example")
        .requestInterceptor(payments)        // pays 402 responses of this client
        .build();
}

@Tool(description = "Get today's market recap")
String getMarketRecap(ToolContext toolContext) {
    return marketApi.get()
        .uri("/market-recap")
        // optional: who pays for this call; otherwise the agentcore.payments.* defaults apply
        .attribute(AgentCorePaymentsClientHttpRequestInterceptor.PAYMENT_CONTEXT_ATTRIBUTE,
                paymentContextResolver.resolve(toolContext))
        .retrieve()
        .body(String.class);
}
```

The same interceptor works with `RestTemplate` and with HTTP interface clients (`@HttpExchange`) backed by `RestClient`. Payment failures are thrown as `PaymentException` from the call; inside a tool, Spring AI turns them into a `ToolExecutionException` (see [Errors](#errors)).

### 2. `paidHttpRequest` tool

The `AgentCorePaymentsTools` bean provides tools to inspect the current wallet and budget and, when enabled, a generic fetch tool built on the interceptor:

| Tool | Description |
|---|---|
| `paidHttpRequest` | Off by default. Calls an HTTP endpoint and returns status code, headers and body; `402` responses are paid automatically. Only hosts in `allowed-hosts` are called; external addresses only (Spring Boot `InetAddressFilter.externalAddresses()`); redirects are not followed, so a merchant cannot redirect a payment to another host; methods GET, POST, PUT, PATCH, DELETE, HEAD; `Set-Cookie` headers are not returned to the model |
| `getPaymentInstrument` | Network, address and status of the current wallet |
| `listPaymentInstruments` | Wallets of the current user |
| `getPaymentInstrumentBalance` | Token balance of the current wallet on a chain |
| `getPaymentSession` | Spending limit, remaining budget and expiry of the current session |

The query tools only read the instrument and session of the current `PaymentContext`; the model cannot name other ones.

```properties
agentcore.payments.paid-http-tool.enabled=true
agentcore.payments.paid-http-tool.allowed-hosts=api.example.com,*.merchant.io
```

```java
@Bean
ChatClient chatClient(ChatClient.Builder builder, AgentCorePaymentsTools paymentsTools) {
    return builder.defaultToolCallbacks(paymentsTools.toolCallbacks()).build();
}
```

`AgentCorePaymentsTools` is deliberately not a `ToolCallbackProvider` bean: Spring AI publishes such beans, for example through the MCP server starter, which would offer paying tools to outside MCP clients.

Use the tool with care: the model chooses the URLs, builds requests without an API schema, and a prompt-injected page can make it call an endpoint that asks for payment. The allowlist limits payments to known merchants and the session budget limits the amount. Prefer way 1 for known APIs.

### 3. Other HTTP clients

**Call the template** when your code receives the `402`:

```java
PaymentHeader header = payments.generatePaymentHeader(
        new PaymentContext(userId, instrumentId, sessionId),
        new PaymentRequired(402, responseHeaders, responseBody));
// send the request again with header.name(): header.value()
// if the server answers 402 again, it rejected the payment: do not pay a second time
```

Every call is a new purchase with a random idempotency token. If your own code retries one purchase, for example after a timeout, pass the same token in each attempt: `generatePaymentHeader(context, response, clientToken)`. Never reuse a token for a second purchase.

`PaymentToolCallback` (`AgentCorePaymentsToolCallbacks.wrap(...)`) is a fallback for tools that cannot use the interceptor: the tool returns `PAYMENT_REQUIRED: {"statusCode":402,"headers":{...},"body":...}` on `402` and must declare a `headers` object in its input, which receives the payment header; tools without it are not paid.

### Who pays: user, instrument and session

All ways resolve a `PaymentContext` (user, payment instrument, payment session). Defaults come from the `agentcore.payments.*` properties; tool context values override them per request:

```java
chatClient.prompt(question)
    .toolContext(Map.of(
        PaymentContext.USER_ID_KEY, userId,
        PaymentContext.PAYMENT_INSTRUMENT_ID_KEY, instrumentId,
        PaymentContext.PAYMENT_SESSION_ID_KEY, sessionId))
    .call()
    .content();
```

For other lookups (database, request headers) define a `PaymentContextResolver` bean.

### Payment sessions (budgets)

A payment session is the budget of a conversation or task: a maximum spend and an expiry, enforced by AgentCore Payments before signing. Sessions are created by application code, never by the model and never implicitly while paying: without a session, a payment fails.

`PaymentSessionRegistry` creates and remembers sessions per user, AgentCore Runtime session and alias, so all invocations of a conversation pay from the same budget and parallel conversations never share one:

```java
@AgentCoreInvocation
String chat(String question, AgentCoreContext context) {
    String runtimeSessionId = context.getHeader(AgentCoreHeaders.SESSION_ID);
    String budget = paymentSessions.getOrCreate(userId, runtimeSessionId);   // reused within the conversation
    return chatClient.prompt(question)
        .toolContext(Map.of(PaymentContext.USER_ID_KEY, userId,
                            PaymentContext.PAYMENT_SESSION_ID_KEY, budget))
        .call()
        .content();
}
```

| Need | Call |
|---|---|
| One budget per conversation | `getOrCreate(userId, runtimeSessionId)` (alias `default`, limits from `agentcore.payments.session.*`) |
| Several budgets in a conversation | `getOrCreate(userId, runtimeSessionId, "booking")`, optionally with own `maxSpend` and `expiry` |
| New budget, for example after the user approved more spending | `renew(userId, runtimeSessionId, alias)` |
| Conversation ended | `remove(userId, runtimeSessionId)` (optional; entries also disappear when their session expires) |

The registry knows when a session expires because it sets the expiry itself; it uses the creation time and expiry returned by AgentCore Payments and drops an entry one minute before. An exhausted budget is not renewed automatically. The registry is in memory per application instance, which fits AgentCore Runtime where a runtime session stays on one instance. Applications that keep conversation state themselves can create sessions with `AgentCorePaymentsTemplate.createPaymentSession(...)` and store the id instead.

### Errors

Payment failures are `PaymentException`s: `InsufficientBudgetException` (the session budget is used up), `PaymentSessionExpiredException`, `PaymentConfigurationException` (missing user, instrument or session) or `PaymentException`, for example for a `402` that uses the Machine Payments Protocol, which is not supported yet. Raised inside a tool, they reach Spring AI as `ToolExecutionException`; by default Spring AI returns the message to the model. Set `spring.ai.tools.throw-exception-on-error=true` to handle them in the application.

When the budget is used up, every further payment of the conversation fails until the application grants a new one. Recommended pattern:

```java
try {
    return chatClient.prompt(question).toolContext(context).call().content();
}
catch (ToolExecutionException ex) {
    if (ex.getCause() instanceof InsufficientBudgetException) {
        // ask the user to approve more spending, then:
        paymentSessions.renew(userId, runtimeSessionId, PaymentSessionRegistry.DEFAULT_ALIAS);
    }
    throw ex;
}
```

AgentCore Payments reports a used-up budget only in the error message ("Insufficient budget for session ..."). Other validation errors, for example a wallet without enough funds, propagate unchanged as AWS SDK `ValidationException` and are logged at DEBUG.

`AgentCorePaymentsTemplate` is thread-safe and uses the synchronous AWS client. AWS SDK exceptions propagate unchanged, except budget and expiry rejections of `ProcessPayment`.

## End-to-end test (Stripe Privy, Base Sepolia testnet)

`AgentCorePaymentsIT` pays a real x402 merchant through AgentCore Payments with testnet USDC (no real money). It follows the AWS [quick start](https://docs.aws.amazon.com/bedrock-agentcore/latest/devguide/payments-getting-started.html) with Stripe Privy as wallet provider. Coinbase works the same way but additionally requires the AWS Marketplace subscription *Coinbase Wallets for AgentCore Payments*.

### Components

```
  ONE-TIME SETUP (developer)                         END USER (once per wallet)
  +---------------------------------------------+   +-------------------------------+
  | IAM service role                            |   | Privy frontend (localhost)    |
  |   assumed by AgentCore Payments             |   |   1. log in with email        |
  | AgentCore Identity                          |   |   2. "Connect agent"          |
  |   Credential Provider (StripePrivy)         |   |      = delegate signing to    |
  |   secrets stored in Secrets Manager         |   |        the authorization key  |
  | Payment Manager  (AWS_IAM)                  |   +-------------------------------+
  |   +-- Payment Connector (StripePrivy)       |   | Circle faucet                 |
  |         +-- Payment Instrument (wallet) ----+-->|   test USDC on Base Sepolia   |
  +---------------------------------------------+   +-------------------------------+

  TEST RUN (AgentCorePaymentsIT)

  +---------------------+                         +---------------------------+
  | RestClient          |  (1) GET /resource      | x402 merchant             |
  |   + AgentCore-      |------------------------>| (Base Sepolia)            |
  |   Payments-         |  (2) 402 + Payment-     |                           |
  |   ClientHttpRequest-|<------------------------|                           |
  |   Interceptor       |      Required header    |                           |
  |                     |                         |                           |
  |                     |  (5) GET /resource +    |                           |
  |                     |      PAYMENT-SIGNATURE  |                           |
  |                     |------------------------>|  (6) facilitator settles  |
  |                     |  (7) 200 + content      |      USDC transfer        |
  |                     |<------------------------|      on chain             |
  +---------------------+                         +---------------------------+
        |        ^
        | (3)    | (4) payment proof
        v        |
  +------------------------------+   sign   +---------------------------+
  | AgentCore Payments           |--------->| Privy                     |
  |   ProcessPayment             |<---------|   embedded wallet         |
  |   session budget check       |          |   (authorization key)     |
  +------------------------------+          +---------------------------+
```

### Setup

1. **Privy app** ([dashboard.privy.io](https://dashboard.privy.io), free developer plan): create a dedicated app; note *App ID* and *App Secret*. Under *Wallet infrastructure → Authorization* create a key; note *Key ID* and *private key*.
2. **IAM service role** trusted by `bedrock-agentcore.amazonaws.com` (condition `aws:SourceAccount`). `CreatePaymentManager` validates up front that the role already allows `CreateWorkloadIdentity`, `GetWorkloadAccessToken` and `GetResourcePaymentToken` on `workload-identity-directory/default/workload-identity/*` and the token vault. See [IAM roles for AgentCore payments](https://docs.aws.amazon.com/bedrock-agentcore/latest/devguide/payments-iam-roles.html).
3. **Credential provider** (stored in AgentCore Identity / Secrets Manager):
   ```bash
   aws bedrock-agentcore-control create-payment-credential-provider --name <name> \
     --credential-provider-vendor StripePrivy \
     --provider-configuration-input '{"stripePrivyConfiguration":{"appId":"…","appSecret":"…","authorizationId":"<key id>","authorizationPrivateKey":"wallet-auth:…"}}'
   ```
   Keep the `wallet-auth:` prefix of the private key as shown by Privy.
4. **Payment manager** (`--authorizer-type AWS_IAM --role-arn <role>`) and **connector** (`--type StripePrivy` referencing the credential provider). Grant the role `GetResourcePaymentToken` on the credential provider and `secretsmanager:GetSecretValue` on its secrets.
5. **Payment instrument** (embedded Ethereum wallet linked to the end user's email):
   ```bash
   aws bedrock-agentcore create-payment-instrument --payment-manager-arn <arn> --payment-connector-id <id> \
     --user-id <user> --payment-instrument-type EMBEDDED_CRYPTO_WALLET \
     --payment-instrument-details '{"embeddedCryptoWallet":{"network":"ETHEREUM","linkedAccounts":[{"email":{"emailAddress":"<email>"}}]}}' \
     --client-token "$(uuidgen)"
   ```
6. **Fund** the returned wallet address with USDC on *Base Sepolia* from [faucet.circle.com](https://faucet.circle.com). No ETH is needed: x402 `exact` payments are signed authorizations whose transfer the merchant's facilitator submits.
7. **Delegate** signing: run the reference frontend [privy-io/aws-agentcore-sdk](https://github.com/privy-io/aws-agentcore-sdk) with `NEXT_PUBLIC_PRIVY_APP_ID`, `PRIVY_APP_SECRET`, `NEXT_PUBLIC_PRIVY_SIGNER_ID=<key id>` and `NEXT_PUBLIC_NETWORK_MODE=testnet` (`pnpm install && pnpm dev`), log in with the instrument's email and click **Connect agent**. Without this step `ProcessPayment` fails with `403 Privy credentials are invalid`.

### Run

The test creates a payment session (1.00 USD, 15 minutes), calls the merchant with a `RestClient` carrying the payments interceptor (way 1), checks that the session budget decreased and deletes the session.

```bash
AGENTCORE_PAYMENTS_IT=true \
AGENTCORE_PAYMENTS_REGION=us-east-1 \
AGENTCORE_PAYMENTS_MANAGER_ARN=arn:aws:bedrock-agentcore:us-east-1:<account>:payment-manager/<id> \
AGENTCORE_PAYMENTS_USER_ID=<user> \
AGENTCORE_PAYMENTS_INSTRUMENT_ID=<instrument id> \
AGENTCORE_PAYMENTS_URL=https://sandbox.node4all.com/v1/x402-test \
mvn verify -Pintegration -pl spring-ai-agentcore-payments
```

Test merchants on Base Sepolia: `https://sandbox.node4all.com/v1/x402-test` (AWS quick start sandbox, 0.002 USDC) and `https://drvd12nxpcyd5.cloudfront.net/market-recap` (0.001 USDC). Each payment appears on chain as a USDC transfer from the wallet to the merchant, submitted by the merchant's facilitator (EIP-3009 `transferWithAuthorization`). It is listed under *Token transfers* of the wallet address in the [Base Sepolia explorer](https://sepolia.basescan.org), not as a transaction sent by the wallet. The Privy dashboard shows mainnet balances only; query testnet balance and transfers with the Privy API:

```bash
curl -u "$PRIVY_APP_ID:$PRIVY_APP_SECRET" -H "privy-app-id: $PRIVY_APP_ID" \
  "https://api.privy.io/v1/wallets/<privy wallet id>/transactions?chain=base_sepolia&asset=usdc"
```

## Changes

Changes against earlier snapshots of this preview module:

- `paymentsToolCallbackProvider` (a `ToolCallbackProvider` bean) is replaced by the `AgentCorePaymentsTools` bean: use `chatClient.prompt().toolCallbacks(paymentsTools.toolCallbacks())`. It is not a `ToolCallbackProvider` bean, so the MCP server starter does not publish the paying tools.
- `paidHttpRequest` is off by default; enable it with `agentcore.payments.paid-http-tool.enabled=true` and `agentcore.payments.paid-http-tool.allowed-hosts`.
- `getPaymentInstrument`, `getPaymentInstrumentBalance` and `getPaymentSession` no longer accept instrument or session ids; they use the current `PaymentContext`.
- `agentcore.payments.user-id` and `payment-session-id` are fallbacks for local runs and tests; a WARN is logged when they are set.

## Next steps

- **MPP** (Machine Payments Protocol, `WWW-Authenticate: Payment` / `Authorization: Payment`): challenge parsing and selection, `buyerPaysGasFees`, fallback to x402 when no MPP challenge is payable.
- **MCP Gateway tools**: paid tools behind AgentCore Gateway report x402 requirements in `structuredContent`, which Spring AI's MCP `ToolCallback` drops; needs a callback over `McpSyncClient`.
- **Runtime session from AgentCore Runtime**: once the runtime starter offers the current runtime session and user, `PaymentSessionRegistry` can take them automatically.
- **Onboarding helper** (opt-in): when the user has no payment instrument, find or create one and return an onboarding link (Coinbase `redirectUrl` or a configured Privy frontend URL) so the user can fund the wallet and connect the agent; map Privy's "credentials are invalid" (missing delegation) to the same message.
- **CUSTOM_JWT** authorizer (bearer token instead of SigV4).
- **Control plane**: payment managers, connectors and credential providers.
- **Enablement flag**: decide whether `agentcore.payments.enabled` should replace enabling by `payment-manager-arn`.
