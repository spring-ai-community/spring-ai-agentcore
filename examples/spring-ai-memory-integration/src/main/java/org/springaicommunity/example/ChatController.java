/*
 * Copyright 2025-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springaicommunity.example;

import java.util.Comparator;
import java.util.List;

import org.springaicommunity.agentcore.memory.longterm.AgentCoreLongTermMemoryAdvisor;
import org.springaicommunity.agentcore.memory.longterm.AgentCoreMemory;
import org.springaicommunity.agentcore.memory.shortterm.AgentCoreShortTermMemoryRepository;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ChatController {

	private static final String CONVERSATION_ID = "testActor:testSession";

	private final ChatClient shortTermChatClient;

	private final ChatClient longTermChatClient;

	private final ChatMemory chatMemory;

	private final AgentCoreMemory agentCoreMemory;

	public ChatController(ChatClient.Builder chatClientBuilder, AgentCoreMemory agentCoreMemory, ChatMemory chatMemory,
			AgentCoreShortTermMemoryRepository memoryRepository) {
		this.agentCoreMemory = agentCoreMemory;
		this.chatMemory = chatMemory;
		this.shortTermChatClient = chatClientBuilder.build();
		this.longTermChatClient = chatClientBuilder.build();

		// NOTE: the short-term memory events are cleared on startup so the example
		// always runs from a clean initial state.
		memoryRepository.deleteByConversationId(CONVERSATION_ID);
	}

	@PostMapping("/api/short")
	public ChatResponse shortChat(@RequestBody ChatRequest request) {
		String response = this.shortTermChatClient.prompt()
			.user(request.message())
			.advisors(this.agentCoreMemory.shortTermMemoryAdvisor)
			.advisors(a -> a.param(ChatMemory.CONVERSATION_ID, CONVERSATION_ID))
			.call()
			.content();

		return new ChatResponse(response);
	}

	@PostMapping("/api/long")
	public ChatResponse longChat(@RequestBody ChatRequest request) {
		String response = this.longTermChatClient.prompt()
			.user(request.message())
			.advisors(this.agentCoreMemory.advisors)
			.advisors(a -> a.param(ChatMemory.CONVERSATION_ID, CONVERSATION_ID))
			.call()
			.content();

		return new ChatResponse(response);
	}

	@GetMapping("/api/history")
	public List<Message> getHistory() {
		return this.chatMemory.get(CONVERSATION_ID);
	}

	@DeleteMapping("/api/history")
	public void clearHistory() {
		this.chatMemory.clear(CONVERSATION_ID);
	}

	/**
	 * Lists the long-term memory advisors wired up by the module, in retrieval order.
	 * Useful to verify that auto-discovery (or explicit config) picked up the strategies
	 * you expect. Does not fetch records — the LTM advisors themselves fetch per request
	 * and log a line like {@code "Enriched prompt with N records for strategy X"} at
	 * INFO when they find data.
	 */
	@GetMapping("/api/ltm/strategies")
	public List<StrategyInfo> getLtmStrategies() {
		return this.agentCoreMemory.longTermMemoryAdvisors.stream()
			.sorted(Comparator.comparingInt(AgentCoreLongTermMemoryAdvisor::getOrder))
			.map(a -> new StrategyInfo(a.getName(), a.getOrder()))
			.toList();
	}

	public record ChatRequest(String message) {
	}

	public record ChatResponse(String response) {
	}

	public record StrategyInfo(String name, int order) {
	}

}
