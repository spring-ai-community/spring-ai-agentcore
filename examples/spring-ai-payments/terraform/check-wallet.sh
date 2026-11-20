#!/usr/bin/env bash
# Shows status, Base Sepolia USDC balance (x402) and Tempo Moderato pathUSD balance (MPP) of a payment
# instrument (wallet).
set -euo pipefail

INSTRUMENT_ID=${1:?usage: ./check-wallet.sh <payment-instrument-id> [user-id]}
USER_ID=${2:-example-user}

cd "$(dirname "$0")"
REGION=$(terraform output -raw region)
MANAGER_ARN=$(terraform output -raw payment_manager_arn)
CONNECTOR_ID=$(terraform output -raw payment_connector_id)

read -r STATUS WALLET < <(aws bedrock-agentcore get-payment-instrument --region "$REGION" \
  --payment-manager-arn "$MANAGER_ARN" --payment-instrument-id "$INSTRUMENT_ID" --user-id "$USER_ID" \
  --query '[paymentInstrument.status, paymentInstrument.paymentInstrumentDetails.embeddedCryptoWallet.walletAddress]' \
  --output text)
AMOUNT=$(aws bedrock-agentcore get-payment-instrument-balance --region "$REGION" \
  --payment-manager-arn "$MANAGER_ARN" --payment-connector-id "$CONNECTOR_ID" \
  --payment-instrument-id "$INSTRUMENT_ID" --user-id "$USER_ID" --chain BASE_SEPOLIA --token USDC \
  --query 'tokenBalance.amount' --output text)

echo "Instrument $INSTRUMENT_ID: status $STATUS, balance $(awk "BEGIN { printf \"%.6f\", $AMOUNT / 1000000 }") USDC (Base Sepolia)"
if [ "$AMOUNT" = "0" ]; then
  echo "Fund the wallet with USDC on Base Sepolia: https://faucet.circle.com"
fi

# pathUSD (0x20c0...0000, 6 decimals) balanceOf(wallet) on Tempo Moderato, used by the MPP test merchant
PATH_USD_HEX=$(curl -s https://rpc.moderato.tempo.xyz -H 'Content-Type: application/json' -d "{\"jsonrpc\":\"2.0\",\"id\":1,
  \"method\":\"eth_call\",\"params\":[{\"to\":\"0x20c0000000000000000000000000000000000000\",
  \"data\":\"0x70a08231000000000000000000000000${WALLET#0x}\"},\"latest\"]}" | sed -E 's/.*"result":"0x0*([0-9a-fA-F]*)".*/\1/')
PATH_USD=$(printf '%d' "0x${PATH_USD_HEX:-0}")
echo "Wallet $WALLET: balance $(awk "BEGIN { printf \"%.6f\", $PATH_USD / 1000000 }") pathUSD (Tempo Moderato)"
if [ "$PATH_USD" = "0" ]; then
  echo "Fund the wallet with pathUSD on Tempo Moderato (for MPP): README.md, step 4"
fi
echo "Signing permission cannot be checked here: if payments fail with 'Privy credentials are invalid',"
echo "click \"Connect agent\" in the Privy frontend (README.md, step 4)."
