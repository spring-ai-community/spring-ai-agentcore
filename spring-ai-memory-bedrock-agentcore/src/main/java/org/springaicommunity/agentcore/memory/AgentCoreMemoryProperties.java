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

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(AgentCoreMemoryProperties.CONFIG_PREFIX)
public record AgentCoreMemoryProperties(String memoryId, Integer totalEventsLimit, String defaultSession, int pageSize,
		boolean ignoreUnknownRoles) {

	public static final String CONFIG_PREFIX = "agentcore.memory";

	public AgentCoreMemoryProperties(String memoryId, Integer totalEventsLimit, String defaultSession, int pageSize,
			boolean ignoreUnknownRoles) {
		this.memoryId = memoryId;
		this.totalEventsLimit = totalEventsLimit;
		this.defaultSession = defaultSession != null ? defaultSession
				: AgentCoreMemoryConversationIdParser.DEFAULT_SESSION;
		this.pageSize = pageSize > 0 ? pageSize : 100;
		this.ignoreUnknownRoles = ignoreUnknownRoles;
	}

}
