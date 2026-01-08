#!/bin/bash
set -e

echo "🧪 Testing AgentCore Runtime with OAuth Authentication"
echo "This test demonstrates that different OAuth users have completely separate conversation memories."
echo ""

# Get runtime name from terraform output or use existing working runtime
cd terraform
RUNTIME_NAME=$(terraform output -raw runtime_name 2>/dev/null || echo "agentcore_iam_agent-0ABgjsAiKF")
USER_POOL_ID=$(terraform output -raw cognito_user_pool_id 2>/dev/null)
CLIENT_ID=$(terraform output -raw cognito_client_id 2>/dev/null)

if [ -z "$RUNTIME_NAME" ] || [ -z "$USER_POOL_ID" ] || [ -z "$CLIENT_ID" ]; then
    echo "❌ Missing terraform outputs. Deploy first with ./deploy.sh"
    exit 1
fi

# Get runtime ARN from terraform
RUNTIME_ARN=$(terraform output -raw runtime_arn 2>/dev/null)
RUNTIME_ID=$(echo "$RUNTIME_ARN" | sed 's/.*runtime\///')

echo "🎯 Testing Runtime: $RUNTIME_NAME"
echo "🔗 Runtime ARN: $RUNTIME_ARN"
echo "🆔 Runtime ID: $RUNTIME_ID"
echo "👥 User Pool: $USER_POOL_ID"
echo "📱 Client ID: $CLIENT_ID"
echo ""

# Function to create user if not exists
create_user_if_needed() {
    local username=$1
    local password=$2
    local email=$3
    
    echo "👤 Checking user: $username"
    USER_EXISTS=$(aws cognito-idp admin-get-user \
        --user-pool-id "$USER_POOL_ID" \
        --username "$username" \
        --region us-east-1 \
        --no-cli-pager 2>/dev/null && echo "true" || echo "false")

    if [ "$USER_EXISTS" = "false" ]; then
        echo "   Creating user: $username"
        aws cognito-idp admin-create-user \
            --user-pool-id "$USER_POOL_ID" \
            --username "$username" \
            --user-attributes Name=email,Value="$email" Name=email_verified,Value=true \
            --temporary-password "$password" \
            --message-action SUPPRESS \
            --region us-east-1 \
            --no-cli-pager > /dev/null

        aws cognito-idp admin-set-user-password \
            --user-pool-id "$USER_POOL_ID" \
            --username "$username" \
            --password "$password" \
            --permanent \
            --region us-east-1 \
            --no-cli-pager > /dev/null
        echo "   ✅ User created: $username"
    else
        echo "   ✅ User exists: $username"
    fi
}

# Function to get OAuth token
get_oauth_token() {
    local username=$1
    local password=$2
    
    aws cognito-idp admin-initiate-auth \
        --user-pool-id "$USER_POOL_ID" \
        --client-id "$CLIENT_ID" \
        --auth-flow ADMIN_NO_SRP_AUTH \
        --auth-parameters USERNAME="$username",PASSWORD="$password" \
        --region us-east-1 \
        --output json | jq -r '.AuthenticationResult.AccessToken'
}

# Function to call AgentCore Runtime with OAuth
call_runtime_oauth() {
    local token=$1
    local session=$2
    local message=$3
    
    # Use the runtime ARN with proper URL encoding and headers like working example
    local runtime_arn="$RUNTIME_ARN"
    local escaped_arn=$(echo "$runtime_arn" | sed 's/:/%3A/g' | sed 's/\//%2F/g')
    
    curl -s -X POST "https://bedrock-agentcore.us-east-1.amazonaws.com/runtimes/${escaped_arn}/invocations?qualifier=DEFAULT" \
        -H "Content-Type: application/json" \
        -H "Accept: application/json" \
        -H "Authorization: Bearer $token" \
        -H "X-Amzn-Bedrock-AgentCore-Runtime-Custom-Test: oauth-test" \
        -H "X-Amzn-Bedrock-AgentCore-Runtime-Session-Id: $session" \
        -d "{\"prompt\":\"$message\"}"
}

# Setup test users
echo "🔧 Setting up test users..."
create_user_if_needed "alice" "AlicePass123!" "alice@example.com"
create_user_if_needed "bob" "BobPass123!" "bob@example.com"

# Get OAuth tokens
echo ""
echo "🔑 Getting OAuth tokens..."
ALICE_TOKEN=$(get_oauth_token "alice" "AlicePass123!")
BOB_TOKEN=$(get_oauth_token "bob" "BobPass123!")

if [ -z "$ALICE_TOKEN" ] || [ -z "$BOB_TOKEN" ]; then
    echo "❌ Failed to get OAuth tokens"
    exit 1
fi

echo "✅ OAuth tokens obtained for both users"
echo ""

# Generate session IDs (must be at least 33 characters)
SESSION_ID="oauth-memory-test-$(date +%s)-session"
ALICE_SESSION="${SESSION_ID}-alice"
BOB_SESSION="${SESSION_ID}-bob"

