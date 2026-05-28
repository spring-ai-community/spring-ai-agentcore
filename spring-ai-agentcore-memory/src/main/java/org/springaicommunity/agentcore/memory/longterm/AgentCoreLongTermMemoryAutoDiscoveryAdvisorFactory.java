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
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springaicommunity.agentcore.memory.AgentCoreMemoryException;
import org.springaicommunity.agentcore.memory.longterm.AgentCoreLongTermMemoryStrategyDiscovery.DiscoveredStrategy;
import org.springaicommunity.agentcore.memory.longterm.strategy.EpisodicMemoryStrategyHandler;
import org.springaicommunity.agentcore.memory.longterm.strategy.MemoryStrategyHandler;
import org.springaicommunity.agentcore.memory.longterm.strategy.SemanticMemoryStrategyHandler;
import org.springaicommunity.agentcore.memory.longterm.strategy.SummaryMemoryStrategyHandler;
import org.springaicommunity.agentcore.memory.longterm.strategy.UserPreferenceMemoryStrategyHandler;

/**
 * Factory for creating LTM advisors from auto-discovered strategies.
 *
 * <p>
 * For each discovered strategy, picks the matching built-in handler class and builds an
 * advisor wrapping it. When explicit configuration for the same {@code strategyId} is
 * present, it wins; otherwise the discovered namespaces are used as defaults.
 *
 * @author Andrei Shakirin
 */
class AgentCoreLongTermMemoryAutoDiscoveryAdvisorFactory {

	private static final Logger logger = LoggerFactory
		.getLogger(AgentCoreLongTermMemoryAutoDiscoveryAdvisorFactory.class);

	private final AgentCoreLongTermMemoryRetriever retriever;

	private final AgentCoreLongTermMemoryProperties config;

	private final String memoryId;

	private final AgentCoreLongTermMemoryNamespaceRegistrar registrar;

	AgentCoreLongTermMemoryAutoDiscoveryAdvisorFactory(AgentCoreLongTermMemoryRetriever retriever,
			AgentCoreLongTermMemoryProperties config, String memoryId,
			AgentCoreLongTermMemoryNamespaceRegistrar registrar) {
		this.retriever = retriever;
		this.config = config;
		this.memoryId = memoryId;
		this.registrar = registrar;
	}

	List<AgentCoreLongTermMemoryAdvisor> createAdvisors(List<DiscoveredStrategy> discovered) {
		List<AgentCoreLongTermMemoryAdvisor> advisors = new ArrayList<>();
		for (DiscoveredStrategy strategy : discovered) {
			advisors.add(this.createAdvisor(strategy));
		}
		logger.info("Created {} advisors from autodiscovery", advisors.size());
		return advisors;
	}

	private AgentCoreLongTermMemoryAdvisor createAdvisor(DiscoveredStrategy discovered) {
		AgentCoreLongTermMemoryStrategyType strategy = discovered.type();
		AgentCoreLongTermMemoryStrategy explicitConfig = this.config.byKind(strategy);
		boolean configMatches = explicitConfig != null && discovered.strategyId().equals(explicitConfig.strategyId());
		AgentCoreLongTermMemoryStrategy effectiveExplicit = (configMatches) ? explicitConfig : null;

		String namespace = this.resolveNamespace(discovered, effectiveExplicit);
		MemoryStrategyHandler handler = this.buildHandler(discovered, effectiveExplicit, namespace,
				strategy.contextLabel());

		return AgentCoreLongTermMemoryAdvisor.builder(this.retriever).memoryStrategy(strategy).handler(handler).build();
	}

