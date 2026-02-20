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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springaicommunity.agentcore.memory.AgentCoreLongTermMemoryAdvisor.MemoryStrategy;
import org.springaicommunity.agentcore.memory.AgentCoreLongTermMemoryStrategyDiscovery.DiscoveredStrategy;
import org.springaicommunity.agentcore.memory.AgentCoreLongTermMemoryStrategyDiscovery.StrategyType;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.AnyNestedCondition;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;

import software.amazon.awssdk.services.bedrockagentcore.BedrockAgentCoreClient;
import software.amazon.awssdk.services.bedrockagentcorecontrol.BedrockAgentCoreControlClient;

/**
 * Auto-configuration for AgentCore Long-Term Memory.
 *
 * @author Yuriy Bezsonov
 */
@AutoConfiguration(after = AgentCoreShortTermMemoryRepositoryAutoConfiguration.class)
@ConditionalOnBean({ BedrockAgentCoreClient.class, AgentCoreMemoryProperties.class })
@EnableConfigurationProperties(AgentCoreLongTermMemoryProperties.class)
public class AgentCoreLongTermMemoryAutoConfiguration {

	private static final Logger logger = LoggerFactory.getLogger(AgentCoreLongTermMemoryAutoConfiguration.class);

	static class AnyStrategyConfiguredCondition extends AnyNestedCondition {

		public AnyStrategyConfiguredCondition() {
			super(ConfigurationPhase.REGISTER_BEAN);
		}

		@ConditionalOnProperty(prefix = AgentCoreLongTermMemoryProperties.CONFIG_PREFIX, name = "auto-discovery",
				havingValue = "true")
		static class AutoDiscoveryCondition {

		}

		@ConditionalOnProperty(prefix = AgentCoreLongTermMemoryProperties.Semantic.CONFIG_PREFIX, name = "strategy-id")
		static class SemanticCondition {

		}

		@ConditionalOnProperty(prefix = AgentCoreLongTermMemoryProperties.UserPreference.CONFIG_PREFIX,
				name = "strategy-id")
		static class UserPreferenceCondition {

		}

		@ConditionalOnProperty(prefix = AgentCoreLongTermMemoryProperties.Summary.CONFIG_PREFIX, name = "strategy-id")
		static class SummaryCondition {

		}

		@ConditionalOnProperty(prefix = AgentCoreLongTermMemoryProperties.Episodic.CONFIG_PREFIX, name = "strategy-id")
		static class EpisodicCondition {

		}

	}

	@Bean
	@ConditionalOnMissingBean
	@Conditional(AnyStrategyConfiguredCondition.class)
	Supplier<BedrockAgentCoreControlClient> controlClientFactory() {
		return BedrockAgentCoreControlClient::create;
	}

	@Bean
	@ConditionalOnMissingBean
	@Conditional(AnyStrategyConfiguredCondition.class)
	ChatMemoryRepository chatMemoryRepository(AgentCoreShortTermMemoryRepository shortTermMemoryRepository) {
		return shortTermMemoryRepository;
	}

	@Bean
	@ConditionalOnMissingBean
	@Conditional(AnyStrategyConfiguredCondition.class)
	ChatMemory chatMemory(AgentCoreShortTermMemoryRepository shortTermMemoryRepository) {
		return MessageWindowChatMemory.builder().chatMemoryRepository(shortTermMemoryRepository).build();
	}

	@Bean
	@ConditionalOnMissingBean
	@Conditional(AnyStrategyConfiguredCondition.class)
	AgentCoreMemory agentCoreLongTermMemory(List<AgentCoreLongTermMemoryAdvisor> ltmAdvisors, ChatMemory chatMemory) {
		var stmAdvisor = MessageChatMemoryAdvisor.builder(chatMemory).build();
		return new AgentCoreMemory(stmAdvisor, ltmAdvisors);
	}

