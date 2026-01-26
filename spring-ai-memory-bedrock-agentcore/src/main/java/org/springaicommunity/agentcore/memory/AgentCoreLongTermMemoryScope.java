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

package org.springaicommunity.agentcore.memory;

/**
 * Namespace scope determines the search scope for long-term memory strategies.
 *
 * <p>
 * ACTOR scope searches across all sessions for a user - richer context but slower.
 * SESSION scope searches only the current session - faster but limited context.
 *
 * @author Yuriy Bezsonov
 */
public enum AgentCoreLongTermMemoryScope {

	/**
	 * Actor-scoped namespace: /strategies/{memoryStrategyId}/actors/{actorId}. Searches
	 * across all sessions for the user.
	 */
	ACTOR("/strategies/{memoryStrategyId}/actors/{actorId}"),

	/**
	 * Session-scoped namespace:
	 * /strategies/{memoryStrategyId}/actors/{actorId}/sessions/{sessionId}. Searches only
	 * the current session.
	 */
	SESSION("/strategies/{memoryStrategyId}/actors/{actorId}/sessions/{sessionId}");

	private final String pattern;

	AgentCoreLongTermMemoryScope(String pattern) {
		this.pattern = pattern;
	}

	public String getPattern() {
		return this.pattern;
	}

	/**
	 * Builds the resolved namespace by replacing template variables with actual values.
	 * @param strategyId the memory strategy ID
	 * @param actorId the actor ID
	 * @return the resolved namespace string
	 * @throws IllegalArgumentException if strategyId or actorId is null/empty
	 */
	public String buildNamespace(String strategyId, String actorId) {
		if (strategyId == null || strategyId.isEmpty()) {
			throw new IllegalArgumentException("strategyId is required");
		}
		if (actorId == null || actorId.isEmpty()) {
			throw new IllegalArgumentException("actorId is required");
		}
		return this.pattern.replace("{memoryStrategyId}", strategyId).replace("{actorId}", actorId);
	}

	/**
	 * Builds the resolved namespace including session ID.
	 * @param strategyId the memory strategy ID
	 * @param actorId the actor ID
	 * @param sessionId the session ID (required for SESSION scope, ignored for ACTOR)
	 * @return the resolved namespace string
	 * @throws IllegalArgumentException if SESSION scope and sessionId is null/empty
	 */
	public String buildNamespace(String strategyId, String actorId, String sessionId) {
		String namespace = buildNamespace(strategyId, actorId);
		if (this == SESSION) {
			if (sessionId == null || sessionId.isEmpty()) {
				throw new IllegalArgumentException("sessionId is required for SESSION scope");
			}
			namespace = namespace.replace("{sessionId}", sessionId);
		}
		return namespace;
	}

}