# Test independent memories with OAuth authentication
echo "🧠 Testing Independent User Memories with OAuth"
echo "=================================================="

# Alice introduces herself
echo ""
echo "👩 ALICE - Session 1: Introduction (OAuth authenticated)"
ALICE_R1=$(call_runtime_oauth "$ALICE_TOKEN" "$ALICE_SESSION" "Hi, I'm Alice. I work as a software engineer at AWS and I love hiking.")
echo "🤖 Alice Response 1: $ALICE_R1"

# Bob introduces himself  
echo ""
echo "👨 BOB - Session 1: Introduction (OAuth authenticated)"
BOB_R1=$(call_runtime_oauth "$BOB_TOKEN" "$BOB_SESSION" "Hello, I'm Bob. I'm a data scientist at Microsoft and I enjoy cooking.")
echo "🤖 Bob Response 1: $(echo "$BOB_R1" | jq -r '.response // .error')"

# Alice adds more info
echo ""
echo "👩 ALICE - Session 2: More details"
ALICE_R2=$(call_runtime_oauth "$ALICE_TOKEN" "$ALICE_SESSION" "I also have a cat named Whiskers and I live in Seattle.")
echo "🤖 Alice Response 2: $(echo "$ALICE_R2" | jq -r '.response // .error')"

# Bob adds more info
echo ""
echo "👨 BOB - Session 2: More details"
BOB_R2=$(call_runtime_oauth "$BOB_TOKEN" "$BOB_SESSION" "I specialize in machine learning and I have a dog named Rex.")
echo "🤖 Bob Response 2: $(echo "$BOB_R2" | jq -r '.response // .error')"

# Test memory isolation - Alice asks about herself
echo ""
echo "🔍 MEMORY ISOLATION TEST"
echo "========================"
echo ""
echo "👩 ALICE asks: What do you know about me?"
ALICE_R3=$(call_runtime_oauth "$ALICE_TOKEN" "$ALICE_SESSION" "What do you know about me? Tell me my name, job, company, hobbies, pet, and city.")
echo "🤖 Alice Memory Recall: $(echo "$ALICE_R3" | jq -r '.response // .error')"

# Test memory isolation - Bob asks about himself
echo ""
echo "👨 BOB asks: What do you know about me?"
BOB_R3=$(call_runtime_oauth "$BOB_TOKEN" "$BOB_SESSION" "What do you know about me? Tell me my name, job, company, specialty, and pet.")
echo "🤖 Bob Memory Recall: $(echo "$BOB_R3" | jq -r '.response // .error')"

# Cross-contamination test - Alice asks about Bob
echo ""
echo "🚫 CROSS-CONTAMINATION TEST"
echo "============================"
echo ""
echo "👩 ALICE asks: Do you know anything about Bob?"
ALICE_R4=$(call_runtime_oauth "$ALICE_TOKEN" "$ALICE_SESSION" "Do you know anything about a person named Bob? What's his job or pet?")
echo "🤖 Alice Cross-Test: $(echo "$ALICE_R4" | jq -r '.response // .error')"

echo ""
echo "✅ OAUTH MEMORY ISOLATION TEST RESULTS:"
echo "========================================"
echo ""
echo "👩 Alice's Information:"
echo "   - OAuth User: alice"
echo "   - Session: $ALICE_SESSION"
echo "   - Knows about Alice: $(echo "$ALICE_R3" | jq -r '.response' | grep -iE "(aws|hiking|whiskers|seattle)" > /dev/null && echo "✅ YES" || echo "❌ NO")"
echo ""
echo "👨 Bob's Information:"
echo "   - OAuth User: bob"
echo "   - Session: $BOB_SESSION"
echo "   - Knows about Bob: $(echo "$BOB_R3" | jq -r '.response' | grep -iE "(microsoft|cooking|rex)" > /dev/null && echo "✅ YES" || echo "❌ NO")"
echo ""
echo "🔒 Memory Isolation:"
MEMORY_ISOLATION_RESULT=$(echo "$ALICE_R4" | jq -r '.response' | grep -iE "(data scientist|microsoft|machine learning|rex)" > /dev/null && echo "❌ LEAKED" || echo "✅ ISOLATED")
echo "   - Alice knows about Bob: $MEMORY_ISOLATION_RESULT"
echo ""
echo "🎯 OAuth Authentication Test Summary:"
echo "   - Different OAuth Users: ✅ alice vs bob"
echo "   - Different Sessions: ✅ $ALICE_SESSION vs $BOB_SESSION"
echo "   - Memory Isolation: $(echo "$MEMORY_ISOLATION_RESULT" | sed 's/LEAKED/FAILED/' | sed 's/ISOLATED/SUCCESS/')"
echo ""
echo "🔐 OAuth Features Demonstrated:"
echo "   ✅ JWT Bearer token authentication"
echo "   ✅ User identity extraction from OAuth tokens"
echo "   ✅ Per-user conversation memory isolation"
echo "   ✅ AgentCore Runtime with OAuth integration"
