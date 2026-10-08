# AgentCore Payments test setup (Terraform)

Creates the AgentCore Payments resources the example needs, with Stripe Privy as wallet provider and the Base Sepolia testnet (no real money). Steps marked **manual** cannot be automated: they create the Privy app or are consent steps of the wallet owner.

| Step | | What |
|---|---|---|
| 1 | manual | Privy app and authorization key → environment variables |
| 2 | `terraform apply` | IAM service role, credential provider, payment manager, connector |
| 3 | `./create-instrument.sh <email>` | Wallet (payment instrument) of a user |
| 4 | manual | Fund the wallet and connect the agent |
| 5 | `./check-wallet.sh <instrument-id>` | Check status and balance |
| 6 | `mvn spring-boot:run -Dspring-boot.run.profiles=local` | Run the example |
| 7 | `./cleanup.sh <instrument-id>` | Delete the wallet and all Terraform resources |

Requirements: Terraform ≥ 1.5, AWS CLI v2 with credentials for the target account, Node.js 20+ and `pnpm` for step 4.

## 1. Privy app (manual)

1. Sign up at [dashboard.privy.io](https://dashboard.privy.io) (free developer plan) and create a **dedicated** app.
2. *Settings → API keys*: copy **App ID** and **App Secret**.
3. *Wallet infrastructure → Authorization → New key*: copy the **Key ID** and the **private key** (keep the `wallet-auth:` prefix).
4. Export them as environment variables in the shell you run Terraform from. Terraform reads `TF_VAR_<name>` automatically; `read -s` keeps the secrets out of the screen and the shell history:
   ```bash
   # works in bash and zsh
   printf 'Privy App ID: ';                    read -r  TF_VAR_privy_app_id
   printf 'Privy App Secret: ';                read -rs TF_VAR_privy_app_secret; echo
   printf 'Privy authorization key ID: ';      read -r  TF_VAR_privy_authorization_id
   printf 'Privy authorization private key: '; read -rs TF_VAR_privy_authorization_private_key; echo
   export TF_VAR_privy_app_id TF_VAR_privy_app_secret TF_VAR_privy_authorization_id TF_VAR_privy_authorization_private_key
   ```
   Region and name prefix default to `us-east-1` and `SpringAiPaymentsExample`; to change them, copy `terraform.tfvars.example` to `terraform.tfvars`.

## 2. AgentCore Payments resources

```bash
terraform init
terraform apply
```

Creates, in the configured region:

- IAM role `<prefix>Role` trusted by `bedrock-agentcore.amazonaws.com`, with the permissions AgentCore validates when the payment manager is created and access to the Privy secrets.
- Payment credential provider `<prefix>Privy` (secrets stored by AgentCore Identity in Secrets Manager).
- Payment manager `<prefix>` (`AWS_IAM` authorizer) and connector `<prefix>Privy`.

> The Terraform state contains the Privy secrets in plain text (the `awscc` provider does not mark them sensitive). Keep the state local or in an encrypted backend.

## 3. Wallet

```bash
./create-instrument.sh you@example.com            # optional second argument: user id, default example-user
```

Prints the wallet address, the instrument id and the environment variables for step 6. Every run creates a new wallet, also for an email that already has one; each wallet needs its own funding and "Connect agent". An existing wallet cannot be attached to a new payment manager: AgentCore Payments always provisions a new wallet per instrument.

## 4. Fund the wallet and connect the agent (manual)

1. **Fund**: [faucet.circle.com](https://faucet.circle.com) → *USDC*, *Base Sepolia*, paste the wallet address of step 3. No ETH is needed. The Privy dashboard lists testnet wallets with a balance of $0.00; `./check-wallet.sh` (step 5) shows the real balance.
2. **Connect the agent** (grants signing permission to the authorization key) with the reference frontend of AWS and Privy:
   ```bash
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

## 5. Check

```bash
./check-wallet.sh <instrument-id>
```

Shows status and balance. Missing signing permission only shows when paying: `403 Privy credentials are invalid` means step 4.2 is missing.

## 6. Run the example

Export the variables printed in step 3 and follow [../README.md](../README.md#running).

## 7. Clean up

```bash
./cleanup.sh <instrument-id>
```

Deletes the wallet in AgentCore Payments (the Privy wallet and its test USDC remain) and runs `terraform destroy`. Rotate or delete the Privy keys afterwards if they are no longer needed.
