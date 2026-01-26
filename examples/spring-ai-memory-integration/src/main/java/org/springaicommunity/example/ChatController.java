package org.springaicommunity.example;

import org.springaicommunity.agentcore.memory.*;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
public class ChatController {

	private final ChatClient shortTermChatClient;
	private final ChatClient longTermChatClient;
	private final ChatMemory chatMemory;
	private final AgentCoreMemory agentCoreMemory;
	private final AgentCoreLongTermMemoryRetriever retriever;
	private final AgentCoreLongTermMemoryProperties config;

	private static final String CONVERSATION_ID = "testActor:testSession";

	public ChatController(
			ChatClient.Builder chatClientBuilder,
			AgentCoreMemory agentCoreMemory, ChatMemory chatMemory,
			AgentCoreLongTermMemoryRetriever retriever, AgentCoreLongTermMemoryProperties config,
			AgentCoreShortTermMemoryRepository memoryRepository) {
		this.agentCoreMemory = agentCoreMemory;
        this.chatMemory = chatMemory;
		this.retriever = retriever;
		this.config = config;

        this.shortTermChatClient = chatClientBuilder.build();
		this.longTermChatClient = chatClientBuilder.build();

		// NOTE! The short-term memory events are removed on startup to run example on clean initial state
		memoryRepository.deleteByConversationId(CONVERSATION_ID);
    }

	@PostMapping("/api/short")
	public ChatResponse shortChat(@RequestBody ChatRequest request) {
		String response = shortTermChatClient.prompt()
				.user(request.message())
				.advisors(agentCoreMemory.shortTermMemoryAdvisor)
				.advisors(a -> a.param(ChatMemory.CONVERSATION_ID, CONVERSATION_ID))
				.call()
				.content();

		return new ChatResponse(response);
	}

	@PostMapping("/api/long")
	public ChatResponse longChat(@RequestBody ChatRequest request) {
		String response = longTermChatClient.prompt()
				.user(request.message())
				.advisors(agentCoreMemory.advisors)
				.advisors(a -> a.param(ChatMemory.CONVERSATION_ID, CONVERSATION_ID))
				.call()
				.content();

		return new ChatResponse(response);
	}

	@GetMapping("/api/history")
	public List<Message> getHistory() {
		return chatMemory.get(CONVERSATION_ID);
	}

	@DeleteMapping("/api/history")
	public void clearHistory() {
		chatMemory.clear(CONVERSATION_ID);
	}

	@GetMapping("/api/memories")
	public List<AgentCoreLongTermMemoryRetriever.MemoryRecord> getMemories() {
		return retriever.listMemories(config.summary().strategyId(), "testActor");
	}

	public record ChatRequest(String message) {}
	public record ChatResponse(String response) {}

}
