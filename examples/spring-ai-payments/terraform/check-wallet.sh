#!/usr/bin/env bash
# Shows status and Base Sepolia USDC balance of a payment instrument (wallet).
set -euo pipefail

INSTRUMENT_ID=${1:?usage: ./check-wallet.sh <payment-instrument-id> [user-id]}
USER_ID=${2:-example-user}

cd "$(dirname "$0")"
REGION=$(terraform output -raw region)
MANAGER_ARN=$(terraform output -raw payment_manager_arn)
CONNECTOR_ID=$(terraform output -raw payment_connector_id)

STATUS=$(aws bedrock-agentcore get-payment-instrument --region "$REGION" \
  --payment-manager-arn "$MANAGER_ARN" --payment-instrument-id "$INSTRUMENT_ID" --user-id "$USER_ID" \
  --query 'paymentInstrument.status' --output text)
AMOUNT=$(aws bedrock-agentcore get-payment-instrument-balance --region "$REGION" \
  --payment-manager-arn "$MANAGER_ARN" --payment-connector-id "$CONNECTOR_ID" \
  --payment-instrument-id "$INSTRUMENT_ID" --user-id "$USER_ID" --chain BASE_SEPOLIA --token USDC \
  --query 'tokenBalance.amount' --output text)

echo "Instrument $INSTRUMENT_ID: status $STATUS, balance $(awk "BEGIN { printf \"%.6f\", $AMOUNT / 1000000 }") USDC (Base Sepolia)"
if [ "$AMOUNT" = "0" ]; then
  echo "Fund the wallet with USDC on Base Sepolia: https://faucet.circle.com"
fi
echo "Signing permission cannot be checked here: if payments fail with 'Privy credentials are invalid',"
echo "click \"Connect agent\" in the Privy frontend (README.md, step 4)."
