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

import org.springaicommunity.agentcore.memory.AgentCoreLongMemoryAdvisor.MemoryStrategy;
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
@AutoConfiguration(after = AgentCoreShortMemoryRepositoryAutoConfiguration.class)
@ConditionalOnBean({ BedrockAgentCoreClient.class, AgentCoreMemoryProperties.class })
@EnableConfigurationProperties(AgentCoreLongMemoryProperties.class)
public class AgentCoreLongMemoryAutoConfiguration {

	// This is used for the construction of the AgentCoreLongMemoryRetriever and the
	// actual AgentCoreLongMemoryAdvisor's depend on that.
	// So we have to use an aggregate config
	// Also, there doesn't seem to be a way to just check for agentcore.memory.long-term
	// (any child)
	static class AnyStrategyConfiguredCondition extends AnyNestedCondition {

		public AnyStrategyConfiguredCondition() {
			super(ConfigurationPhase.REGISTER_BEAN);
		}

		@ConditionalOnProperty(prefix = AgentCoreLongMemoryProperties.Semantic.CONFIG_PREFIX, name = "strategy-id")
		static class SemanticCondition {

		}

		@ConditionalOnProperty(prefix = AgentCoreLongMemoryProperties.UserPreference.CONFIG_PREFIX,
				name = "strategy-id")
		static class UserPreferenceCondition {

		}

		@ConditionalOnProperty(prefix = AgentCoreLongMemoryProperties.Summary.CONFIG_PREFIX, name = "strategy-id")
		static class SummaryCondition {

		}

		@ConditionalOnProperty(prefix = AgentCoreLongMemoryProperties.Episodic.CONFIG_PREFIX, name = "strategy-id")
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
	ChatMemoryRepository chatMemoryRepository(AgentCoreShortMemoryRepository shortMemoryRepository) {
		return shortMemoryRepository;
	}

	@Bean
	@ConditionalOnMissingBean
	@Conditional(AnyStrategyConfiguredCondition.class)
	ChatMemory chatMemory(AgentCoreShortMemoryRepository shortMemoryRepository) {
		return MessageWindowChatMemory.builder()
			.chatMemoryRepository(shortMemoryRepository)
			// todo: make this configurable?
			// .maxMessages(10)
			.build();
	}

	@Bean
	@ConditionalOnMissingBean
	@Conditional(AnyStrategyConfiguredCondition.class)
	AgentCoreMemory agentCoreLongMemory(List<AgentCoreLongMemoryAdvisor> ltmAdvisors, ChatMemory chatMemory) {
		var stmAdvisor = MessageChatMemoryAdvisor.builder(chatMemory).build();

		return new AgentCoreMemory(stmAdvisor, ltmAdvisors);
	}

	@Bean
	@ConditionalOnMissingBean
	@Conditional(AnyStrategyConfiguredCondition.class)
	AgentCoreLongMemoryRetriever agentCoreLongMemoryRetriever(BedrockAgentCoreClient client,
			AgentCoreMemoryProperties memoryConfig, AgentCoreLongMemoryProperties longMemoryProperties,
			Supplier<BedrockAgentCoreControlClient> controlClientFactory) {
		String memoryId = memoryConfig.memoryId();

		// Validate namespaces at startup to fail fast on misconfiguration
		// ControlClient is only needed for validation, so create/use/close inline
		Map<String, AgentCoreLongMemoryScope> strategyConfigs = buildStrategyConfigs(longMemoryProperties);
		if (!strategyConfigs.isEmpty()) {
			try (BedrockAgentCoreControlClient controlClient = controlClientFactory.get()) {
				AgentCoreLongMemoryNamespaceValidator validator = new AgentCoreLongMemoryNamespaceValidator(
						controlClient);
				validator.validateNamespaces(memoryId, strategyConfigs);
			}
		}

		return new AgentCoreLongMemoryRetriever(client, memoryId);
	}

