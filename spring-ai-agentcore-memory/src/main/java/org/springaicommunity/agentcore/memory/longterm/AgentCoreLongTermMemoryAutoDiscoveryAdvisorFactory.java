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

package org.springaicommunity.agentcore.memory.longterm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springaicommunity.agentcore.memory.AgentCoreMemoryException;
import org.springaicommunity.agentcore.memory.longterm.AgentCoreLongTermMemoryStrategyDiscovery.DiscoveredStrategy;

import java.util.ArrayList;
import java.util.List;

/**
 * Factory for creating LTM advisors from autodiscovered strategies.
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
			advisors.add(createAdvisor(strategy));
		}
		logger.info("Created {} advisors from autodiscovery", advisors.size());
		return advisors;
	}

	private AgentCoreLongTermMemoryAdvisor createAdvisor(DiscoveredStrategy discovered) {
		MemoryStrategiesMap.StrategyLabel strategyLabel = MemoryStrategiesMap.LABELS.get(discovered.type());
		AgentCoreLongTermMemoryStrategy explicitConfig = getExplicitConfig(discovered);

		boolean configMatches = explicitConfig != null && discovered.strategyId().equals(explicitConfig.strategyId());
		String namespace = resolveNamespace(discovered, configMatches ? explicitConfig : null);
		Integer topK = configMatches ? getTopK(explicitConfig) : null;

		var builder = AgentCoreLongTermMemoryAdvisor.builder(retriever, strategyLabel.strategy())
			.strategyId(discovered.strategyId())
			.contextLabel(strategyLabel.contextLabel())
			.namespacePattern(namespace);

		if (topK != null) {
			builder.topK(topK);
		}

		return builder.build();
	}

	private AgentCoreLongTermMemoryStrategy getExplicitConfig(DiscoveredStrategy discovered) {
		return switch (discovered.type()) {
			case SEMANTIC -> config.semantic();
			case USER_PREFERENCE -> config.userPreference();
			case SUMMARY -> config.summary();
			case EPISODIC -> config.episodic();
		};
	}

	private Integer getTopK(AgentCoreLongTermMemoryStrategy explicitConfig) {
		if (explicitConfig instanceof AgentCoreLongTermMemoryProperties.Episodic e) {
			return e.episodesTopK();
		}
		else if (explicitConfig instanceof AgentCoreLongTermMemoryProperties.Semantic s) {
			return s.topK();
		}
		else if (explicitConfig instanceof AgentCoreLongTermMemoryProperties.Summary s) {
			return s.topK();
		}
		return null;
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

		if (registrar != null) {
			registrar.registerNamespace(memoryId, discovered.strategyId(), configuredNamespace);
			logger.info("Strategy '{}': auto-registered new namespace '{}'", discovered.strategyId(),
					configuredNamespace);
			return configuredNamespace;
		}

		throw new AgentCoreMemoryException.ConfigurationException("Configured namespace '" + configuredNamespace
				+ "' for strategy '" + discovered.strategyId() + "' does not match any discovered namespace. "
				+ "Available namespaces: " + discovered.namespaces());
	}

}