	@Bean
	@ConditionalOnMissingBean
	@Conditional(AnyStrategyConfiguredCondition.class)
	AgentCoreLongTermMemoryRetriever agentCoreLongTermMemoryRetriever(BedrockAgentCoreClient client,
			AgentCoreMemoryProperties memoryConfig, AgentCoreLongTermMemoryProperties longTermMemoryProperties,
			Supplier<BedrockAgentCoreControlClient> controlClientFactory) {
		String memoryId = memoryConfig.memoryId();

		if (!longTermMemoryProperties.autoDiscovery()) {
			Map<String, String> strategyConfigs = buildStrategyConfigs(longTermMemoryProperties);
			if (!strategyConfigs.isEmpty()) {
				try (BedrockAgentCoreControlClient controlClient = controlClientFactory.get()) {
					AgentCoreLongTermMemoryNamespaceRegistrar registrar = new AgentCoreLongTermMemoryNamespaceRegistrar(
							controlClient);
					AgentCoreLongTermMemoryNamespaceValidator validator = new AgentCoreLongTermMemoryNamespaceValidator(
							controlClient, registrar, longTermMemoryProperties.namespace().autoRegister());
					validator.validateNamespaces(memoryId, strategyConfigs);
				}
			}
		}

		return new AgentCoreLongTermMemoryRetriever(client, memoryId);
	}

	// ==================== Autodiscovery Mode ====================

	@Bean
	@ConditionalOnProperty(prefix = AgentCoreLongTermMemoryProperties.CONFIG_PREFIX, name = "auto-discovery",
			havingValue = "true")
	List<AgentCoreLongTermMemoryAdvisor> autoDiscoveredAdvisors(AgentCoreLongTermMemoryRetriever retriever,
			AgentCoreMemoryProperties memoryConfig, AgentCoreLongTermMemoryProperties config,
			Supplier<BedrockAgentCoreControlClient> controlClientFactory) {

		String memoryId = memoryConfig.memoryId();

		List<DiscoveredStrategy> discovered;
		try (BedrockAgentCoreControlClient controlClient = controlClientFactory.get()) {
			var discovery = new AgentCoreLongTermMemoryStrategyDiscovery(controlClient);
			discovered = discovery.discoverStrategies(memoryId);
		}

		if (discovered.isEmpty()) {
			logger.warn("Autodiscovery enabled but no strategies found in memory: {}", memoryId);
			return List.of();
		}

		try (BedrockAgentCoreControlClient controlClient = controlClientFactory.get()) {
			AgentCoreLongTermMemoryNamespaceRegistrar registrar = config.namespace().autoRegister()
					? new AgentCoreLongTermMemoryNamespaceRegistrar(controlClient) : null;

			var factory = new AgentCoreLongTermMemoryAutoDiscoveryAdvisorFactory(retriever, config, memoryId,
					registrar);
			return factory.createAdvisors(discovered);
		}
	}

	// ==================== Explicit Configuration Mode ====================

	@Bean
	@ConditionalOnMissingBean(name = "semanticAdvisor")
	@ConditionalOnProperty(prefix = AgentCoreLongTermMemoryProperties.Semantic.CONFIG_PREFIX, name = "strategy-id")
	@ConditionalOnProperty(prefix = AgentCoreLongTermMemoryProperties.CONFIG_PREFIX, name = "auto-discovery",
			havingValue = "false", matchIfMissing = true)
	AgentCoreLongTermMemoryAdvisor semanticAdvisor(AgentCoreLongTermMemoryRetriever retriever,
			AgentCoreLongTermMemoryProperties config) {
		var semanticConfig = config.semantic();
		return AgentCoreLongTermMemoryAdvisor.builder(retriever, MemoryStrategy.SEMANTIC)
			.strategyId(semanticConfig.strategyId())
			.contextLabel(MemoryStrategiesMap.getContextLabel(StrategyType.SEMANTIC))
			.topK(semanticConfig.topK())
			.namespacePattern(semanticConfig.resolveNamespacePattern())
			.build();
	}

