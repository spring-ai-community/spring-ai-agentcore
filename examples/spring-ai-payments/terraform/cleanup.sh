#!/usr/bin/env bash
# Deletes a payment instrument (wallet), then all resources created by Terraform.
set -euo pipefail

INSTRUMENT_ID=${1:?usage: ./cleanup.sh <payment-instrument-id> [user-id]}
USER_ID=${2:-example-user}

cd "$(dirname "$0")"
REGION=$(terraform output -raw region)
MANAGER_ARN=$(terraform output -raw payment_manager_arn)
CONNECTOR_ID=$(terraform output -raw payment_connector_id)

aws bedrock-agentcore delete-payment-instrument --region "$REGION" \
  --payment-manager-arn "$MANAGER_ARN" --payment-connector-id "$CONNECTOR_ID" \
  --payment-instrument-id "$INSTRUMENT_ID" --user-id "$USER_ID" >/dev/null
echo "Deleted payment instrument $INSTRUMENT_ID"

terraform destroy
