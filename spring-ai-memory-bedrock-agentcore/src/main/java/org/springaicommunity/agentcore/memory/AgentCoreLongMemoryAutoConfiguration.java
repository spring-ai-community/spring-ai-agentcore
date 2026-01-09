package org.springaicommunity.agentcore.memory;

import org.springaicommunity.agentcore.memory.AgentCoreLongMemoryAdvisor.Mode;
import org.springaicommunity.agentcore.memory.AgentCoreLongMemoryConfiguration.EpisodicConfig;
import org.springaicommunity.agentcore.memory.AgentCoreLongMemoryConfiguration.StrategyConfig;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import software.amazon.awssdk.services.bedrockagentcore.BedrockAgentCoreClient;

/**
 * Auto-configuration for AgentCore Long-Term Memory.
 *
 * @author Yuriy Bezsonov
 */
@AutoConfiguration(after = AgentCoreShortMemoryRepositoryAutoConfiguration.class)
@EnableConfigurationProperties(AgentCoreLongMemoryConfiguration.class)
@ConditionalOnBean(BedrockAgentCoreClient.class)
public class AgentCoreLongMemoryAutoConfiguration {

	@Bean
	@ConditionalOnMissingBean
	@ConditionalOnProperty(name = "agentcore.memory.long-term.semantic-facts.strategy-id")
	AgentCoreLongMemoryRepository agentCoreLongMemoryRepository(BedrockAgentCoreClient client,
			AgentCoreShortMemoryRepositoryConfiguration shortMemoryConfig) {
		return new AgentCoreLongMemoryRepository(client, shortMemoryConfig.memoryId());
	}

	@Bean
	@ConditionalOnProperty(name = "agentcore.memory.long-term.semantic-facts.strategy-id")
	AgentCoreLongMemoryAdvisor semanticFactsAdvisor(AgentCoreLongMemoryRepository repository,
			AgentCoreLongMemoryConfiguration config) {
		StrategyConfig strategyConfig = config.semanticFacts();
		return new AgentCoreLongMemoryAdvisor(repository, strategyConfig.strategyId(),
				"Known facts about the user (use naturally in conversation)", Mode.SEMANTIC, 100,
				strategyConfig.topK());
	}

	@Bean
	@ConditionalOnProperty(name = "agentcore.memory.long-term.user-preferences.strategy-id")
	AgentCoreLongMemoryAdvisor userPreferencesAdvisor(AgentCoreLongMemoryRepository repository,
			AgentCoreLongMemoryConfiguration config) {
		StrategyConfig strategyConfig = config.userPreferences();
		return new AgentCoreLongMemoryAdvisor(repository, strategyConfig.strategyId(),
				"User preferences (apply when relevant)", Mode.LIST, 101, strategyConfig.topK());
	}

	@Bean
	@ConditionalOnProperty(name = "agentcore.memory.long-term.summary.strategy-id")
	AgentCoreLongMemoryAdvisor summaryAdvisor(AgentCoreLongMemoryRepository repository,
			AgentCoreLongMemoryConfiguration config) {
		StrategyConfig strategyConfig = config.summary();
		return new AgentCoreLongMemoryAdvisor(repository, strategyConfig.strategyId(), "Conversation summaries",
				Mode.SUMMARY, 102, strategyConfig.topK());
	}

	@Bean
	@ConditionalOnProperty(name = "agentcore.memory.long-term.episodic.strategy-id")
	AgentCoreLongMemoryAdvisor episodicAdvisor(AgentCoreLongMemoryRepository repository,
			AgentCoreLongMemoryConfiguration config) {
		EpisodicConfig episodicConfig = config.episodic();
		return new AgentCoreLongMemoryAdvisor(repository, episodicConfig.strategyId(), "Episodic memory", Mode.EPISODIC,
				103, episodicConfig.episodesTopK(), episodicConfig.reflectionsTopK());
	}

}
