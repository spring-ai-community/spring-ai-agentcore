# Spring AI AgentCore Payments Example

An AgentCore Runtime agent that pays for paid APIs through [AgentCore Payments](../../spring-ai-agentcore-payments/), on testnets (no real money): x402 merchants on Base Sepolia and an MPP merchant on Tempo Moderato. The merchant's `402` decides the protocol; the agent code is the same for both. One agent shows the three integration approaches side by side; the request field `approach` selects one.

| `approach` | Way | Code | Paid endpoint |
|---|---|---|---|
| `interceptor` (default) | 1. HTTP client interceptor: domain tool on a `RestClient` with `AgentCorePaymentsClientHttpRequestInterceptor` | [`MarketRecapTools`](src/main/java/com/unicorn/payments/MarketRecapTools.java), [`PaymentsConfiguration`](src/main/java/com/unicorn/payments/PaymentsConfiguration.java) | market recap |
| `tool` | 2. `paidHttpRequest` tool plus payment query tools; the prompt names the URL | auto-configured `AgentCorePaymentsTools`, enabled for the three test merchants in `application.properties` | URL from the prompt, allowed hosts only: the x402 merchants or the MPP merchant `mpp.dev` |
| `custom` | 3. Own integration: domain tool on the JDK `HttpClient` calling `AgentCorePaymentsClient.generatePaymentHeader(...)` | [`FortuneTools`](src/main/java/com/unicorn/payments/FortuneTools.java) | fortune reading |

All three pay from the budget of the conversation: [`PaymentsAgent`](src/main/java/com/unicorn/payments/PaymentsAgent.java) calls `PaymentSessionRegistry.getOrCreate(userId, runtimeSessionId)` once per invocation (0.50 USD for 30 minutes, see `application.properties`) and passes user and payment session to the tools through the tool context.

```
POST /invocations ──► PaymentsAgent ── getOrCreate(user, runtime session) ──► payment session (budget)
                          │ toolContext(user, payment session)
                          ▼
                   ChatClient + tools ──► paid API answers 402 ──► AgentCore Payments signs ──► retry ──► 200
```

## Prerequisites