	private Map<String, AgentCoreLongMemoryScope> buildStrategyConfigs(AgentCoreLongMemoryProperties config) {
		Map<String, AgentCoreLongMemoryScope> configs = new HashMap<>();
		if (config.semantic() != null) {
			configs.put(config.semantic().strategyId(), config.semantic().scope());
		}
		if (config.userPreference() != null) {
			configs.put(config.userPreference().strategyId(), config.userPreference().scope());
		}
		if (config.summary() != null) {
			configs.put(config.summary().strategyId(), config.summary().scope());
		}
		if (config.episodic() != null) {
			configs.put(config.episodic().strategyId(), config.episodic().scope());
			if (config.episodic().hasReflections()) {
				configs.put(config.episodic().reflectionsStrategyId(), config.episodic().scope());
			}
		}
		return configs;
	}

	@Bean
	@ConditionalOnMissingBean(name = "semanticAdvisor")
	@ConditionalOnProperty(prefix = AgentCoreLongMemoryProperties.Semantic.CONFIG_PREFIX, name = "strategy-id")
	AgentCoreLongMemoryAdvisor semanticAdvisor(AgentCoreLongMemoryRetriever retriever,
			AgentCoreLongMemoryProperties config) {
		var semanticConfig = config.semantic();
		return AgentCoreLongMemoryAdvisor.builder(retriever, MemoryStrategy.SEMANTIC)
			.strategyId(semanticConfig.strategyId())
			.contextLabel("Known facts about the user (use naturally in conversation)")
			.topK(semanticConfig.topK())
			.scope(semanticConfig.scope())
			.build();
	}

	@Bean
	@ConditionalOnMissingBean(name = "userPreferenceAdvisor")
	@ConditionalOnProperty(prefix = AgentCoreLongMemoryProperties.UserPreference.CONFIG_PREFIX, name = "strategy-id")
	AgentCoreLongMemoryAdvisor userPreferenceAdvisor(AgentCoreLongMemoryRetriever retriever,
			AgentCoreLongMemoryProperties config) {
		var prefConfig = config.userPreference();
		return AgentCoreLongMemoryAdvisor.builder(retriever, MemoryStrategy.USER_PREFERENCE)
			.strategyId(prefConfig.strategyId())
			.contextLabel("User preferences (apply when relevant)")
			.scope(prefConfig.scope())
			.build();
	}

	@Bean
	@ConditionalOnMissingBean(name = "summaryAdvisor")
	@ConditionalOnProperty(prefix = AgentCoreLongMemoryProperties.Summary.CONFIG_PREFIX, name = "strategy-id")
	AgentCoreLongMemoryAdvisor summaryAdvisor(AgentCoreLongMemoryRetriever retriever,
			AgentCoreLongMemoryProperties config) {
		var summaryConfig = config.summary();
		return AgentCoreLongMemoryAdvisor.builder(retriever, MemoryStrategy.SUMMARY)
			.strategyId(summaryConfig.strategyId())
			.contextLabel("Previous conversation summaries (use for continuity)")
			.topK(summaryConfig.topK())
			.scope(summaryConfig.scope())
			.build();
	}

	@Bean
	@ConditionalOnMissingBean(name = "episodicAdvisor")
	@ConditionalOnProperty(prefix = AgentCoreLongMemoryProperties.Episodic.CONFIG_PREFIX, name = "strategy-id")
	AgentCoreLongMemoryAdvisor episodicAdvisor(AgentCoreLongMemoryRetriever retriever,
			AgentCoreLongMemoryProperties config) {
		var episodicConfig = config.episodic();
		return AgentCoreLongMemoryAdvisor.builder(retriever, MemoryStrategy.EPISODIC)
			.strategyId(episodicConfig.strategyId())
			.reflectionsStrategyId(episodicConfig.reflectionsStrategyId())
			.contextLabel("Past interactions and reflections (reference when relevant)")
			.topK(episodicConfig.episodesTopK())
			.reflectionsTopK(episodicConfig.reflectionsTopK())
			.scope(episodicConfig.scope())
			.build();
	}

}
