# Spring AI AgentCore Payments Example

An AgentCore Runtime agent that pays for x402-protected APIs through [AgentCore Payments](../../spring-ai-agentcore-payments/), on the Base Sepolia testnet (no real money). One agent shows the three integration approaches side by side; the request field `approach` selects one.

| `approach` | Way | Code | Paid endpoint |
|---|---|---|---|
| `interceptor` (default) | 1. HTTP client interceptor: domain tool on a `RestClient` with `AgentCorePaymentsClientHttpRequestInterceptor` | [`MarketRecapTools`](src/main/java/com/unicorn/payments/MarketRecapTools.java), [`PaymentsConfiguration`](src/main/java/com/unicorn/payments/PaymentsConfiguration.java) | market recap |
| `tool` | 2. `paidHttpRequest` tool plus payment query tools; the prompt names the URL | auto-configured `AgentCorePaymentsTools`, enabled for the two test merchants in `application.properties` | URL from the prompt, allowed hosts only |
| `custom` | 3. Own integration: domain tool on the JDK `HttpClient` calling `AgentCorePaymentsTemplate.generatePaymentHeader(...)` | [`FortuneTools`](src/main/java/com/unicorn/payments/FortuneTools.java) | fortune reading |

All three pay from the budget of the conversation: [`PaymentsAgent`](src/main/java/com/unicorn/payments/PaymentsAgent.java) calls `PaymentSessionRegistry.getOrCreate(userId, runtimeSessionId)` once per invocation (0.10 USD for 30 minutes, see `application.properties`) and passes user and payment session to the tools through the tool context.

```
POST /invocations ──► PaymentsAgent ── getOrCreate(user, runtime session) ──► payment session (budget)
                          │ toolContext(user, payment session)
                          ▼
                   ChatClient + tools ──► paid API answers 402 ──► AgentCore Payments signs ──► retry ──► 200
```

## Prerequisites

- Java 17+, Maven, AWS credentials with access to Amazon Bedrock and AgentCore Payments.
- A payment manager, connector and a **funded** payment instrument (wallet) with signing delegated to the agent. [`terraform/`](terraform/README.md) sets them up in seven steps (Stripe Privy, test USDC from the Circle faucet).

## Running

```bash
# from the project root
mvn clean install -DskipTests

cd examples/spring-ai-payments
export PAYMENT_MANAGER_ARN=arn:aws:bedrock-agentcore:us-east-1:<account>:payment-manager/<id>
export PAYMENT_INSTRUMENT_ID=<payment instrument id>
export PAYMENT_USER_ID=<user id of the instrument>      # local profile: used when no Runtime user header is sent
export PAYMENT_REGION=us-east-1                          # region of the payment manager
mvn spring-boot:run -Dspring-boot.run.profiles=local
```

The `local` profile lets the agent pay as `PAYMENT_USER_ID`, in one local conversation, when the AgentCore Runtime user and session headers are absent. Without it, such requests fail, so that a deployed agent never falls back to a shared wallet or budget.

Send the requests in [`test_request.http`](test_request.http), or:

```bash
curl -X POST http://localhost:8080/invocations \
  -H 'Content-Type: application/json' \
  -H 'X-Amzn-Bedrock-AgentCore-Runtime-Session-Id: example-session-0000000000000001' \
  -d '{"prompt":"Give me a short recap of recent prediction-market activity.","approach":"interceptor"}'
```

The log shows each payment, for example `GET https://drvd12nxpcyd5.cloudfront.net/market-recap requires payment, paying through AgentCore Payments`. Requests with the same runtime session id share one budget; the `tool` approach can report the remaining budget through the `getPaymentSession` tool.

## Configuration

```properties
agentcore.payments.payment-manager-arn=${PAYMENT_MANAGER_ARN}
agentcore.payments.payment-instrument-id=${PAYMENT_INSTRUMENT_ID}
agentcore.payments.paid-http-tool.enabled=true
agentcore.payments.paid-http-tool.allowed-hosts=drvd12nxpcyd5.cloudfront.net,sandbox.node4all.com
agentcore.payments.session.max-spend=0.10     # budget per conversation (USD)
agentcore.payments.session.expiry=30m
app.payments.region=${PAYMENT_REGION:us-east-1}
spring.ai.bedrock.converse.chat.options.model=global.anthropic.claude-sonnet-4-5-20250929-v1:0
```

The example defines its own `BedrockAgentCoreClient` bean for the payment manager's region; the auto-configured client uses the default AWS region.

## Notes

- The test merchants occasionally fail on their side (for example HTTP 500 after a valid payment). Such payments are not settled and cost nothing; the agent reports the error.
- `paidHttpRequest` lets the model choose URLs. Prefer domain tools (ways 1 and 3) for known APIs; see the module README for the security notes.
