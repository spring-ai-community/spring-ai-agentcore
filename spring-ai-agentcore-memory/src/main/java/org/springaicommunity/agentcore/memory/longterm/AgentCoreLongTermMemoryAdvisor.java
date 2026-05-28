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

import java.util.Map;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springaicommunity.agentcore.memory.AgentCoreMemoryConversationIdParser;
import org.springaicommunity.agentcore.memory.longterm.strategy.MemoryStrategyHandler;
import org.springaicommunity.agentcore.memory.longterm.strategy.MemoryStrategyHandler.MemoryFetchContext;
import org.springaicommunity.agentcore.memory.longterm.strategy.MemoryStrategyHandler.MemoryFetchResult;
import reactor.core.publisher.Flux;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.memory.ChatMemory;

/**
 * Unified advisor for long-term memory retrieval. Holds a single
 * {@link MemoryStrategyHandler} and delegates each turn's fetch / format / inject to it.
 *
 * <p>
 * Four built-in strategy handlers ship with the module
 * ({@code SemanticMemoryStrategyHandler}, {@code UserPreferenceMemoryStrategyHandler},
 * {@code SummaryMemoryStrategyHandler}, {@code EpisodicMemoryStrategyHandler}). Each has
 * its own Builder; callers construct the handler and pass it to this advisor's Builder.
 *
 * <p>
 * Uses the same {@code conversationId} format as STM: {@code userId} or
 * {@code userId:sessionId}.
 *
 * @author Yuriy Bezsonov
 */
public final class AgentCoreLongTermMemoryAdvisor implements CallAdvisor, StreamAdvisor {

	private static final Logger logger = LoggerFactory.getLogger(AgentCoreLongTermMemoryAdvisor.class);

	// ------------------------------------------------------------------
	// Configuration and collaborators (immutable after construction)
	// ------------------------------------------------------------------

	private final AgentCoreLongTermMemoryRetriever retriever;

	private final MemoryStrategyHandler handler;

	private final AgentCoreLongTermMemoryStrategyType memoryStrategy;

	private final int order;

	// ------------------------------------------------------------------
	// Construction
	// ------------------------------------------------------------------

	private AgentCoreLongTermMemoryAdvisor(Builder builder) {
		this.retriever = builder.retriever;
		this.handler = builder.handler;
		this.memoryStrategy = builder.memoryStrategy;
		this.order = (builder.order != null) ? builder.order : this.memoryStrategy.getOrder();
		logger.info("AgentCoreLongTermMemoryAdvisor initialized: mode={}, strategyId={}", this.memoryStrategy,
				this.handler.strategyId());
	}

	public static Builder builder(AgentCoreLongTermMemoryRetriever retriever) {
		return new Builder(retriever);
	}

	// ------------------------------------------------------------------
	// Advisor API (call + stream entry points)
	// ------------------------------------------------------------------

	@Override
	public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
		return chain.nextCall(this.enrichRequest(request));
	}

	@Override
	public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
		return chain.nextStream(this.enrichRequest(request));
	}

	// ------------------------------------------------------------------
	// Orchestration: single linear path, all strategy-specific logic in the handler
	// ------------------------------------------------------------------

	private ChatClientRequest enrichRequest(ChatClientRequest request) {
		AgentCoreMemoryConversationIdParser.ActorAndSession parsed = this.parseConversationId(request);
		String userId = parsed.actor();
		String sessionId = parsed.session();
		String userPrompt = this.extractUserText(request);

		if (this.handler.requiresUserPrompt() && (userPrompt == null || userPrompt.isEmpty())) {
			return request;
		}

		MemoryFetchContext ctx = new MemoryFetchContext(this.retriever, userId, sessionId, userPrompt);
		MemoryFetchResult fetched = this.handler.fetch(ctx);
		if (fetched.isEmpty()) {
			logger.info("No memories found for strategy {} / user {}", this.handler.strategyId(), userId);
			return request;
		}

		String context = this.handler.format(ctx, fetched);
		logger.info("Enriched prompt with {} records for strategy {} / user {}", fetched.totalCount(),
				this.handler.strategyId(), userId);
		return this.handler.inject(request, context);
	}

	// ------------------------------------------------------------------
	// Request parsing helpers
	// ------------------------------------------------------------------

	private AgentCoreMemoryConversationIdParser.ActorAndSession parseConversationId(ChatClientRequest request) {
		String conversationId = this.extractParam(request, ChatMemory.CONVERSATION_ID);
		if (conversationId == null || conversationId.isEmpty()) {
			throw new IllegalStateException("LTM advisor requires '" + ChatMemory.CONVERSATION_ID
					+ "' parameter (format: 'userId' or 'userId:sessionId'). "
					+ "Add .param(ChatMemory.CONVERSATION_ID, conversationId) to your ChatClient call.");
		}
		return AgentCoreMemoryConversationIdParser.parse(conversationId);
	}

	private String extractUserText(ChatClientRequest request) {
		var userMessage = request.prompt().getUserMessage();
		return (userMessage != null) ? userMessage.getText() : null;
	}

	private String extractParam(ChatClientRequest request, String paramName) {
		Map<String, Object> context = request.context();
		if (context != null && context.containsKey(paramName)) {
			return context.get(paramName).toString();
		}
		return null;
	}

	// ------------------------------------------------------------------
	// Spring AI Advisor identity
	// ------------------------------------------------------------------

	@Override
	public String getName() {
		return "AgentCoreLongTermMemoryAdvisor-" + this.memoryStrategy;
	}

	@Override
	public int getOrder() {
		return this.order;
	}

	public static final class Builder {

		private final AgentCoreLongTermMemoryRetriever retriever;

		private MemoryStrategyHandler handler;

		private AgentCoreLongTermMemoryStrategyType memoryStrategy = AgentCoreLongTermMemoryStrategyType.CUSTOM;

		private Integer order;

		private Builder(AgentCoreLongTermMemoryRetriever retriever) {
			Objects.requireNonNull(retriever, "AgentCore Long-Term memory retriever is required");
			this.retriever = retriever;
		}

		/**
		 * Supply the strategy handler that drives fetch / format / inject. Use one of the
		 * built-in handlers or a user-provided implementation of
		 * {@link MemoryStrategyHandler}.
		 * @param handler the strategy handler
		 * @return this builder
		 */
		public Builder handler(MemoryStrategyHandler handler) {
			Objects.requireNonNull(handler, "handler is required");
			this.handler = handler;
			return this;
		}

		/**
		 * Declares which built-in strategy type this advisor represents. Auto-config and
		 * auto-discovery use this to set the advisor name (e.g.
		 * {@code AgentCoreLongTermMemoryAdvisor-SEMANTIC}) and the default advisor order.
		 * Custom handlers can leave this unset; the advisor defaults to
		 * {@link AgentCoreLongTermMemoryStrategyType#CUSTOM}.
		 * @param memoryStrategy the built-in strategy type
		 * @return this builder
		 */
		public Builder memoryStrategy(AgentCoreLongTermMemoryStrategyType memoryStrategy) {
			Objects.requireNonNull(memoryStrategy, "memoryStrategy is required");
			this.memoryStrategy = memoryStrategy;
			return this;
		}

		public Builder order(int order) {
			this.order = order;
			return this;
		}

		public AgentCoreLongTermMemoryAdvisor build() {
			if (this.handler == null) {
				throw new IllegalArgumentException("handler is required (call .handler(...) on the Builder)");
			}
			return new AgentCoreLongTermMemoryAdvisor(this);
		}

	}

}
