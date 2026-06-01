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
 * @param ignoreUnknownRoles deprecated, see {@link #ignoreUnknownRoles()}
 * @author Yuriy Bezsonov
 */
@ConfigurationProperties(AgentCoreShortTermMemoryProperties.CONFIG_PREFIX)
public record AgentCoreShortTermMemoryProperties(Integer totalEventsLimit, String defaultSession, Integer pageSize,
		Boolean ignoreUnknownRoles) {

	/**
	 * configuration prefix for short-term memory properties.
	 */
	public static final String CONFIG_PREFIX = "agentcore.memory.short-term";

	/**
	 * Returns the {@code ignore-unknown-roles} flag, or {@code null} if unset.
	 *
	 * <p>
	 * The property is misnamed: AgentCore's {@code TOOL} and {@code OTHER} are
	 * first-class {@code Role} values, not "unknown" roles — these are simply
	 * non-dialogue messages that should never be persisted into conversation memory (they
	 * are point-in-time facts, add token noise, and break {@code tool_call}↔ response
	 * pairing under windowing). The throw-mode it gates ({@code false}) has no production
	 * use — Spring AI 2.0.0-M7+ tool-using turns crash with
	 * {@code IllegalStateException}. The runtime {@code WARN} log when a non-dialogue
	 * message is skipped already provides visibility without a property.
	 * @return the flag value, or {@code null}
	 * @deprecated since 1.1.0, for removal. Setting this property has no recommended
	 * value: skipping non-dialogue messages will become hardcoded behaviour. See <a href=
	 * "https://github.com/spring-ai-community/spring-ai-agentcore/issues/109">issue
	 * #109</a>.
	 */
	@Deprecated(since = "1.1.0", forRemoval = true)
	@Override
	public Boolean ignoreUnknownRoles() {
		return this.ignoreUnknownRoles;
	}

}
