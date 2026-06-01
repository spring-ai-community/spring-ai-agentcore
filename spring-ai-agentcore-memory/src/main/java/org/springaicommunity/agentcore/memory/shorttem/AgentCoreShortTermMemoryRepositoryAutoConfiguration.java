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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springaicommunity.agentcore.memory.AgentCoreMemoryConversationIdParser;
import org.springaicommunity.agentcore.memory.AgentCoreMemoryProperties;
import software.amazon.awssdk.services.bedrockagentcore.BedrockAgentCoreClient;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
@ConfigurationPropertiesScan
@EnableConfigurationProperties({ AgentCoreMemoryProperties.class, AgentCoreShortTermMemoryProperties.class })
public class AgentCoreShortTermMemoryRepositoryAutoConfiguration {

	private static final Logger logger = LoggerFactory
		.getLogger(AgentCoreShortTermMemoryRepositoryAutoConfiguration.class);

	private static final int DEFAULT_PAGE_SIZE = 100;

	@Bean(destroyMethod = "close")
	@ConditionalOnMissingBean
	@ConditionalOnProperty(prefix = AgentCoreMemoryProperties.CONFIG_PREFIX, name = "memory-id")
	BedrockAgentCoreClient bedrockAgentCoreClient() {
		logger.info("Creating BedrockAgentCoreClient bean");
		return BedrockAgentCoreClient.create();
	}

	@Bean
	@ConditionalOnMissingBean
	@ConditionalOnProperty(prefix = AgentCoreMemoryProperties.CONFIG_PREFIX, name = "memory-id")
	AgentCoreShortTermMemoryRepository memoryRepository(AgentCoreMemoryProperties memory,
			AgentCoreShortTermMemoryProperties shortTerm, BedrockAgentCoreClient client) {
		logger.info("Creating AgentCoreShortTermMemoryRepository bean with memoryId: {}", memory.memoryId());

		Integer totalEventsLimit = resolve("total-events-limit", shortTerm.totalEventsLimit(),
				memory.totalEventsLimit(), null);
		String defaultSession = resolve("default-session", shortTerm.defaultSession(), memory.defaultSession(),
				AgentCoreMemoryConversationIdParser.DEFAULT_SESSION);
		int pageSize = resolve("page-size", shortTerm.pageSize(), memory.pageSize(), DEFAULT_PAGE_SIZE);
		boolean ignoreUnknownRoles = resolve("ignore-unknown-roles", shortTerm.ignoreUnknownRoles(),
				memory.ignoreUnknownRoles(), Boolean.TRUE);
		warnIfIgnoreUnknownRolesExplicitlySet(shortTerm, memory);

		return new AgentCoreShortTermMemoryRepository(memory.memoryId(), client, totalEventsLimit, defaultSession,
				pageSize, ignoreUnknownRoles);
	}

	private static void warnIfIgnoreUnknownRolesExplicitlySet(AgentCoreShortTermMemoryProperties shortTerm,
			AgentCoreMemoryProperties memory) {
		if (shortTerm.ignoreUnknownRoles() == null && memory.ignoreUnknownRoles() == null) {
			return;
		}
		logger.warn("Property 'ignore-unknown-roles' is deprecated and will be removed in a future release. "
				+ "Skipping non-dialogue messages will become hardcoded behaviour; remove this property. See "
				+ "https://github.com/spring-ai-community/spring-ai-agentcore/issues/109");
	}

	private static <T> T resolve(String name, T modern, T legacy, T fallback) {
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

}
