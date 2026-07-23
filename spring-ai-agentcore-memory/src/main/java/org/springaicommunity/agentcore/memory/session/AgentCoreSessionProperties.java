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

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for the Spring AI Session API bean stack.
 *
 * <p>
 * <strong>User id convention.</strong> The AgentCore-backed
 * {@link AgentCoreSessionRepository} derives {@code Session.userId} from the actor
 * segment of the sessionId (parsed by
 * {@link org.springaicommunity.agentcore.memory.AgentCoreMemoryConversationIdParser}).
 * Callers using {@code SessionMemoryAdvisor.USER_ID_CONTEXT_KEY} MUST use the sessionId
 * format {@code "userId:sessionSuffix"} and pass the SAME user id under
 * {@code USER_ID_CONTEXT_KEY}. Any mismatch triggers an {@link IllegalStateException}
 * from the advisor on the second turn.
 *
 * @param enabled opt-in switch for the session-backed memory stack; defaults to
 * {@code false}
 * @param defaultUserId optional override for the {@code SessionMemoryAdvisor} built-in
 * default user id
 * @param persistSynthetic when {@code true}, synthetic session events (framework
 * generated, e.g. compaction summaries) are persisted to AgentCore; defaults to
 * {@code false}
 * @author Spring AI Community
 */
@ConfigurationProperties(AgentCoreSessionProperties.CONFIG_PREFIX)
public record AgentCoreSessionProperties(Boolean enabled, String defaultUserId, Boolean persistSynthetic) {

	/**
	 * Configuration prefix for the session-backed memory stack.
	 */
	public static final String CONFIG_PREFIX = "agentcore.memory.session";

}
