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

import java.util.List;

import org.springaicommunity.agentcore.memory.longterm.AgentCoreLongTermMemoryRetriever.MemoryRecord;

/**
 * Episodic strategy. Retrieves past interaction episodes plus, optionally, higher-level
 * reflections and merges both into the system message.
 *
 * <p>
 * Reflections are fetched via one of two paths:
 * <ul>
 * <li><b>Modern (preferred)</b>: same {@code strategyId} as episodes, a second namespace
 * pattern. Matches the AWS AgentCore Memory model.</li>
 * <li><b>Legacy (deprecated)</b>: a separate {@code reflectionsStrategyId} using the same
 * namespace as episodes. Kept for one release; construction-time warning comes from the
 * properties record.</li>
 * </ul>
 */
public final class EpisodicMemoryStrategyHandler implements MemoryStrategyHandler {

	private final String strategyId;

	private final String namespacePattern;

	private final int episodesTopK;

	private final int reflectionsTopK;

	private final String reflectionsNamespacePattern;

	// Legacy: kept for backward compatibility with deprecated reflectionsStrategyId.
	private final String reflectionsStrategyId;

	public EpisodicMemoryStrategyHandler(String strategyId, String namespacePattern, int episodesTopK,
			int reflectionsTopK, String reflectionsNamespacePattern, String reflectionsStrategyId) {
		this.strategyId = strategyId;
		this.namespacePattern = namespacePattern;
		this.episodesTopK = episodesTopK;
		this.reflectionsTopK = reflectionsTopK;
		this.reflectionsNamespacePattern = reflectionsNamespacePattern;
		this.reflectionsStrategyId = reflectionsStrategyId;
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
		List<MemoryRecord> episodes = ctx.retriever()
			.searchMemories(this.strategyId, ctx.userId(), ctx.sessionId(), ctx.userPrompt(), this.episodesTopK,
					this.namespacePattern);
		List<MemoryRecord> reflections = this.fetchReflections(ctx);
		return new MemoryFetchResult(episodes, reflections);
	}

	private List<MemoryRecord> fetchReflections(MemoryFetchContext ctx) {
		if (this.reflectionsNamespacePattern != null && !this.reflectionsNamespacePattern.isEmpty()) {
			return ctx.retriever()
				.searchMemories(this.strategyId, ctx.userId(), ctx.sessionId(), ctx.userPrompt(), this.reflectionsTopK,
						this.reflectionsNamespacePattern);
		}
		if (this.reflectionsStrategyId != null && !this.reflectionsStrategyId.isEmpty()) {
			return ctx.retriever()
				.searchMemories(this.reflectionsStrategyId, ctx.userId(), ctx.sessionId(), ctx.userPrompt(),
						this.reflectionsTopK, this.namespacePattern);
		}
		return List.of();
	}

	@Override
	public String format(MemoryFetchContext ctx, MemoryFetchResult fetched) {
		StringBuilder sb = new StringBuilder();
		if (!fetched.primary().isEmpty()) {
			sb.append(MemoryStrategyHandler.formatMemorySection("Relevant past interactions", fetched.primary()));
		}
		if (!fetched.secondary().isEmpty()) {
			if (!fetched.primary().isEmpty()) {
				sb.append("\n");
			}
			sb.append(MemoryStrategyHandler.formatMemorySection("Lessons learned", fetched.secondary()));
		}
		return sb.toString();
	}

	@Override
	public InjectionTarget target() {
		return InjectionTarget.SYSTEM;
	}

	public static final class Builder {

		private String strategyId;

		private String namespacePattern;

		private int episodesTopK = 3;

		private int reflectionsTopK = 2;

		private String reflectionsNamespacePattern;

		// Legacy: see field comment on the enclosing class.
		private String reflectionsStrategyId;

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

		public Builder episodesTopK(int episodesTopK) {
			this.episodesTopK = episodesTopK;
			return this;
		}

		public Builder reflectionsTopK(int reflectionsTopK) {
			this.reflectionsTopK = reflectionsTopK;
			return this;
		}

		/**
		 * Sets the namespace pattern used to look up reflections. Reflections share the
		 * same {@code strategyId} as episodes but live under a (typically less nested)
		 * namespace. Takes precedence over the deprecated
		 * {@link #reflectionsStrategyId(String)}.
		 */
		public Builder reflectionsNamespacePattern(String reflectionsNamespacePattern) {
			this.reflectionsNamespacePattern = reflectionsNamespacePattern;
			return this;
		}

		/**
		 * Legacy setter. In AWS AgentCore Memory, reflections are a namespace within the
		 * same episodic strategy, not a separate strategy. Use
		 * {@link #reflectionsNamespacePattern(String)} instead.
		 * @deprecated will be removed in a future release
		 */
		@Deprecated(forRemoval = true)
		public Builder reflectionsStrategyId(String reflectionsStrategyId) {
			this.reflectionsStrategyId = reflectionsStrategyId;
			return this;
		}

		public EpisodicMemoryStrategyHandler build() {
			if (this.strategyId == null || this.strategyId.isEmpty()) {
				throw new IllegalArgumentException("strategyId is required");
			}
			return new EpisodicMemoryStrategyHandler(this.strategyId, this.namespacePattern, this.episodesTopK,
					this.reflectionsTopK, this.reflectionsNamespacePattern, this.reflectionsStrategyId);
		}

	}

}
