package org.springaicommunity.example;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
public class ChatController {

	private final ChatClient chatClient;
	private final ChatMemory chatMemory;
	private static final String CONVERSATION_ID = "testActor:testSession";

	public ChatController(ChatClient.Builder chatClientBuilder, ChatMemoryRepository memoryRepository) {
		this.chatMemory = MessageWindowChatMemory.builder()
			.chatMemoryRepository(memoryRepository)
			.maxMessages(10)
			.build();
		
		this.chatClient = chatClientBuilder
			.defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
			.build();
	}

	@PostMapping("/api/chat")
	public ChatResponse chat(@RequestBody ChatRequest request) {
		String response = chatClient.prompt()
			.user(request.message())
			.advisors(a -> a.param(ChatMemory.CONVERSATION_ID, CONVERSATION_ID))
			.call()
			.content();

		return new ChatResponse(response);
	}

	@GetMapping("/api/chat/history")
	public List<org.springframework.ai.chat.messages.Message> getHistory() {
		return chatMemory.get(CONVERSATION_ID);
	}

	@DeleteMapping("/api/chat/history")
	public void clearHistory() {
		chatMemory.clear(CONVERSATION_ID);
	}

	public record ChatRequest(String message) {}
	public record ChatResponse(String response) {}

}
