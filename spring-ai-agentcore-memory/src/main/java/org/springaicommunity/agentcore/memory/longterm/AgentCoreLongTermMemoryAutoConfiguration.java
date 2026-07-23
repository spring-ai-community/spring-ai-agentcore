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

package org.springaicommunity.agentcore.memory.longterm;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springaicommunity.agentcore.memory.AgentCoreMemoryProperties;
import org.springaicommunity.agentcore.memory.longterm.AgentCoreLongTermMemoryStrategyDiscovery.DiscoveredStrategy;
import org.springaicommunity.agentcore.memory.longterm.strategy.EpisodicMemoryStrategyHandler;
import org.springaicommunity.agentcore.memory.longterm.strategy.SemanticMemoryStrategyHandler;
import org.springaicommunity.agentcore.memory.longterm.strategy.SummaryMemoryStrategyHandler;
import org.springaicommunity.agentcore.memory.longterm.strategy.UserPreferenceMemoryStrategyHandler;
import org.springaicommunity.agentcore.memory.shortterm.AgentCoreShortTermMemoryRepository;
import org.springaicommunity.agentcore.memory.shortterm.AgentCoreShortTermMemoryRepositoryAutoConfiguration;
import software.amazon.awssdk.services.bedrockagentcore.BedrockAgentCoreClient;
import software.amazon.awssdk.services.bedrockagentcorecontrol.BedrockAgentCoreControlClient;

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

	@Bean
	@ConditionalOnMissingBean
	@Conditional(AnyStrategyConfiguredCondition.class)
	Supplier<BedrockAgentCoreControlClient> controlClientFactory() {
		return BedrockAgentCoreControlClient::create;
	}

	/**
	 * Bridge bean exposing the AgentCore short-term repository as the generic Spring AI
	 * {@link ChatMemoryRepository}.
	 * @param shortTermMemoryRepository the AgentCore-backed short-term repository
	 * @return the same repository typed as {@link ChatMemoryRepository}
	 * @deprecated since 1.2.0. Prefer the Session API stack via
	 * {@code agentcore.memory.session.enabled=true}. See issue #152.
	 */
	@Bean
	@ConditionalOnMissingBean
	@Conditional(AnyStrategyConfiguredCondition.class)
	@Deprecated(since = "1.2.0")
	ChatMemoryRepository chatMemoryRepository(AgentCoreShortTermMemoryRepository shortTermMemoryRepository) {
		return shortTermMemoryRepository;
	}

	/**
	 * Bridge bean exposing a {@link ChatMemory} built from the AgentCore short-term
	 * repository.
	 * @param shortTermMemoryRepository the AgentCore-backed short-term repository
	 * @return a {@link MessageWindowChatMemory} instance
	 * @deprecated since 1.2.0. Prefer the Session API stack via
	 * {@code agentcore.memory.session.enabled=true}. See issue #152.
	 */
	@Bean
	@ConditionalOnMissingBean
	@Conditional(AnyStrategyConfiguredCondition.class)
	@Deprecated(since = "1.2.0")
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
			Map<String, List<String>> namespacesByStrategy = this.collectNamespacesByStrategy(longTermMemoryProperties);
			if (!namespacesByStrategy.isEmpty()) {
				try (BedrockAgentCoreControlClient controlClient = controlClientFactory.get()) {
					AgentCoreLongTermMemoryNamespaceRegistrar registrar = new AgentCoreLongTermMemoryNamespaceRegistrar(
							controlClient);
					AgentCoreLongTermMemoryNamespaceValidator validator = new AgentCoreLongTermMemoryNamespaceValidator(
							controlClient, registrar, longTermMemoryProperties.namespace().autoRegister());
					validator.validateNamespaces(memoryId, namespacesByStrategy);
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
			AgentCoreLongTermMemoryNamespaceRegistrar registrar = (config.namespace().autoRegister())
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
		var c = config.semantic();
		var handler = SemanticMemoryStrategyHandler.builder()
			.strategyId(c.strategyId())
			.namespacePattern(c.resolveNamespacePattern())
			.topK(c.topK())
			.contextLabel(AgentCoreLongTermMemoryStrategyType.SEMANTIC.contextLabel())
			.build();
		return AgentCoreLongTermMemoryAdvisor.builder(retriever)
			.memoryStrategy(AgentCoreLongTermMemoryStrategyType.SEMANTIC)
			.handler(handler)
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
		var c = config.userPreference();
		var handler = UserPreferenceMemoryStrategyHandler.builder()
			.strategyId(c.strategyId())
			.namespacePattern(c.resolveNamespacePattern())
			.contextLabel(AgentCoreLongTermMemoryStrategyType.USER_PREFERENCE.contextLabel())
			.build();
		return AgentCoreLongTermMemoryAdvisor.builder(retriever)
			.memoryStrategy(AgentCoreLongTermMemoryStrategyType.USER_PREFERENCE)
			.handler(handler)
			.build();
	}

	@Bean
	@ConditionalOnMissingBean(name = "summaryAdvisor")
	@ConditionalOnProperty(prefix = AgentCoreLongTermMemoryProperties.Summary.CONFIG_PREFIX, name = "strategy-id")
	@ConditionalOnProperty(prefix = AgentCoreLongTermMemoryProperties.CONFIG_PREFIX, name = "auto-discovery",
			havingValue = "false", matchIfMissing = true)
	AgentCoreLongTermMemoryAdvisor summaryAdvisor(AgentCoreLongTermMemoryRetriever retriever,
			AgentCoreLongTermMemoryProperties config) {
		var c = config.summary();
		var handler = SummaryMemoryStrategyHandler.builder()
			.strategyId(c.strategyId())
			.namespacePattern(c.resolveNamespacePattern())
			.topK(c.topK())
			.contextLabel(AgentCoreLongTermMemoryStrategyType.SUMMARY.contextLabel())
			.build();
		return AgentCoreLongTermMemoryAdvisor.builder(retriever)
			.memoryStrategy(AgentCoreLongTermMemoryStrategyType.SUMMARY)
			.handler(handler)
			.build();
	}

	@Bean
	@ConditionalOnMissingBean(name = "episodicAdvisor")
	@ConditionalOnProperty(prefix = AgentCoreLongTermMemoryProperties.Episodic.CONFIG_PREFIX, name = "strategy-id")
	@ConditionalOnProperty(prefix = AgentCoreLongTermMemoryProperties.CONFIG_PREFIX, name = "auto-discovery",
			havingValue = "false", matchIfMissing = true)
	AgentCoreLongTermMemoryAdvisor episodicAdvisor(AgentCoreLongTermMemoryRetriever retriever,
			AgentCoreLongTermMemoryProperties config) {
		var c = config.episodic();
		var handlerBuilder = EpisodicMemoryStrategyHandler.builder()
			.strategyId(c.strategyId())
			.namespacePattern(c.resolveNamespacePattern())
			.episodesTopK(c.episodesTopK())
			.reflectionsTopK(c.reflectionsTopK());

		// Prefer the modern namespace-based path. Fall back to the deprecated
		// separate-strategy path only when no namespace override is set.
		String reflectionsPattern = c.resolveReflectionsNamespacePattern();
		if (reflectionsPattern != null) {
			handlerBuilder.reflectionsNamespacePattern(reflectionsPattern);
		}
		else if (c.reflectionsStrategyId() != null && !c.reflectionsStrategyId().isEmpty()) {
			handlerBuilder.reflectionsStrategyId(c.reflectionsStrategyId());
		}

		return AgentCoreLongTermMemoryAdvisor.builder(retriever)
			.memoryStrategy(AgentCoreLongTermMemoryStrategyType.EPISODIC)
			.handler(handlerBuilder.build())
			.build();
	}

	// ==================== Helper Methods ====================

	/**
	 * Collects the expected namespace patterns for each explicitly configured strategy,
	 * keyed by strategy id. Multiple namespaces per strategy are possible — e.g. episodic
	 * strategies with reflections live under the same id but two namespaces. Used by the
	 * startup namespace validator/registrar.
	 * @param config the long-term memory configuration
	 * @return map of strategy id to expected namespace patterns
	 */
	private Map<String, List<String>> collectNamespacesByStrategy(AgentCoreLongTermMemoryProperties config) {
		Map<String, List<String>> namespacesByStrategy = new HashMap<>();
		if (config.semantic() != null && config.semantic().strategyId() != null) {
			namespacesByStrategy.put(config.semantic().strategyId(),
					List.of(config.semantic().resolveNamespacePattern()));
		}
		if (config.userPreference() != null && config.userPreference().strategyId() != null) {
			namespacesByStrategy.put(config.userPreference().strategyId(),
					List.of(config.userPreference().resolveNamespacePattern()));
		}
		if (config.summary() != null && config.summary().strategyId() != null) {
			namespacesByStrategy.put(config.summary().strategyId(),
					List.of(config.summary().resolveNamespacePattern()));
		}
		if (config.episodic() != null && config.episodic().strategyId() != null) {
			var episodic = config.episodic();
			List<String> namespaces = new ArrayList<>();
			namespaces.add(episodic.resolveNamespacePattern());

			// Modern path: reflections live under the same strategy, different namespace.
			String reflectionsPattern = episodic.resolveReflectionsNamespacePattern();
			if (reflectionsPattern != null) {
				namespaces.add(reflectionsPattern);
			}
			namespacesByStrategy.put(episodic.strategyId(), namespaces);

			// Legacy path: reflections in a separate strategy, same namespace. Kept
			// working for one release to ease migration. Will be removed.
			if (reflectionsPattern == null && episodic.reflectionsStrategyId() != null
					&& !episodic.reflectionsStrategyId().isEmpty()) {
				namespacesByStrategy.put(episodic.reflectionsStrategyId(), List.of(episodic.resolveNamespacePattern()));
			}
		}
		return namespacesByStrategy;
	}

	/**
	 * Fires when either auto-discovery is on, or at least one explicit strategy id is
	 * set. Inner classes look unused but are discovered reflectively by
	 * {@link AnyNestedCondition} through their {@code @ConditionalOnProperty}
	 * annotations.
	 */
	@SuppressWarnings("unused")
	static class AnyStrategyConfiguredCondition extends AnyNestedCondition {

		AnyStrategyConfiguredCondition() {
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

}
