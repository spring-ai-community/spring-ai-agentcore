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

package org.springaicommunity.agentcore.memory.session;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import software.amazon.awssdk.services.bedrockagentcore.BedrockAgentCoreClient;
import software.amazon.awssdk.services.bedrockagentcore.model.CreateEventRequest;
import software.amazon.awssdk.services.bedrockagentcore.model.CreateEventResponse;
import software.amazon.awssdk.services.bedrockagentcore.model.Event;
import software.amazon.awssdk.services.bedrockagentcore.model.ListEventsRequest;
import software.amazon.awssdk.services.bedrockagentcore.model.ListEventsResponse;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.session.DefaultSessionService;
import org.springframework.ai.session.advisor.SessionMemoryAdvisor;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

/**
 * Drives a real {@link ChatClient} with a tool through {@link SessionMemoryAdvisor} on
 * {@link AgentCoreSessionRepository}, with an in-memory stand-in for AgentCore, and
 * asserts on the prompts the model receives.
 *
 * <p>
 * The {@code ChatClient} registers a {@code ToolCallingAdvisor} at
 * {@code HIGHEST_PRECEDENCE + 300} and switches its conversation history off when a
 * memory advisor is ordered after it. The repository stores message text only (no tool
 * calls, no tool results), so the session advisor has to wrap the tool loop instead of
 * rebuilding each tool round from AgentCore.
 *
 * @author Spring AI Community
 */
class SessionMemoryAdvisorToolLoopTests {

	private static final String SESSION_ID = "alice:conv-1";

	private static final String TOOL = "weather";

	private final List<Event> stored = new ArrayList<>();

	private final List<Prompt> prompts = new ArrayList<>();

	@Test
	void upstreamDefaultOrderSendsAToolResultWithoutItsToolCall() {
		// Pins why the auto-configured advisor does not keep the upstream default order.
		ChatClient chatClient = this.chatClient(null, "");
		this.ask(chatClient, "weather in Paris?");

		assertThat(this.prompts).hasSize(2);
		List<Message> toolRound = this.prompts.get(1).getInstructions();
		assertThat(toolRound.get(toolRound.size() - 1)).isInstanceOf(ToolResponseMessage.class);
		assertThat(toolRound).noneMatch(SessionMemoryAdvisorToolLoopTests::hasToolCalls);
	}

	@Test
	void upstreamDefaultOrderPersistsToolCallTextAsAnExtraAssistantTurn() {
		ChatClient chatClient = this.chatClient(null, "Let me check.");
		this.ask(chatClient, "weather in Paris?");

		assertThat(this.storedTexts()).containsExactly("weather in Paris?", "Let me check.", "It is sunny.");
		List<Message> toolRound = this.prompts.get(1).getInstructions();
		assertThat(toolRound).noneMatch(SessionMemoryAdvisorToolLoopTests::hasToolCalls);
	}

	@Test
	void moduleOrderKeepsTheToolExchangeAndTheHistoryOnceAcrossTurns() {
		ChatClient chatClient = this
			.chatClient(AgentCoreSessionRepositoryAutoConfiguration.SESSION_MEMORY_ADVISOR_ORDER, "");
		this.ask(chatClient, "weather in Paris?");
		this.ask(chatClient, "and in Rome?");

		assertThat(this.prompts).hasSize(4);
		assertThat(describe(this.prompts.get(1))).containsExactly("USER:weather in Paris?", "ASSISTANT:[weather]",
				"TOOL:[weather]");
		assertThat(describe(this.prompts.get(2))).containsExactly("USER:weather in Paris?", "ASSISTANT:It is sunny.",
				"USER:and in Rome?");
		assertThat(describe(this.prompts.get(3))).containsExactly("USER:weather in Paris?", "ASSISTANT:It is sunny.",
				"USER:and in Rome?", "ASSISTANT:[weather]", "TOOL:[weather]");
		assertThat(this.storedTexts()).containsExactly("weather in Paris?", "It is sunny.", "and in Rome?",
				"It is sunny.");
	}

	@Test
	void moduleOrderKeepsToolCallTextInTheLoopWithoutPersistingIt() {
		ChatClient chatClient = this
			.chatClient(AgentCoreSessionRepositoryAutoConfiguration.SESSION_MEMORY_ADVISOR_ORDER, "Let me check.");
		this.ask(chatClient, "weather in Paris?");

		assertThat(describe(this.prompts.get(1))).containsExactly("USER:weather in Paris?",
				"ASSISTANT:Let me check.[weather]", "TOOL:[weather]");
		assertThat(this.storedTexts()).containsExactly("weather in Paris?", "It is sunny.");
	}

	private void ask(ChatClient chatClient, String question) {
		String answer = chatClient.prompt()
			.user(question)
			.advisors((a) -> a.param(SessionMemoryAdvisor.SESSION_ID_CONTEXT_KEY, SESSION_ID))
			.call()
			.content();
		assertThat(answer).isEqualTo("It is sunny.");
	}