- Java 17+, Maven, AWS credentials with access to Amazon Bedrock and AgentCore Payments.
- A payment manager, connector and a **funded** payment instrument (wallet) with signing delegated to the agent: see [Setup](#setup-stripe-privy-testnets).

## Setup (Stripe Privy, testnets)

The example pays from a Stripe Privy wallet on testnets (no real money): USDC on Base Sepolia for the x402 merchants, pathUSD on Tempo Moderato for the MPP merchant. Steps marked **manual** cannot be automated: they create the Privy app or are consent steps of the wallet owner. The scripts and the Terraform configuration are in [`terraform/`](terraform/README.md).

| Step | | What |
|---|---|---|
| 1 | manual | Privy app and authorization key → environment variables |
| 2 | `terraform apply` | IAM service role, credential provider, payment manager, connector |
| 3 | `./create-instrument.sh <email>` | Wallet (payment instrument) of a user |
| 4 | manual | Fund the wallet and connect the agent |
| 5 | `./check-wallet.sh <instrument-id>` | Check status and balances |

Requirements: Terraform ≥ 1.5, AWS CLI v2 with credentials for the target account, Node.js 20+ and `pnpm` for step 4.

### 1. Privy app (manual)

1. Sign up at [dashboard.privy.io](https://dashboard.privy.io) (free developer plan) and create a **dedicated** app.
2. *Settings → API keys*: copy **App ID** and **App Secret**.
3. *Wallet infrastructure → Authorization → New key*: copy the **Key ID** and the **private key** (keep the `wallet-auth:` prefix).
4. Export them as environment variables in the shell you run the setup from. Terraform reads `TF_VAR_<name>` automatically; `read -s` keeps the secrets out of the screen and the shell history:
   ```bash
   # works in bash and zsh
   printf 'Privy App ID: ';                    read -r  TF_VAR_privy_app_id
   printf 'Privy App Secret: ';                read -rs TF_VAR_privy_app_secret; echo
   printf 'Privy authorization key ID: ';      read -r  TF_VAR_privy_authorization_id
   printf 'Privy authorization private key: '; read -rs TF_VAR_privy_authorization_private_key; echo
   export TF_VAR_privy_app_id TF_VAR_privy_app_secret TF_VAR_privy_authorization_id TF_VAR_privy_authorization_private_key
   ```
   Region and name prefix default to `us-east-1` and `SpringAiPaymentsExample`; to change them, copy `terraform/terraform.tfvars.example` to `terraform/terraform.tfvars`.

### 2. AgentCore Payments resources

```bash
cd terraform
terraform init
terraform apply
```

Creates, in the configured region:

- IAM role `<prefix>Role` trusted by `bedrock-agentcore.amazonaws.com`, with the permissions AgentCore validates when the payment manager is created and access to the Privy secrets.
- Payment credential provider `<prefix>Privy` (secrets stored by AgentCore Identity in Secrets Manager).
- Payment manager `<prefix>` (`AWS_IAM` authorizer) and connector `<prefix>Privy`.

> The Terraform state contains the Privy secrets in plain text (the `awscc` provider does not mark them sensitive). Keep the state local or in an encrypted backend.

### 3. Wallet

```bash
./create-instrument.sh you@example.com   # in terraform/; optional second argument: user id, default example-user
```

Prints the wallet address, the instrument id and the environment variables for [Running](#running). Every run creates a new wallet, also for an email that already has one; each wallet needs its own funding and "Connect agent". An existing wallet cannot be attached to a new payment manager: AgentCore Payments always provisions a new wallet per instrument.

### 4. Fund the wallet and connect the agent (manual)

1. **Fund**:
   - x402 merchants: [faucet.circle.com](https://faucet.circle.com) → *USDC*, *Base Sepolia*, paste the wallet address of step 3. No ETH is needed.
   - MPP merchant (`mpp.dev`): pathUSD on the Tempo Moderato testnet, same wallet address. The merchant pays the network fee, so nothing else is needed:
     ```bash
     curl -X POST https://tempo.com/developers/api/faucet -H 'Content-Type: application/json' \
       -d '{"address": "<wallet address of step 3>"}'
     ```
   The Privy dashboard lists testnet wallets with a balance of $0.00; `terraform/check-wallet.sh` (step 5) shows the real balances. Only Stripe Privy wallets can sign Tempo payments; Coinbase wallets cannot.
2. **Connect the agent** (grants signing permission to the authorization key) with the reference frontend of AWS and Privy:
   ```bash
   cd ~   # any directory outside this repository
   git clone https://github.com/privy-io/aws-agentcore-sdk   # once
   cd aws-agentcore-sdk
   # .env.local from the variables of step 1; readable only by you, git-ignored by the frontend.
   # ">|" overwrites an existing file also when the shell has noclobber set.
   (umask 077; cat >| .env.local <<EOT
   NEXT_PUBLIC_PRIVY_APP_ID=$TF_VAR_privy_app_id
   PRIVY_APP_SECRET=$TF_VAR_privy_app_secret
   NEXT_PUBLIC_PRIVY_SIGNER_ID=$TF_VAR_privy_authorization_id
   NEXT_PUBLIC_NETWORK_MODE=testnet
   EOT
   )
   pnpm install && pnpm dev    # restart pnpm dev after changing .env.local; it is read only at startup
   ```
   Then:
   1. Open http://localhost:3000 and log in with the email of step 3 (Privy sends a login code).
   2. Find the wallet with the address of step 3. Each run of `create-instrument.sh` creates a new wallet, also for the same email, so several may be listed.
   3. Click **Connect agent** and confirm; the page shows "Agent connected successfully".

### 5. Check

```bash
./check-wallet.sh <instrument-id>   # in terraform/
```

Shows status and both balances (USDC on Base Sepolia, pathUSD on Tempo Moderato). Missing signing permission only shows when paying: `403 Privy credentials are invalid` means step 4.2 is missing.

## Running

```bash
# from the project root, with the variables printed in setup step 3
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

To pay the MPP merchant, ask the `tool` approach for its URL:

```bash
curl -X POST http://localhost:8080/invocations \
  -H 'Content-Type: application/json' \
  -H 'X-Amzn-Bedrock-AgentCore-Runtime-Session-Id: example-session-0000000000000001' \
  -d '{"prompt":"Fetch https://mpp.dev/api/ping/paid and tell me what it returned.","approach":"tool"}'
```

The log shows each payment, for example `GET https://drvd12nxpcyd5.cloudfront.net/market-recap requires payment, paying through AgentCore Payments`. Requests with the same runtime session id share one budget; the `tool` approach can report the remaining budget through the `getPaymentSession` tool.

## Configuration

```properties
agentcore.payments.payment-manager-arn=${PAYMENT_MANAGER_ARN}
agentcore.payments.payment-instrument-id=${PAYMENT_INSTRUMENT_ID}
agentcore.payments.paid-http-tool.enabled=true
agentcore.payments.paid-http-tool.allowed-hosts=drvd12nxpcyd5.cloudfront.net,sandbox.node4all.com,mpp.dev
agentcore.payments.session.max-spend=0.50     # budget per conversation (USD); the MPP merchant costs 0.10
agentcore.payments.session.expiry=30m
app.payments.region=${PAYMENT_REGION:us-east-1}
spring.ai.bedrock.converse.chat.options.model=global.anthropic.claude-sonnet-4-5-20250929-v1:0
```

The example defines its own `BedrockAgentCoreClient` bean for the payment manager's region; the auto-configured client uses the default AWS region.

## Notes

- The test merchants occasionally fail on their side (for example HTTP 500 after a valid payment). Such payments are not settled and cost nothing; the agent reports the error.
- `paidHttpRequest` lets the model choose URLs. Prefer domain tools (ways 1 and 3) for known APIs; see the module README for the security notes.

## Clean up

```bash
cd terraform
./cleanup.sh <instrument-id>
```

Deletes the wallet in AgentCore Payments (the Privy wallet and its test USDC and pathUSD remain) and runs `terraform destroy`. Rotate or delete the Privy keys afterwards if they are no longer needed.
