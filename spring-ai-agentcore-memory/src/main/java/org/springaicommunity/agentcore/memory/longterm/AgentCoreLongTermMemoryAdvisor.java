/*
 * Copyright 2025-2025 the original author or authors.
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
import java.util.Map;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springaicommunity.agentcore.memory.AgentCoreMemoryConversationIdParser;
import org.springaicommunity.agentcore.memory.longterm.AgentCoreLongTermMemoryRetriever.MemoryRecord;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;

import reactor.core.publisher.Flux;

/**
 * Unified advisor for all long-term memory strategies. Supports 4 modes:
 * <ul>
 * <li>SEMANTIC: Semantic search for facts -> system message</li>
 * <li>USER_PREFERENCE: List all user preferences -> system message</li>
 * <li>EPISODIC: Search episodes + reflections -> system message</li>
 * <li>SUMMARY: Search summaries -> user message (augments query)</li>
 * </ul>
 *
 * <p>
 * Uses the same {@code conversationId} format as STM: {@code userId} or
 * {@code userId:sessionId}. This allows a single param for both STM and LTM.
 * </p>
 *
 * @author Yuriy Bezsonov
 */
public class AgentCoreLongTermMemoryAdvisor implements CallAdvisor, StreamAdvisor {

	private static final Logger logger = LoggerFactory.getLogger(AgentCoreLongTermMemoryAdvisor.class);

	private final AgentCoreLongTermMemoryRetriever retriever;

	private final String strategyId;

	private final String reflectionsStrategyId;

	private final String contextLabel;

	private final MemoryStrategy memoryStrategy;

	private final int order;

	private final int topK;

	private final int reflectionsTopK;

	private final String namespacePattern;

	public enum MemoryStrategy {

		SEMANTIC(100), USER_PREFERENCE(200), SUMMARY(300), EPISODIC(400);

		private final int order;

		MemoryStrategy(int order) {
			this.order = order;
		}

		public int getOrder() {
			return this.order;
		}

	}

	private AgentCoreLongTermMemoryAdvisor(Builder builder) {
		this.retriever = builder.retriever;
		this.strategyId = builder.strategyId;
		this.reflectionsStrategyId = builder.reflectionsStrategyId;
		this.contextLabel = builder.contextLabel;
		this.memoryStrategy = builder.mode;
		this.order = builder.order != null ? builder.order : builder.mode.getOrder();
		this.topK = builder.topK;
		this.reflectionsTopK = builder.reflectionsTopK;
		this.namespacePattern = builder.namespacePattern;
		logger.info(
				"AgentCoreLongTermMemoryAdvisor initialized: mode={}, strategyId={}, reflectionsStrategyId={}, namespacePattern={}",
				this.memoryStrategy, this.strategyId, this.reflectionsStrategyId, this.namespacePattern);
	}

	public static Builder builder(AgentCoreLongTermMemoryRetriever retriever, MemoryStrategy mode) {
		return new Builder(retriever, mode);
	}

	public static class Builder {

		private final AgentCoreLongTermMemoryRetriever retriever;

		private final MemoryStrategy mode;

		private String strategyId;

		private String reflectionsStrategyId;

		private String contextLabel;

		private Integer order;

		private int topK = 3;

		private int reflectionsTopK = 2;

		private String namespacePattern = AgentCoreLongTermMemoryNamespace.ACTOR.getPattern();

		private Builder(AgentCoreLongTermMemoryRetriever retriever, MemoryStrategy mode) {
			Objects.requireNonNull(retriever, "AgentCore Long-Term memory retriever is required");
			Objects.requireNonNull(mode, "mode is required");
			this.retriever = retriever;
			this.mode = mode;
		}

		public Builder strategyId(String strategyId) {
			this.strategyId = strategyId;
			return this;
		}

		public Builder reflectionsStrategyId(String reflectionsStrategyId) {
			this.reflectionsStrategyId = reflectionsStrategyId;
			return this;
		}

