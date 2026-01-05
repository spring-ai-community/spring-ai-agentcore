package org.springaicommunity.example;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springaicommunity.agentcore.context.AgentCoreContext;
import org.springaicommunity.agentcore.context.AgentCoreHeaders;
import org.springaicommunity.agentcore.annotation.AgentCoreInvocation;
import org.springframework.stereotype.Service;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;

import java.util.List;
import java.util.Map;

@Service
public class ExtendedChatController {

	private final ChatClient chatClient;
	private final ChatMemory chatMemory;
	private final ObjectMapper objectMapper = new ObjectMapper();

	public ExtendedChatController(ChatClient.Builder chatClientBuilder, ChatMemoryRepository memoryRepository) {
		this.chatMemory = MessageWindowChatMemory.builder()
			.chatMemoryRepository(memoryRepository)
			.maxMessages(10)
			.build();
		
		this.chatClient = chatClientBuilder
			.defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
			.build();
	}

	@AgentCoreInvocation
	public Map<String, Object> handleChat(Map<String, Object> request, AgentCoreContext context) {
		try {
			// Extract user identity from JWT token in Authorization header
			String userId = extractUserIdFromContext(context);
			String sessionId = context.getHeader(AgentCoreHeaders.SESSION_ID);
			
			// Create conversation ID from user and session
			String conversationId = userId + ":" + sessionId;
			
			String message = (String) request.get("prompt");
			if (message == null) {
				message = (String) request.get("message");
			}
			
			if (message == null) {
				return Map.of(
					"error", "No message provided",
					"userId", userId,
					"sessionId", sessionId
				);
			}

			String response = chatClient.prompt()
				.user(message)
				.advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
				.call()
				.content();

			return Map.of(
				"response", response,
				"userId", userId,
				"sessionId", sessionId,
				"conversationId", conversationId,
				"messageCount", chatMemory.get(conversationId).size()
			);
			
		} catch (Exception e) {
			return Map.of(
				"error", "Failed to process chat: " + e.getMessage(),
				"sessionId", context.getHeader(AgentCoreHeaders.SESSION_ID)
			);
		}
	}

	private String extractUserIdFromContext(AgentCoreContext context) {
		try {
			// Get Authorization header
			String authHeader = context.getHeader("Authorization");
			if (authHeader != null && authHeader.startsWith("Bearer ")) {
				String token = authHeader.substring(7);
				
				// Decode JWT token (skip signature validation as AgentCore already validated it)
				String[] parts = token.split("\\.");
				if (parts.length >= 2) {
					String payload = new String(java.util.Base64.getUrlDecoder().decode(parts[1]));
					Map<String, Object> claims = objectMapper.readValue(payload, Map.class);
					
					// Try to get username from JWT claims
					String username = (String) claims.get("username");
					if (username != null) {
						return username;
					}
					
					// Fallback to 'sub' claim
					String sub = (String) claims.get("sub");
					if (sub != null) {
						return sub;
					}
				}
			}
			
			// Fallback to session ID if no user identity found
			return "user-" + context.getHeader(AgentCoreHeaders.SESSION_ID);
			
		} catch (JsonProcessingException e) {
			// Fallback to session ID if JWT parsing fails
			return "user-" + context.getHeader(AgentCoreHeaders.SESSION_ID);
		}
	}

	public record ChatRequest(String message) {}
	public record ChatResponse(String response) {}
}
