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

package org.springaicommunity.example.observability;

import java.util.Map;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

import org.springaicommunity.agentcore.annotation.AgentCoreInvocation;
import org.springaicommunity.agentcore.context.AgentCoreContext;
import org.springaicommunity.agentcore.context.AgentCoreHeaders;
import org.springaicommunity.agentcore.memory.shorttem.AgentCoreShortTermMemoryRepository;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.stereotype.Service;

/**
 * Spring AI agent with short-term memory. Uses the AgentCore session ID as the
 * conversation ID, so memory persists across invocations within the same session.
 *
 * @author Maximilian Schellhorn
 */
@Service
public class ObservabilityChatController {

	private final ChatClient chatClient;

	private final ChatMemory chatMemory;

	private final Counter invocationCounter;

	public ObservabilityChatController(ChatClient.Builder chatClientBuilder,
			AgentCoreShortTermMemoryRepository memoryRepository, MeterRegistry meterRegistry) {
		this.chatMemory = MessageWindowChatMemory.builder()
			.chatMemoryRepository(memoryRepository)
			.maxMessages(20)
			.build();
		this.chatClient = chatClientBuilder.defaultTools(new DateTimeTools()).build();
		this.invocationCounter = Counter.builder("custom.agent.invocations")
			.description("Number of agent invocations")
			.tag("agent", "observability-demo")
			.register(meterRegistry);
	}

	@AgentCoreInvocation
	public Map<String, Object> chat(Map<String, Object> request, AgentCoreContext context) {
		String prompt = String.valueOf(request.getOrDefault("prompt", ""));
		String sessionId = context.getHeader(AgentCoreHeaders.SESSION_ID);
		String conversationId = (sessionId != null && !sessionId.isEmpty()) ? sessionId : "default";

		this.invocationCounter.increment();

		String response = this.chatClient.prompt()
			.user(prompt)
			.advisors(MessageChatMemoryAdvisor.builder(this.chatMemory).build())
			.advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
			.call()
			.content();

		return Map.of("response", response);
	}

}