	@Bean
	@ConditionalOnMissingBean(name = "userPreferenceAdvisor")
	@ConditionalOnProperty(prefix = AgentCoreLongTermMemoryProperties.UserPreference.CONFIG_PREFIX,
			name = "strategy-id")
	@ConditionalOnProperty(prefix = AgentCoreLongTermMemoryProperties.CONFIG_PREFIX, name = "auto-discovery",
			havingValue = "false", matchIfMissing = true)
	AgentCoreLongTermMemoryAdvisor userPreferenceAdvisor(AgentCoreLongTermMemoryRetriever retriever,
			AgentCoreLongTermMemoryProperties config) {
		var prefConfig = config.userPreference();
		return AgentCoreLongTermMemoryAdvisor.builder(retriever, MemoryStrategy.USER_PREFERENCE)
			.strategyId(prefConfig.strategyId())
			.contextLabel(MemoryStrategiesMap.getContextLabel(StrategyType.USER_PREFERENCE))
			.namespacePattern(prefConfig.resolveNamespacePattern())
			.build();
	}

	@Bean
	@ConditionalOnMissingBean(name = "summaryAdvisor")
	@ConditionalOnProperty(prefix = AgentCoreLongTermMemoryProperties.Summary.CONFIG_PREFIX, name = "strategy-id")
	@ConditionalOnProperty(prefix = AgentCoreLongTermMemoryProperties.CONFIG_PREFIX, name = "auto-discovery",
			havingValue = "false", matchIfMissing = true)
	AgentCoreLongTermMemoryAdvisor summaryAdvisor(AgentCoreLongTermMemoryRetriever retriever,
			AgentCoreLongTermMemoryProperties config) {
		var summaryConfig = config.summary();
		return AgentCoreLongTermMemoryAdvisor.builder(retriever, MemoryStrategy.SUMMARY)
			.strategyId(summaryConfig.strategyId())
			.contextLabel(MemoryStrategiesMap.getContextLabel(StrategyType.SUMMARY))
			.topK(summaryConfig.topK())
			.namespacePattern(summaryConfig.resolveNamespacePattern())
			.build();
	}

	@Bean
	@ConditionalOnMissingBean(name = "episodicAdvisor")
	@ConditionalOnProperty(prefix = AgentCoreLongTermMemoryProperties.Episodic.CONFIG_PREFIX, name = "strategy-id")
	@ConditionalOnProperty(prefix = AgentCoreLongTermMemoryProperties.CONFIG_PREFIX, name = "auto-discovery",
			havingValue = "false", matchIfMissing = true)
	AgentCoreLongTermMemoryAdvisor episodicAdvisor(AgentCoreLongTermMemoryRetriever retriever,
			AgentCoreLongTermMemoryProperties config) {
		var episodicConfig = config.episodic();
		return AgentCoreLongTermMemoryAdvisor.builder(retriever, MemoryStrategy.EPISODIC)
			.strategyId(episodicConfig.strategyId())
			.reflectionsStrategyId(episodicConfig.reflectionsStrategyId())
			.contextLabel(MemoryStrategiesMap.getContextLabel(StrategyType.EPISODIC))
			.topK(episodicConfig.episodesTopK())
			.reflectionsTopK(episodicConfig.reflectionsTopK())
			.namespacePattern(episodicConfig.resolveNamespacePattern())
			.build();
	}

	// ==================== Helper Methods ====================

	private Map<String, String> buildStrategyConfigs(AgentCoreLongTermMemoryProperties config) {
		Map<String, String> configs = new HashMap<>();
		if (config.semantic() != null && config.semantic().strategyId() != null) {
			configs.put(config.semantic().strategyId(), config.semantic().resolveNamespacePattern());
		}
		if (config.userPreference() != null && config.userPreference().strategyId() != null) {
			configs.put(config.userPreference().strategyId(), config.userPreference().resolveNamespacePattern());
		}
		if (config.summary() != null && config.summary().strategyId() != null) {
			configs.put(config.summary().strategyId(), config.summary().resolveNamespacePattern());
		}
		if (config.episodic() != null && config.episodic().strategyId() != null) {
			configs.put(config.episodic().strategyId(), config.episodic().resolveNamespacePattern());
			if (config.episodic().hasReflections()) {
				configs.put(config.episodic().reflectionsStrategyId(), config.episodic().resolveNamespacePattern());
			}
		}
		return configs;
	}

}
