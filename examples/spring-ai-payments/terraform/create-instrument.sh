#!/usr/bin/env bash
# Creates an embedded Ethereum wallet (payment instrument) for a user of the payment manager
# created by this Terraform configuration. Wallets are data-plane objects, not Terraform resources.
set -euo pipefail

EMAIL=${1:?usage: ./create-instrument.sh <email> [user-id]}
USER_ID=${2:-example-user}

cd "$(dirname "$0")"
REGION=$(terraform output -raw region)
MANAGER_ARN=$(terraform output -raw payment_manager_arn)
CONNECTOR_ID=$(terraform output -raw payment_connector_id)

read -r INSTRUMENT_ID WALLET <<<"$(aws bedrock-agentcore create-payment-instrument \
  --region "$REGION" \
  --payment-manager-arn "$MANAGER_ARN" \
  --payment-connector-id "$CONNECTOR_ID" \
  --user-id "$USER_ID" \
  --payment-instrument-type EMBEDDED_CRYPTO_WALLET \
  --payment-instrument-details "{\"embeddedCryptoWallet\":{\"network\":\"ETHEREUM\",\"linkedAccounts\":[{\"email\":{\"emailAddress\":\"$EMAIL\"}}]}}" \
  --client-token "$(uuidgen)" \
  --query 'paymentInstrument.[paymentInstrumentId,paymentInstrumentDetails.embeddedCryptoWallet.walletAddress]' \
  --output text)"

cat <<EOT
Wallet created: $WALLET (instrument $INSTRUMENT_ID, user $USER_ID)

Next steps:
  1. Fund it with USDC on Base Sepolia: https://faucet.circle.com
  2. Log in with $EMAIL in the Privy frontend and click "Connect agent" (see README.md)
  3. Run the example with:
     export PAYMENT_REGION=$REGION
     export PAYMENT_MANAGER_ARN=$MANAGER_ARN
     export PAYMENT_INSTRUMENT_ID=$INSTRUMENT_ID
     export PAYMENT_USER_ID=$USER_ID
EOT
