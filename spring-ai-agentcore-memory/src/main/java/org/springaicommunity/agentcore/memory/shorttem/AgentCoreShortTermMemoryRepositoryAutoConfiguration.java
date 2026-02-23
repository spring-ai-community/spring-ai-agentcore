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

package org.springaicommunity.agentcore.memory.shorttem;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springaicommunity.agentcore.memory.AgentCoreMemoryProperties;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import software.amazon.awssdk.services.bedrockagentcore.BedrockAgentCoreClient;

@AutoConfiguration
@ConfigurationPropertiesScan
@EnableConfigurationProperties(AgentCoreMemoryProperties.class)
public class AgentCoreShortTermMemoryRepositoryAutoConfiguration {

	private static final Logger logger = LoggerFactory
		.getLogger(AgentCoreShortTermMemoryRepositoryAutoConfiguration.class);

	@Bean
	@ConditionalOnMissingBean
	@ConditionalOnProperty(prefix = AgentCoreMemoryProperties.CONFIG_PREFIX, name = "memory-id")
	BedrockAgentCoreClient bedrockAgentCoreClient() {
		logger.info("Creating BedrockAgentCoreClient bean");
		return BedrockAgentCoreClient.create();
	}

	@Bean
	@ConditionalOnMissingBean
	@ConditionalOnProperty(prefix = AgentCoreMemoryProperties.CONFIG_PREFIX, name = "memory-id")
	AgentCoreShortTermMemoryRepository memoryRepository(AgentCoreMemoryProperties configuration,
			BedrockAgentCoreClient client) {
		logger.info("Creating AgentCoreShortTermMemoryRepository bean with memoryId: {}", configuration.memoryId());
		return new AgentCoreShortTermMemoryRepository(configuration.memoryId(), client,
				configuration.totalEventsLimit(), configuration.defaultSession(), configuration.pageSize(),
				configuration.ignoreUnknownRoles());
	}

}