	private MemoryStrategyHandler buildHandler(DiscoveredStrategy discovered,
			AgentCoreLongTermMemoryStrategy explicitConfig, String namespace, String contextLabel) {
		return switch (discovered.type()) {
			case SEMANTIC -> {
				var b = SemanticMemoryStrategyHandler.builder()
					.strategyId(discovered.strategyId())
					.namespacePattern(namespace)
					.contextLabel(contextLabel);
				if (explicitConfig instanceof AgentCoreLongTermMemoryProperties.Semantic s) {
					b.topK(s.topK());
				}
				yield b.build();
			}
			case USER_PREFERENCE -> UserPreferenceMemoryStrategyHandler.builder()
				.strategyId(discovered.strategyId())
				.namespacePattern(namespace)
				.contextLabel(contextLabel)
				.build();
			case SUMMARY -> {
				var b = SummaryMemoryStrategyHandler.builder()
					.strategyId(discovered.strategyId())
					.namespacePattern(namespace)
					.contextLabel(contextLabel);
				if (explicitConfig instanceof AgentCoreLongTermMemoryProperties.Summary s) {
					b.topK(s.topK());
				}
				yield b.build();
			}
			case EPISODIC -> this.buildEpisodicHandler(discovered, explicitConfig, namespace);
			case CUSTOM -> throw new IllegalStateException("Auto-discovery should never produce CUSTOM; "
					+ "AgentCoreLongTermMemoryStrategyType.fromAwsType filters it out.");
		};
	}

	private EpisodicMemoryStrategyHandler buildEpisodicHandler(DiscoveredStrategy discovered,
			AgentCoreLongTermMemoryStrategy explicitConfig, String namespace) {
		var b = EpisodicMemoryStrategyHandler.builder().strategyId(discovered.strategyId()).namespacePattern(namespace);

		if (explicitConfig instanceof AgentCoreLongTermMemoryProperties.Episodic episodicExplicit) {
			b.episodesTopK(episodicExplicit.episodesTopK());
			b.reflectionsTopK(episodicExplicit.reflectionsTopK());

			String explicitReflectionsPattern = episodicExplicit.resolveReflectionsNamespacePattern();
			if (explicitReflectionsPattern != null) {
				b.reflectionsNamespacePattern(explicitReflectionsPattern);
				logger.debug("Strategy '{}': using configured reflections namespace '{}'", discovered.strategyId(),
						explicitReflectionsPattern);
			}
			else if (episodicExplicit.usesLegacyReflectionsStrategy()) {
				b.reflectionsStrategyId(episodicExplicit.reflectionsStrategyId());
				logger.debug("Strategy '{}': using deprecated reflectionsStrategyId '{}'", discovered.strategyId(),
						episodicExplicit.reflectionsStrategyId());
			}
			else {
				this.applyDiscoveredReflections(b, discovered);
			}
		}
		else {
			this.applyDiscoveredReflections(b, discovered);
		}

		return b.build();
	}

	private void applyDiscoveredReflections(EpisodicMemoryStrategyHandler.Builder b, DiscoveredStrategy discovered) {
		String discoveredReflections = discovered.defaultReflectionsNamespace();
		if (discoveredReflections != null) {
			b.reflectionsNamespacePattern(discoveredReflections);
			logger.debug("Strategy '{}': using discovered reflections namespace '{}'", discovered.strategyId(),
					discoveredReflections);
		}
	}

	private String resolveNamespace(DiscoveredStrategy discovered, AgentCoreLongTermMemoryStrategy explicitConfig) {
		if (explicitConfig == null || explicitConfig.namespacePattern() == null) {
			String namespace = discovered.defaultNamespace();
			logger.debug("Strategy '{}': using discovered namespace '{}'", discovered.strategyId(), namespace);
			return namespace;
		}

		String configuredNamespace = explicitConfig.namespacePattern();
		if (discovered.namespaces().contains(configuredNamespace)) {
			logger.debug("Strategy '{}': using configured namespace '{}' (matches discovered)", discovered.strategyId(),
					configuredNamespace);
			return configuredNamespace;
		}

		if (this.registrar != null) {
			this.registrar.registerNamespace(this.memoryId, discovered.strategyId(), configuredNamespace);
			logger.info("Strategy '{}': auto-registered new namespace '{}'", discovered.strategyId(),
					configuredNamespace);
			return configuredNamespace;
		}

		throw new AgentCoreMemoryException.ConfigurationException("Configured namespace '" + configuredNamespace
				+ "' for strategy '" + discovered.strategyId() + "' does not match any discovered namespace. "
				+ "Available namespaces: " + discovered.namespaces());
	}

}
