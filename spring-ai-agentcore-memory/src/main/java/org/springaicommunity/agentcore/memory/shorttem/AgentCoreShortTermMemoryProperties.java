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

package org.springaicommunity.agentcore.memory.shorttem;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for AgentCore short-term memory.
 *
 * <p>
 * Bound to the {@code agentcore.memory.short-term} prefix, mirroring the
 * {@code agentcore.memory.long-term} namespace used by long-term memory. Each field is
 * nullable so the auto-configuration can distinguish "not set" from an explicitly
 * configured zero/false value and apply defaults or fall back to legacy root-level
 * properties on {@code agentcore.memory.*}.
 *
 * @param totalEventsLimit maximum number of events to retrieve, or {@code null} for no
 * limit
 * @param defaultSession default session id used when none is supplied in the conversation
 * id, or {@code null} to fall back
 * @param pageSize page size for AgentCore {@code listEvents} pagination, or {@code null}
 * to fall back to a default
 * @param ignoreUnknownRoles whether to silently skip messages with unsupported roles, or
 * {@code null} to fall back
 * @author Yuriy Bezsonov
 */
@ConfigurationProperties(AgentCoreShortTermMemoryProperties.CONFIG_PREFIX)
public record AgentCoreShortTermMemoryProperties(Integer totalEventsLimit, String defaultSession, Integer pageSize,
		Boolean ignoreUnknownRoles) {

	/**
	 * configuration prefix for short-term memory properties.
	 */
	public static final String CONFIG_PREFIX = "agentcore.memory.short-term";

}
