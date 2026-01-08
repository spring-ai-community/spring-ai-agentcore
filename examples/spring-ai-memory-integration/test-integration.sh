#!/bin/bash

set -e

BASE_URL="http://localhost:8080"

echo "🧪 Running Integration Tests for Spring AI AgentCore Memory Example"

# Test 1: Send first message
echo "📤 Test 1: Sending first message..."
RESPONSE1=$(curl -s -X POST "$BASE_URL/api/chat" \
  -H "Content-Type: application/json" \
  -d '{"message": "Hello, my name is Alice and I love hiking"}')

echo "Response: $RESPONSE1"
if [[ -n "$RESPONSE1" ]]; then
  echo "✅ Test 1 passed: Got response from AI"
else
  echo "❌ Test 1 failed: No response from AI"
  exit 1
fi

# Test 2: Test memory recall
echo "📤 Test 2: Testing memory recall..."
RESPONSE2=$(curl -s -X POST "$BASE_URL/api/chat" \
  -H "Content-Type: application/json" \
  -d '{"message": "What do you remember about me?"}')

echo "Response: $RESPONSE2"
if [[ $RESPONSE2 == *"Alice"* ]] && [[ $RESPONSE2 == *"hiking"* ]]; then
  echo "✅ Test 2 passed: AI remembered previous context"
else
  echo "❌ Test 2 failed: AI did not remember context"
  exit 1
fi

# Test 3: Get conversation history
echo "📤 Test 3: Getting conversation history..."
HISTORY=$(curl -s "$BASE_URL/api/chat/history")

echo "History: $HISTORY"
if [[ $HISTORY == *"Alice"* ]] && [[ $HISTORY == *"hiking"* ]]; then
  echo "✅ Test 3 passed: History contains expected content"
else
  echo "❌ Test 3 failed: History missing expected content"
  exit 1
fi

# Test 5: Clear conversation
echo "📤 Test 4: Clearing conversation..."
curl -s -X DELETE "$BASE_URL/api/chat/history"

# Verify conversation is cleared
CLEARED_HISTORY=$(curl -s "$BASE_URL/api/chat/history")
if [[ $CLEARED_HISTORY == "[]" ]]; then
  echo "✅ Test 4 passed: Conversation cleared successfully"
else
  echo "❌ Test 4 failed: Conversation not cleared"
  exit 1
fi

echo "🎉 All integration tests passed!"
echo "✅ Memory persistence working"
echo "✅ Context awareness working"
echo "✅ History management working"