	private ChatClient chatClient(Integer order, String toolCallText) {
		BedrockAgentCoreClient client = Mockito.mock(BedrockAgentCoreClient.class);
		given(client.createEvent(any(CreateEventRequest.class))).willAnswer((invocation) -> {
			CreateEventRequest request = invocation.getArgument(0);
			Event event = Event.builder()
				.memoryId(request.memoryId())
				.actorId(request.actorId())
				.sessionId(request.sessionId())
				.eventId("e-" + this.stored.size())
				.eventTimestamp(request.eventTimestamp())
				.payload(request.payload())
				.build();
			this.stored.add(event);
			return CreateEventResponse.builder().event(event).build();
		});
		// AgentCore lists events newest first.
		given(client.listEvents(any(ListEventsRequest.class))).willAnswer((invocation) -> {
			ListEventsRequest request = invocation.getArgument(0);
			List<Event> newestFirst = new ArrayList<>();
			for (int i = this.stored.size() - 1; i >= 0; i--) {
				Event event = this.stored.get(i);
				if (event.actorId().equals(request.actorId()) && event.sessionId().equals(request.sessionId())) {
					newestFirst.add(event);
				}
			}
			return ListEventsResponse.builder().events(newestFirst).build();
		});
		AgentCoreSessionRepository repository = AgentCoreSessionRepository.builder()
			.memoryId("mem-1")
			.client(client)
			.ignoreUnknownRoles(true)
			.build();
		SessionMemoryAdvisor.Builder advisor = SessionMemoryAdvisor
			.builder(DefaultSessionService.builder().sessionRepository(repository).build());
		if (order != null) {
			advisor.order(order);
		}
		return ChatClient.builder(new ToolThenAnswerModel(toolCallText, this.prompts))
			.defaultAdvisors(advisor.build())
			.defaultToolCallbacks(new WeatherTool())
			.build();
	}

	private List<String> storedTexts() {
		return this.stored.stream()
			.flatMap((event) -> event.payload().stream())
			.map((payload) -> payload.conversational().content().text())
			.toList();
	}

	private static boolean hasToolCalls(Message message) {
		return message instanceof AssistantMessage assistant && assistant.hasToolCalls();
	}

	// Blank system messages are dropped: the ChatClient adds one on some paths only.
	private static List<String> describe(Prompt prompt) {
		List<String> described = new ArrayList<>();
		for (Message message : prompt.getInstructions()) {
			if (message.getMessageType() == MessageType.SYSTEM && message.getText().isBlank()) {
				continue;
			}
			if (message instanceof ToolResponseMessage toolResponse) {
				List<String> names = toolResponse.getResponses()
					.stream()
					.map(ToolResponseMessage.ToolResponse::name)
					.toList();
				described.add("TOOL:" + names);
			}
			else if (message instanceof AssistantMessage assistant && assistant.hasToolCalls()) {
				described.add("ASSISTANT:" + assistant.getText()
						+ assistant.getToolCalls().stream().map(AssistantMessage.ToolCall::name).toList());
			}
			else {
				described.add(message.getMessageType() + ":" + message.getText());
			}
		}
		return described;
	}

	/**
	 * Calls the tool once per question, then answers from the tool result.
	 */
	private static final class ToolThenAnswerModel implements ChatModel {

		private final String toolCallText;

		private final List<Prompt> prompts;

		ToolThenAnswerModel(String toolCallText, List<Prompt> prompts) {
			this.toolCallText = toolCallText;
			this.prompts = prompts;
		}

		@Override
		public ChatResponse call(Prompt prompt) {
			this.prompts.add(prompt);
			List<Message> instructions = prompt.getInstructions();
			AssistantMessage output = (instructions.get(instructions.size() - 1) instanceof ToolResponseMessage)
					? AssistantMessage.builder().content("It is sunny.").build()
					: AssistantMessage.builder().content(this.toolCallText).toolCalls(List.of(this.toolCall())).build();
			return new ChatResponse(List.of(new Generation(output)));
		}

		private AssistantMessage.ToolCall toolCall() {
			return new AssistantMessage.ToolCall("call-" + this.prompts.size(), "function", TOOL, "{}");
		}

		// The ChatClient derives the prompt options from these and attaches the tool
		// callbacks only to ToolCallingChatOptions.
		@Override
		public ChatOptions getOptions() {
			return ToolCallingChatOptions.builder().build();
		}

	}

	private static final class WeatherTool implements ToolCallback {

		@Override
		public ToolDefinition getToolDefinition() {
			return ToolDefinition.builder().name(TOOL).description("Current weather").inputSchema("{}").build();
		}

		@Override
		public String call(String toolInput) {
			return "sunny";
		}

	}

}
