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

package org.springaicommunity.agentcore.memory.longterm;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springaicommunity.agentcore.memory.longterm.AgentCoreLongTermMemoryRetriever.MemoryRecord;
import org.springaicommunity.agentcore.memory.longterm.strategy.SummaryMemoryStrategyHandler;
import org.springaicommunity.agentcore.memory.session.AgentCoreSessionRepositoryAutoConfiguration;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.session.DefaultSessionService;
import org.springframework.ai.session.InMemorySessionRepository;
import org.springframework.ai.session.SessionService;
import org.springframework.ai.session.advisor.SessionMemoryAdvisor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/**
 * Drives a real {@link ChatClient} through {@link SessionMemoryAdvisor} (ordered as the
 * auto-configuration orders it) and the summary long-term memory advisor, and asserts on
 * the prompts the model receives and on the stored session events.
 *
 * <p>
 * The session advisor runs first and adds the conversation history to the prompt. The
 * summary advisor must augment only the current user message and leave the history
 * unchanged.
 *
 * @author Spring AI Community
 */
class SummaryAdvisorWithSessionHistoryTests {

	private static final String SESSION_ID = "alice:session-1";

	@Test
	void summaryAugmentsOnlyTheCurrentUserMessageAndKeepsHistory() {
		AgentCoreLongTermMemoryRetriever retriever = mock(AgentCoreLongTermMemoryRetriever.class);
		given(retriever.searchMemories(anyString(), anyString(), anyString(), anyString(), anyInt(), any()))
			.willReturn(List.of(new MemoryRecord("1", "Alice introduced herself.", 0.9)));
		AgentCoreLongTermMemoryAdvisor summaryAdvisor = AgentCoreLongTermMemoryAdvisor.builder(retriever)
			.memoryStrategy(AgentCoreLongTermMemoryStrategyType.SUMMARY)
			.handler(SummaryMemoryStrategyHandler.builder()
				.strategyId("summary-1")
				.namespacePattern(AgentCoreLongTermMemoryNamespace.SESSION.getPattern())
				.contextLabel("Previous conversation summaries")
				.build())
			.build();

		SessionService sessionService = DefaultSessionService.builder()
			.sessionRepository(InMemorySessionRepository.builder().build())
			.build();
		SessionMemoryAdvisor sessionAdvisor = SessionMemoryAdvisor.builder(sessionService)
			.order(AgentCoreSessionRepositoryAutoConfiguration.SESSION_MEMORY_ADVISOR_ORDER)
			.build();

		List<Prompt> prompts = new ArrayList<>();
		ChatModel model = mock(ChatModel.class);
		given(model.getOptions()).willReturn(ChatOptions.builder().build());
		given(model.call(any(Prompt.class))).willAnswer((invocation) -> {
			prompts.add(invocation.getArgument(0));
			return new ChatResponse(List.of(new Generation(new AssistantMessage("answer " + prompts.size()))));
		});
		ChatClient chatClient = ChatClient.builder(model).defaultAdvisors(sessionAdvisor, summaryAdvisor).build();

		for (String question : List.of("My name is Alice", "I like tea", "What is my name?")) {
			chatClient.prompt()
				.user(question)
				.advisors((a) -> a.param(SessionMemoryAdvisor.SESSION_ID_CONTEXT_KEY, SESSION_ID))
				.call()
				.content();
		}

		List<String> promptUserTexts = userTexts(prompts.get(2).getInstructions());
		assertThat(promptUserTexts).hasSize(3);
		assertThat(promptUserTexts.get(0)).isEqualTo("My name is Alice");
		assertThat(promptUserTexts.get(1)).isEqualTo("I like tea");
		assertThat(promptUserTexts.get(2)).contains("Alice introduced herself.", "What is my name?");

		assertThat(userTexts(sessionService.getMessages(SESSION_ID))).containsExactly("My name is Alice", "I like tea",
				"What is my name?");
	}

	private static List<String> userTexts(List<Message> messages) {
		return messages.stream().filter(UserMessage.class::isInstance).map(Message::getText).toList();
	}

}
