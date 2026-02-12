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

import org.springaicommunity.agentcore.memory.AgentCoreLongTermMemoryAdvisor.MemoryStrategy;
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

	// This is used for the construction of the AgentCoreLongTermMemoryRetriever and the
	// actual AgentCoreLongTermMemoryAdvisor's depend on that.
	// So we have to use an aggregate config
	// Also, there doesn't seem to be a way to just check for agentcore.memory.long-term
	// (any child)
	static class AnyStrategyConfiguredCondition extends AnyNestedCondition {

		public AnyStrategyConfiguredCondition() {
			super(ConfigurationPhase.REGISTER_BEAN);
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

	/**
	 * Factory for creating ControlClient instances. Allows tests to provide a mock
	 * factory while production uses the default SDK client creation.
	 */
	@Bean
	@ConditionalOnMissingBean
	@Conditional(AnyStrategyConfiguredCondition.class)
	Supplier<BedrockAgentCoreControlClient> controlClientFactory() {
		return BedrockAgentCoreControlClient::create;
	}

	// when long-term memory is configured, create the ChatMemoryRepository and ChatMemory
	// beans

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
		return MessageWindowChatMemory.builder()
			.chatMemoryRepository(shortTermMemoryRepository)
			// todo: make this configurable?
			// .maxMessages(10)
			.build();
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

		// Validate namespaces at startup to fail fast on misconfiguration
		// ControlClient is only needed for validation, so create/use/close inline
		Map<String, String> strategyConfigs = buildStrategyConfigs(longTermMemoryProperties);
		if (!strategyConfigs.isEmpty()) {
			try (BedrockAgentCoreControlClient controlClient = controlClientFactory.get()) {
				AgentCoreLongTermMemoryNamespaceValidator validator = new AgentCoreLongTermMemoryNamespaceValidator(
						controlClient);
				validator.validateNamespaces(memoryId, strategyConfigs);
			}
		}

		return new AgentCoreLongTermMemoryRetriever(client, memoryId);
	}

	private Map<String, String> buildStrategyConfigs(AgentCoreLongTermMemoryProperties config) {
		Map<String, String> configs = new HashMap<>();
		if (config.semantic() != null) {
			configs.put(config.semantic().strategyId(), config.semantic().resolveNamespacePattern());
		}
		if (config.userPreference() != null) {
			configs.put(config.userPreference().strategyId(), config.userPreference().resolveNamespacePattern());
		}
		if (config.summary() != null) {
			configs.put(config.summary().strategyId(), config.summary().resolveNamespacePattern());
		}
		if (config.episodic() != null) {
			configs.put(config.episodic().strategyId(), config.episodic().resolveNamespacePattern());
			if (config.episodic().hasReflections()) {
				configs.put(config.episodic().reflectionsStrategyId(), config.episodic().resolveNamespacePattern());
			}
		}
		return configs;
	}

	@Bean
	@ConditionalOnMissingBean(name = "semanticAdvisor")
	@ConditionalOnProperty(prefix = AgentCoreLongTermMemoryProperties.Semantic.CONFIG_PREFIX, name = "strategy-id")
	AgentCoreLongTermMemoryAdvisor semanticAdvisor(AgentCoreLongTermMemoryRetriever retriever,
			AgentCoreLongTermMemoryProperties config) {
		var semanticConfig = config.semantic();
		return AgentCoreLongTermMemoryAdvisor.builder(retriever, MemoryStrategy.SEMANTIC)
			.strategyId(semanticConfig.strategyId())
			.contextLabel("Known facts about the user (use naturally in conversation)")
			.topK(semanticConfig.topK())
			.namespacePattern(semanticConfig.resolveNamespacePattern())
			.build();
	}

	@Bean
	@ConditionalOnMissingBean(name = "userPreferenceAdvisor")
	@ConditionalOnProperty(prefix = AgentCoreLongTermMemoryProperties.UserPreference.CONFIG_PREFIX,
			name = "strategy-id")
	AgentCoreLongTermMemoryAdvisor userPreferenceAdvisor(AgentCoreLongTermMemoryRetriever retriever,
			AgentCoreLongTermMemoryProperties config) {
		var prefConfig = config.userPreference();
		return AgentCoreLongTermMemoryAdvisor.builder(retriever, MemoryStrategy.USER_PREFERENCE)
			.strategyId(prefConfig.strategyId())
			.contextLabel("User preferences (apply when relevant)")
			.namespacePattern(prefConfig.resolveNamespacePattern())
			.build();
	}

	@Bean
	@ConditionalOnMissingBean(name = "summaryAdvisor")
	@ConditionalOnProperty(prefix = AgentCoreLongTermMemoryProperties.Summary.CONFIG_PREFIX, name = "strategy-id")
	AgentCoreLongTermMemoryAdvisor summaryAdvisor(AgentCoreLongTermMemoryRetriever retriever,
			AgentCoreLongTermMemoryProperties config) {
		var summaryConfig = config.summary();
		return AgentCoreLongTermMemoryAdvisor.builder(retriever, MemoryStrategy.SUMMARY)
			.strategyId(summaryConfig.strategyId())
			.contextLabel("Previous conversation summaries (use for continuity)")
			.topK(summaryConfig.topK())
			.namespacePattern(summaryConfig.resolveNamespacePattern())
			.build();
	}

	@Bean
	@ConditionalOnMissingBean(name = "episodicAdvisor")
	@ConditionalOnProperty(prefix = AgentCoreLongTermMemoryProperties.Episodic.CONFIG_PREFIX, name = "strategy-id")
	AgentCoreLongTermMemoryAdvisor episodicAdvisor(AgentCoreLongTermMemoryRetriever retriever,
			AgentCoreLongTermMemoryProperties config) {
		var episodicConfig = config.episodic();
		return AgentCoreLongTermMemoryAdvisor.builder(retriever, MemoryStrategy.EPISODIC)
			.strategyId(episodicConfig.strategyId())
			.reflectionsStrategyId(episodicConfig.reflectionsStrategyId())
			.contextLabel("Past interactions and reflections (reference when relevant)")
			.topK(episodicConfig.episodesTopK())
			.reflectionsTopK(episodicConfig.reflectionsTopK())
			.namespacePattern(episodicConfig.resolveNamespacePattern())
			.build();
	}

}
