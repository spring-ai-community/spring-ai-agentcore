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
import org.springaicommunity.agentcore.memory.AgentCoreMemoryConversationIdParser;
import org.springaicommunity.agentcore.memory.AgentCoreMemoryProperties;
import software.amazon.awssdk.services.bedrockagentcore.BedrockAgentCoreClient;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
@EnableConfigurationProperties({ AgentCoreMemoryProperties.class, AgentCoreShortTermMemoryProperties.class })
public class AgentCoreShortTermMemoryRepositoryAutoConfiguration {

	private static final Logger logger = LoggerFactory
		.getLogger(AgentCoreShortTermMemoryRepositoryAutoConfiguration.class);

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

		Integer totalEventsLimit = ShortTermPropertyResolver.resolve("total-events-limit", shortTerm.totalEventsLimit(),
				memory.totalEventsLimit(), null);
		String defaultSession = ShortTermPropertyResolver.resolve("default-session", shortTerm.defaultSession(),
				memory.defaultSession(), AgentCoreMemoryConversationIdParser.DEFAULT_SESSION);
		int pageSize = ShortTermPropertyResolver.resolve("page-size", shortTerm.pageSize(), memory.pageSize(),
				ShortTermPropertyResolver.DEFAULT_PAGE_SIZE);
		boolean ignoreUnknownRoles = ShortTermPropertyResolver.resolve("ignore-unknown-roles",
				shortTerm.ignoreUnknownRoles(), memory.ignoreUnknownRoles(), Boolean.TRUE);
		ShortTermPropertyResolver.warnIfIgnoreUnknownRolesExplicitlySet(shortTerm, memory);

		return new AgentCoreShortTermMemoryRepository(memory.memoryId(), client, totalEventsLimit, defaultSession,
				pageSize, ignoreUnknownRoles);
	}

}
