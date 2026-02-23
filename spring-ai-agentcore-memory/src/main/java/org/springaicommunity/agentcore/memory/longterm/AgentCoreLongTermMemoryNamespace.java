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

/**
 * Predefined namespace patterns for long-term memory strategies.
 *
 * <p>
 * ACTOR namespace searches across all sessions for a user - richer context but slower.
 * SESSION namespace searches only the current session - faster but limited context.
 *
 * <p>
 * Custom namespace patterns can be configured per strategy via
 * {@code agentcore.memory.long-term.<strategy>.namespace-pattern}.
 *
 * @author Yuriy Bezsonov
 */
public enum AgentCoreLongTermMemoryNamespace {

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

	AgentCoreLongTermMemoryNamespace(String pattern) {
		this.pattern = pattern;
	}

	public String getPattern() {
		return this.pattern;
	}

	/**
	 * Builds the resolved namespace by replacing template variables with actual values.
	 * @param pattern the namespace pattern (use custom or {@link #getPattern()})
	 * @param strategyId the memory strategy ID
	 * @param actorId the actor ID
	 * @param sessionId the session ID (optional, only used if pattern contains
	 * {sessionId})
	 * @return the resolved namespace string
	 * @throws IllegalArgumentException if required variables are missing
	 */
	public static String buildNamespace(String pattern, String strategyId, String actorId, String sessionId) {
		if (pattern == null || pattern.isEmpty()) {
			throw new IllegalArgumentException("namespace pattern is required");
		}
		if (strategyId == null || strategyId.isEmpty()) {
			throw new IllegalArgumentException("strategyId is required");
		}
		if (actorId == null || actorId.isEmpty()) {
			throw new IllegalArgumentException("actorId is required");
		}

		String namespace = pattern.replace("{memoryStrategyId}", strategyId).replace("{actorId}", actorId);

		if (namespace.contains("{sessionId}")) {
			if (sessionId == null || sessionId.isEmpty()) {
				throw new IllegalArgumentException("sessionId is required for namespace pattern: " + pattern);
			}
			namespace = namespace.replace("{sessionId}", sessionId);
		}

		return namespace;
	}

}
