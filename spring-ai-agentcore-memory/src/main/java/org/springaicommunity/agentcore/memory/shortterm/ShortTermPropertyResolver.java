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

package org.springaicommunity.agentcore.memory.shortterm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springaicommunity.agentcore.memory.AgentCoreMemoryProperties;

/**
 * Internal helper resolving short-term memory tunables using the modern
 * {@code agentcore.memory.short-term.*} namespace first and falling back to the legacy
 * {@code agentcore.memory.*} namespace with a deprecation warning. Shared between the
 * short-term memory auto-config and the session-backed session auto-config so both stacks
 * honor the same property precedence and log the same deprecation messages.
 *
 * <p>
 * The class deliberately keeps its {@code resolve} helper and deprecation-warn wording
 * identical to the historical inlined implementation in
 * {@link AgentCoreShortTermMemoryRepositoryAutoConfiguration} so that existing
 * {@code CapturedOutput} assertions on the deprecation messages remain unchanged.
 *
 * <p>
 * Public only so the sibling {@code session} sub-package auto-config can call it. This is
 * internal API; do not treat it as a stable extension point.
 *
 * <p>
 * See <a href="https://github.com/spring-ai-community/spring-ai-agentcore/issues/49">
 * issue #49</a> and
 * <a href="https://github.com/spring-ai-community/spring-ai-agentcore/issues/109"> issue
 * #109</a>.
 *
 * @author Spring AI Community
 */
public final class ShortTermPropertyResolver {

	/** Default page size for AgentCore {@code listEvents} pagination. */
	public static final int DEFAULT_PAGE_SIZE = 100;

	private static final Logger logger = LoggerFactory.getLogger(ShortTermPropertyResolver.class);

	private ShortTermPropertyResolver() {
	}

	/**
	 * Resolve a short-term property preferring the modern namespace, falling back to the
	 * legacy namespace with a deprecation warning, and finally to the supplied fallback.
	 * @param name the short property key (e.g. {@code "page-size"}) used only for log
	 * messages
	 * @param modern value bound under {@code agentcore.memory.short-term.<name>}
	 * @param legacy value bound under {@code agentcore.memory.<name>}
	 * @param fallback the value used when neither is set
	 * @param <T> property type
	 * @return the resolved value
	 */
	public static <T> T resolve(String name, T modern, T legacy, T fallback) {
		if (modern != null) {
			return modern;
		}
		if (legacy != null) {
			logger.warn("Property 'agentcore.memory.{}' is deprecated and will be removed in a future release. "
					+ "Use 'agentcore.memory.short-term.{}' instead. See "
					+ "https://github.com/spring-ai-community/spring-ai-agentcore/issues/49", name, name);
			return legacy;
		}
		return fallback;
	}

	/**
	 * Emit the {@code ignore-unknown-roles} deprecation warning if either namespace sets
	 * the property (issue #109). Idempotent when both are {@code null}.
	 * @param shortTerm the modern short-term properties record
	 * @param memory the legacy memory properties record
	 */
	public static void warnIfIgnoreUnknownRolesExplicitlySet(AgentCoreShortTermMemoryProperties shortTerm,
			AgentCoreMemoryProperties memory) {
		if (shortTerm.ignoreUnknownRoles() == null && memory.ignoreUnknownRoles() == null) {
			return;
		}
		logger.warn("Property 'ignore-unknown-roles' is deprecated and will be removed in a future release. "
				+ "Skipping non-dialogue messages will become hardcoded behaviour; remove this property. See "
				+ "https://github.com/spring-ai-community/spring-ai-agentcore/issues/109");
	}

}
