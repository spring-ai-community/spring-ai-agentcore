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

package org.springaicommunity.agentcore.memory.longterm.strategy;

/**
 * User preference strategy. Lists all preferences under the user's namespace without a
 * semantic query, so it does <em>not</em> require a user prompt to run.
 *
 * @author Maximilian Schellhorn
 */
public final class UserPreferenceMemoryStrategyHandler implements MemoryStrategyHandler {

	private final String strategyId;

	private final String namespacePattern;

	private final String contextLabel;

	public UserPreferenceMemoryStrategyHandler(String strategyId, String namespacePattern, String contextLabel) {
		this.strategyId = strategyId;
		this.namespacePattern = namespacePattern;
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
	public boolean requiresUserPrompt() {
		return false;
	}

	@Override
	public MemoryFetchResult fetch(MemoryFetchContext ctx) {
		return MemoryFetchResult
			.primaryOnly(ctx.retriever().listMemories(this.strategyId, ctx.userId(), this.namespacePattern));
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

		public Builder contextLabel(String contextLabel) {
			this.contextLabel = contextLabel;
			return this;
		}

		public UserPreferenceMemoryStrategyHandler build() {
			if (this.strategyId == null || this.strategyId.isEmpty()) {
				throw new IllegalArgumentException("strategyId is required");
			}
			return new UserPreferenceMemoryStrategyHandler(this.strategyId, this.namespacePattern, this.contextLabel);
		}

	}

}
