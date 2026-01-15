package org.springaicommunity.agentcore.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import software.amazon.awssdk.services.bedrockagentcore.BedrockAgentCoreClient;

@AutoConfiguration
@EnableConfigurationProperties(AgentCoreShortMemoryRepositoryConfiguration.class)
@ConditionalOnProperty(name = "agentcore.memory.memory-id")
public class AgentCoreShortMemoryRepositoryAutoConfiguration {

	private static final Logger logger = LoggerFactory.getLogger(AgentCoreShortMemoryRepositoryAutoConfiguration.class);

	@Bean
	@ConditionalOnMissingBean
	BedrockAgentCoreClient bedrockAgentCoreClient() {
		logger.info("Creating BedrockAgentCoreClient bean");
		return BedrockAgentCoreClient.create();
	}

	@Bean
	@ConditionalOnMissingBean
	AgentCoreShortMemoryRepository memoryRepository(AgentCoreShortMemoryRepositoryConfiguration configuration,
			BedrockAgentCoreClient client) {
		logger.info("Creating AgentCoreShortMemoryRepository bean with memoryId: {}", configuration.memoryId());
		return new AgentCoreShortMemoryRepository(configuration.memoryId(), client, configuration.totalEventsLimit(),
				configuration.defaultSession(), configuration.pageSize(), configuration.ignoreUnknownRoles());
	}

}