		public Builder contextLabel(String contextLabel) {
			this.contextLabel = contextLabel;
			return this;
		}

		public Builder order(int order) {
			this.order = order;
			return this;
		}

		public Builder topK(int topK) {
			this.topK = topK;
			return this;
		}

		public Builder reflectionsTopK(int reflectionsTopK) {
			this.reflectionsTopK = reflectionsTopK;
			return this;
		}

		public Builder namespacePattern(String namespacePattern) {
			this.namespacePattern = namespacePattern != null ? namespacePattern
					: AgentCoreLongTermMemoryNamespace.ACTOR.getPattern();
			return this;
		}

		public AgentCoreLongTermMemoryAdvisor build() {
			if (this.strategyId == null || this.strategyId.isEmpty()) {
				throw new IllegalArgumentException("strategyId is required");
			}
			return new AgentCoreLongTermMemoryAdvisor(this);
		}

	}

	@Override
	public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
		return chain.nextCall(enrichRequest(request));
	}

	@Override
	public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
		return chain.nextStream(enrichRequest(request));
	}

	private ChatClientRequest enrichRequest(ChatClientRequest request) {
		AgentCoreMemoryConversationIdParser.ActorAndSession parsed = parseConversationId(request);
		String userId = parsed.actor();
		String sessionId = parsed.session();

		if (this.memoryStrategy == MemoryStrategy.SUMMARY) {
			return enrichWithSummary(request, userId, sessionId);
		}

		if (this.memoryStrategy == MemoryStrategy.EPISODIC) {
			return enrichWithEpisodic(request, userId, sessionId);
		}

		List<MemoryRecord> memories = fetchMemories(request, userId, sessionId);
		if (memories.isEmpty()) {
			logger.debug("No {} found for user: {}", this.contextLabel, userId);
			return request;
		}

		String context = formatContext(memories);
		logger.debug("Enriched system prompt with {} {} for user: {}", memories.size(), this.contextLabel, userId);
		return addToSystemMessage(request, context);
	}

	/**
	 * Parse conversationId into actor and session.
	 */
	private AgentCoreMemoryConversationIdParser.ActorAndSession parseConversationId(ChatClientRequest request) {
		String conversationId = extractParam(request, ChatMemory.CONVERSATION_ID);
		if (conversationId == null || conversationId.isEmpty()) {
			throw new IllegalStateException("LTM advisor requires '" + ChatMemory.CONVERSATION_ID
					+ "' parameter (format: 'userId' or 'userId:sessionId'). "
					+ "Add .param(ChatMemory.CONVERSATION_ID, conversationId) to your ChatClient call.");
		}
		return AgentCoreMemoryConversationIdParser.parse(conversationId);
	}

	private ChatClientRequest enrichWithEpisodic(ChatClientRequest request, String userId, String sessionId) {
		String userPrompt = extractUserText(request);
		if (userPrompt == null || userPrompt.isEmpty()) {
			return request;
		}

		List<MemoryRecord> episodes = this.retriever.searchMemories(this.strategyId, userId, sessionId, userPrompt,
				this.topK, this.namespacePattern);

		List<MemoryRecord> reflections;
		if (this.reflectionsStrategyId != null && !this.reflectionsStrategyId.isEmpty()) {
			reflections = this.retriever.searchMemories(this.reflectionsStrategyId, userId, sessionId, userPrompt,
					this.reflectionsTopK, this.namespacePattern);
		}
		else {
			reflections = List.of();
		}

		if (episodes.isEmpty() && reflections.isEmpty()) {
			logger.debug("No episodes or reflections found for user: {}", userId);
			return request;
		}

		String context = formatEpisodicContext(episodes, reflections);
		logger.debug("Enriched system prompt with {} episodes and {} reflections for user: {}", episodes.size(),
				reflections.size(), userId);
		return addToSystemMessage(request, context);
	}

	private ChatClientRequest enrichWithSummary(ChatClientRequest request, String userId, String sessionId) {
		String userPrompt = extractUserText(request);
		if (userPrompt == null || userPrompt.isEmpty()) {
			return request;
		}

		List<MemoryRecord> summaries = this.retriever.searchMemories(this.strategyId, userId, sessionId, userPrompt,
				this.topK, this.namespacePattern);
		if (summaries.isEmpty()) {
			logger.debug("No summaries found for user: {}, session: {}", userId, sessionId);
			return request;
		}

		String augmentedPrompt = formatSummaryContext(userPrompt, summaries);
		logger.debug("Enriched user prompt with {} summaries for user: {}", summaries.size(), userId);
		return augmentUserMessage(request, augmentedPrompt);
	}

	private List<MemoryRecord> fetchMemories(ChatClientRequest request, String userId, String sessionId) {
		if (this.memoryStrategy == MemoryStrategy.SEMANTIC) {
			String userPrompt = extractUserText(request);
			if (userPrompt == null || userPrompt.isEmpty()) {
				return List.of();
			}
			return this.retriever.searchMemories(this.strategyId, userId, sessionId, userPrompt, this.topK,
					this.namespacePattern);
		}
		else {
			return this.retriever.listMemories(this.strategyId, userId, this.namespacePattern);
		}
	}

	private ChatClientRequest addToSystemMessage(ChatClientRequest request, String context) {
		List<Message> messages = new ArrayList<>();
		boolean merged = false;
		for (Message msg : request.prompt().getInstructions()) {
			if (msg instanceof SystemMessage && !merged) {
				messages.add(new SystemMessage(msg.getText() + "\n\n" + context));
				merged = true;
			}
			else {
				messages.add(msg);
			}
		}
		if (!merged) {
			messages.add(0, new SystemMessage(context));
		}
		return request.mutate().prompt(new Prompt(messages, request.prompt().getOptions())).build();
	}

	private ChatClientRequest augmentUserMessage(ChatClientRequest request, String newUserText) {
		List<Message> messages = new ArrayList<>();
		for (Message msg : request.prompt().getInstructions()) {
			if (msg instanceof UserMessage) {
				messages.add(new UserMessage(newUserText));
			}
			else {
				messages.add(msg);
			}
		}
		return request.mutate().prompt(new Prompt(messages, request.prompt().getOptions())).build();
	}

	private String extractUserText(ChatClientRequest request) {
		var userMessage = request.prompt().getUserMessage();
		return userMessage != null ? userMessage.getText() : null;
	}

	private String extractParam(ChatClientRequest request, String paramName) {
		Map<String, Object> context = request.context();
		if (context != null && context.containsKey(paramName)) {
			return context.get(paramName).toString();
		}
		return null;
	}

	private String formatContext(List<MemoryRecord> memories) {
		return formatMemorySection(this.contextLabel, memories);
	}

	private String formatEpisodicContext(List<MemoryRecord> episodes, List<MemoryRecord> reflections) {
		StringBuilder sb = new StringBuilder();
		if (!episodes.isEmpty()) {
			sb.append(formatMemorySection("Relevant past interactions", episodes));
		}
		if (!reflections.isEmpty()) {
			if (!episodes.isEmpty()) {
				sb.append("\n");
			}
			sb.append(formatMemorySection("Lessons learned", reflections));
		}
		return sb.toString();
	}

	private String formatSummaryContext(String originalPrompt, List<MemoryRecord> summaries) {
		return formatMemorySection(this.contextLabel, summaries) + "\nUser question: " + originalPrompt;
	}

	private String formatMemorySection(String header, List<MemoryRecord> records) {
		StringBuilder sb = new StringBuilder();
		sb.append(header).append(":\n");
		for (MemoryRecord record : records) {
			sb.append("- ").append(record.content()).append("\n");
		}
		return sb.toString();
	}

	@Override
	public String getName() {
		return "AgentCoreLongTermMemoryAdvisor-" + this.memoryStrategy;
	}

	@Override
	public int getOrder() {
		return this.order;
	}

}
