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
 * Parses conversationId into actor and session components. Supports two formats:
 * <ul>
 * <li>{@code "actorId"} → actor: actorId, session: default</li>
 * <li>{@code "actorId:sessionId"} → actor: actorId, session: sessionId</li>
 * </ul>
 *
 * @author Yuriy Bezsonov
 */
public final class AgentCoreMemoryConversationIdParser {

	/**
	 * Default session ID used when conversationId doesn't include a session.
	 */
	public static final String DEFAULT_SESSION = "default-session";

	private AgentCoreMemoryConversationIdParser() {
	}

	/**
	 * Parse conversationId using the default session fallback.
	 * @param conversationId the conversation ID (format: "actorId" or
	 * "actorId:sessionId")
	 * @return parsed actor and session
	 * @throws IllegalArgumentException if conversationId is null or empty
	 */
	public static ActorAndSession parse(String conversationId) {
		return parse(conversationId, DEFAULT_SESSION);
	}

	/**
	 * Parse conversationId with a custom session fallback.
	 * @param conversationId the conversation ID (format: "actorId" or
	 * "actorId:sessionId")
	 * @param defaultSession the session to use when not specified in conversationId
	 * @return parsed actor and session
	 * @throws IllegalArgumentException if conversationId is null or empty
	 */
	public static ActorAndSession parse(String conversationId, String defaultSession) {
		if (conversationId == null || conversationId.isEmpty()) {
			throw new IllegalArgumentException("conversationId is required (format: 'actorId' or 'actorId:sessionId')");
		}

		if (conversationId.contains(":")) {
			String[] parts = conversationId.split(":", 2);
			return new ActorAndSession(parts[0], parts[1]);
		}
		return new ActorAndSession(conversationId, defaultSession != null ? defaultSession : DEFAULT_SESSION);
	}

	/**
	 * Represents parsed actor and session from a conversationId.
	 */
	public record ActorAndSession(String actor, String session) {
	}

}
