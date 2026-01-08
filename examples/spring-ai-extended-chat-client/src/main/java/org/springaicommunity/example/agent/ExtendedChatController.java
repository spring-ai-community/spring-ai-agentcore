package org.springaicommunity.example.agent;

import org.springaicommunity.agentcore.annotation.AgentCoreInvocation;
import org.springaicommunity.agentcore.context.AgentCoreContext;
import org.springaicommunity.agentcore.context.AgentCoreHeaders;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
public class ExtendedChatController {

	private static final String AUTHORIZATION_HEADER = "Authorization";
	private static final String BEARER_PREFIX = "Bearer ";
	private static final String ANONYMOUS_USER = "ANONYMOUS_USER";

	private final ChatClient chatClient;
	private final ChatMemory chatMemory;
	private final JwtUtil jwtUtil;

	public ExtendedChatController(ChatClient.Builder chatClientBuilder, 
								  ChatMemoryRepository memoryRepository,
								  JwtUtil jwtUtil) {
		this.jwtUtil = jwtUtil;
		this.chatMemory = MessageWindowChatMemory.builder()
				.chatMemoryRepository(memoryRepository)
				.maxMessages(10)
				.build();
		
		this.chatClient = chatClientBuilder
				.defaultTools(new DateTimeTools())
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
		// Get Authorization header
		String authHeader = context.getHeader(AUTHORIZATION_HEADER);
		
		if (authHeader != null && authHeader.startsWith(BEARER_PREFIX)) {
			String token = authHeader.substring(BEARER_PREFIX.length());
			String userId = jwtUtil.extractUserId(token);
			if (userId != null) {
				return userId;
			}
		}
		
		// Fallback to anonymous user if no user identity found
		return ANONYMOUS_USER;
	}
}
