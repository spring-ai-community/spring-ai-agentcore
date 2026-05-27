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

package org.springaicommunity.agentcore.memory.longterm.strategy;

/**
 * Semantic-search strategy. Fetches facts by semantic similarity against the user prompt
 * and merges them into the system message.
 */
public final class SemanticMemoryStrategyHandler implements MemoryStrategyHandler {

	private final String strategyId;

	private final String namespacePattern;

	private final int topK;

	private final String contextLabel;

	public SemanticMemoryStrategyHandler(String strategyId, String namespacePattern, int topK, String contextLabel) {
		this.strategyId = strategyId;
		this.namespacePattern = namespacePattern;
		this.topK = topK;
		this.contextLabel = contextLabel;
	}

	public static Builder builder() {
		return new Builder();
	}

	@Override
	public String strategyId() {
		return this.strategyId;
	}

	@Override
	public MemoryFetchResult fetch(MemoryFetchContext ctx) {
		return MemoryFetchResult.primaryOnly(ctx.retriever()
			.searchMemories(this.strategyId, ctx.userId(), ctx.sessionId(), ctx.userPrompt(), this.topK,
					this.namespacePattern));
	}

	@Override
	public String format(MemoryFetchContext ctx, MemoryFetchResult fetched) {
		return MemoryStrategyHandler.formatMemorySection(this.contextLabel, fetched.primary());
	}

	@Override
	public InjectionTarget target() {
		return InjectionTarget.SYSTEM;
	}

	public static final class Builder {

		private String strategyId;

		private String namespacePattern;

		private int topK = 3;

		private String contextLabel;

		private Builder() {
		}

		public Builder strategyId(String strategyId) {
			this.strategyId = strategyId;
			return this;
		}

		public Builder namespacePattern(String namespacePattern) {
			this.namespacePattern = namespacePattern;
			return this;
		}

		public Builder topK(int topK) {
			this.topK = topK;
			return this;
		}

		public Builder contextLabel(String contextLabel) {
			this.contextLabel = contextLabel;
			return this;
		}

		public SemanticMemoryStrategyHandler build() {
			if (this.strategyId == null || this.strategyId.isEmpty()) {
				throw new IllegalArgumentException("strategyId is required");
			}
			return new SemanticMemoryStrategyHandler(this.strategyId, this.namespacePattern, this.topK,
					this.contextLabel);
		}

	}

}
