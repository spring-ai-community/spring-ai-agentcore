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

package org.springaicommunity.agentcore.memory;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties shared across AgentCore memory features.
 *
 * <p>
 * The {@link #memoryId()} field is shared between short-term and long-term memory and
 * remains at the {@code agentcore.memory} prefix. The remaining fields are short-term
 * memory tunables retained at the root prefix only for backward compatibility; they have
 * been moved to
 * {@link org.springaicommunity.agentcore.memory.shorttem.AgentCoreShortTermMemoryProperties}
 * under the {@code agentcore.memory.short-term} prefix and will be removed from the root
 * prefix in a future release. See
 * <a href= "https://github.com/spring-ai-community/spring-ai-agentcore/issues/49">issue
 * #49</a>.
 *
 * @param memoryId AgentCore Memory resource id (shared between STM and LTM)
 * @param totalEventsLimit deprecated, see {@link #totalEventsLimit()}
 * @param defaultSession deprecated, see {@link #defaultSession()}
 * @param pageSize deprecated, see {@link #pageSize()}
 * @param ignoreUnknownRoles deprecated, see {@link #ignoreUnknownRoles()}
 * @author Yuriy Bezsonov
 */
@ConfigurationProperties(AgentCoreMemoryProperties.CONFIG_PREFIX)
public record AgentCoreMemoryProperties(String memoryId, Integer totalEventsLimit, String defaultSession,
		Integer pageSize, Boolean ignoreUnknownRoles) {

	/**
	 * configuration prefix for memory properties.
	 */
	public static final String CONFIG_PREFIX = "agentcore.memory";

	/**
	 * Returns the legacy short-term {@code total-events-limit} value, or {@code null} if
	 * unset.
	 * @return the legacy total events limit, or {@code null}
	 * @deprecated since 1.1.0, for removal. Use
	 * {@link org.springaicommunity.agentcore.memory.shorttem.AgentCoreShortTermMemoryProperties#totalEventsLimit()}
	 * bound to {@code agentcore.memory.short-term.total-events-limit}.
	 */
	@Deprecated(since = "1.1.0", forRemoval = true)
	@Override
	public Integer totalEventsLimit() {
		return this.totalEventsLimit;
	}

	/**
	 * Returns the legacy short-term {@code default-session} value, or {@code null} if
	 * unset.
	 * @return the legacy default session, or {@code null}
	 * @deprecated since 1.1.0, for removal. Use
	 * {@link org.springaicommunity.agentcore.memory.shorttem.AgentCoreShortTermMemoryProperties#defaultSession()}
	 * bound to {@code agentcore.memory.short-term.default-session}.
	 */
	@Deprecated(since = "1.1.0", forRemoval = true)
	@Override
	public String defaultSession() {
		return this.defaultSession;
	}

	/**
	 * Returns the legacy short-term {@code page-size} value, or {@code null} if unset.
	 * @return the legacy page size, or {@code null}
	 * @deprecated since 1.1.0, for removal. Use
	 * {@link org.springaicommunity.agentcore.memory.shorttem.AgentCoreShortTermMemoryProperties#pageSize()}
	 * bound to {@code agentcore.memory.short-term.page-size}.
	 */
	@Deprecated(since = "1.1.0", forRemoval = true)
	@Override
	public Integer pageSize() {
		return this.pageSize;
	}

	/**
	 * Returns the legacy short-term {@code ignore-unknown-roles} value, or {@code null}
	 * if unset.
	 * @return the legacy ignore-unknown-roles flag, or {@code null}
	 * @deprecated since 1.1.0, for removal. Use
	 * {@link org.springaicommunity.agentcore.memory.shorttem.AgentCoreShortTermMemoryProperties#ignoreUnknownRoles()}
	 * bound to {@code agentcore.memory.short-term.ignore-unknown-roles}.
	 */
	@Deprecated(since = "1.1.0", forRemoval = true)
	@Override
	public Boolean ignoreUnknownRoles() {
		return this.ignoreUnknownRoles;
	}

}
