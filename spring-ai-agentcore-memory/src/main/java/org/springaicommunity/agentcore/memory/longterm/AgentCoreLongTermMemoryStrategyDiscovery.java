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
import software.amazon.awssdk.services.bedrockagentcorecontrol.BedrockAgentCoreControlClient;
import software.amazon.awssdk.services.bedrockagentcorecontrol.model.EpisodicReflectionConfiguration;
import software.amazon.awssdk.services.bedrockagentcorecontrol.model.GetMemoryRequest;
import software.amazon.awssdk.services.bedrockagentcorecontrol.model.MemoryStrategy;
import software.amazon.awssdk.services.bedrockagentcorecontrol.model.MemoryStrategyType;
import software.amazon.awssdk.services.bedrockagentcorecontrol.model.ReflectionConfiguration;
import software.amazon.awssdk.services.bedrockagentcorecontrol.model.StrategyConfiguration;

/**
 * Discovers memory strategies from AgentCore Memory for autodiscovery mode.
 *
 * @author Andrei Shakirin
 */
public class AgentCoreLongTermMemoryStrategyDiscovery {

	private static final Logger logger = LoggerFactory.getLogger(AgentCoreLongTermMemoryStrategyDiscovery.class);

	private final BedrockAgentCoreControlClient controlClient;

	public AgentCoreLongTermMemoryStrategyDiscovery(BedrockAgentCoreControlClient controlClient) {
		this.controlClient = controlClient;
	}

	/**
	 * Discovers all strategies from the given memory.
	 * @param memoryId the memory resource ID
	 * @return list of discovered strategies (excludes CUSTOM and unknown types)
	 */
	public List<DiscoveredStrategy> discoverStrategies(String memoryId) {
		logger.info("Discovering strategies for memory: {}", memoryId);

		var response = this.controlClient.getMemory(GetMemoryRequest.builder().memoryId(memoryId).build());
		var strategies = response.memory().strategies();

		if (strategies == null || strategies.isEmpty()) {
			logger.warn("No strategies found in memory: {}", memoryId);
			return List.of();
		}

		List<DiscoveredStrategy> discovered = new ArrayList<>();
		int skipped = 0;
		for (MemoryStrategy strategy : strategies) {
			var mapped = this.mapStrategy(strategy);
			if (mapped != null) {
				discovered.add(mapped);
				logger.info("Discovered strategy: {} (type={}, namespaces={}, reflectionsNamespaces={})",
						mapped.strategyId(), mapped.type(), mapped.namespaces(), mapped.reflectionsNamespaces());
			}
			else {
				skipped++;
			}
		}

		this.logDiscoveryOverview(memoryId, strategies.size(), discovered, skipped);
		return discovered;
	}

	private void logDiscoveryOverview(String memoryId, int total, List<DiscoveredStrategy> discovered, int skipped) {
		if (discovered.isEmpty()) {
			logger.warn("Strategy discovery for memory {}: {} total, 0 usable, {} skipped", memoryId, total, skipped);
			return;
		}
		StringBuilder table = new StringBuilder();
		table.append(String.format("%n  Strategy discovery for memory '%s':%n", memoryId));
		table.append(String.format("  %-20s %-45s %s%n", "TYPE", "STRATEGY ID", "REFLECTIONS"));
		table.append("  ").append("-".repeat(80)).append(System.lineSeparator());
		for (DiscoveredStrategy ds : discovered) {
			String refl = (ds.reflectionsNamespaces().isEmpty()) ? "no" : "yes";
			table.append(String.format("  %-20s %-45s %s%n", ds.type(), ds.strategyId(), refl));
		}
		table.append(String.format("  %d usable / %d total%s", discovered.size(), total,
				(skipped > 0) ? " (" + skipped + " skipped)" : ""));
		logger.info(table.toString());
	}

	private DiscoveredStrategy mapStrategy(MemoryStrategy strategy) {
		MemoryStrategyType sdkType = strategy.type();
		if (sdkType == null) {
			logger.warn("Strategy '{}' has no type, skipping", strategy.strategyId());
			return null;
		}

		AgentCoreLongTermMemoryStrategyType type = AgentCoreLongTermMemoryStrategyType.fromAwsType(sdkType);
		if (type == null) {
			logger.warn("Unknown or unsupported strategy type '{}' for strategy '{}', skipping", sdkType,
					strategy.strategyId());
			return null;
		}

		var namespaces = strategy.namespaces();
		if (namespaces == null || namespaces.isEmpty()) {
			logger.warn("Strategy '{}' has no namespaces, skipping", strategy.strategyId());
			return null;
		}

		List<String> reflectionsNamespaces = (type != AgentCoreLongTermMemoryStrategyType.EPISODIC) ? List.of()
				: this.extractReflectionsNamespaces(strategy);

		return new DiscoveredStrategy(strategy.strategyId(), type, List.copyOf(namespaces), reflectionsNamespaces);
	}

	/**
	 * Walks the nullable {@link StrategyConfiguration} &rarr;
	 * {@link ReflectionConfiguration} &rarr; {@link EpisodicReflectionConfiguration}
	 * chain to extract reflection namespaces for an episodic strategy. Returns an empty
	 * list when any link is missing or when the reflection variant is
	 * {@code customReflectionConfiguration} (handled separately, not supported by
	 * auto-discovery yet).
	 * @param strategy the AWS memory strategy descriptor
	 * @return the configured reflection namespaces, or empty when not applicable
	 */
	private List<String> extractReflectionsNamespaces(MemoryStrategy strategy) {
		StrategyConfiguration configuration = strategy.configuration();
		if (configuration == null) {
			return List.of();
		}
		ReflectionConfiguration reflection = configuration.reflection();
		if (reflection == null) {
			return List.of();
		}
		EpisodicReflectionConfiguration episodic = reflection.episodicReflectionConfiguration();
		if (episodic == null) {
			if (reflection.customReflectionConfiguration() != null) {
				logger.info(
						"Strategy '{}' uses customReflectionConfiguration; reflection-namespace auto-discovery "
								+ "will skip this entry. Configure reflection explicitly via "
								+ "agentcore.memory.long-term.episodic.reflections-namespace-pattern.",
						strategy.strategyId());
			}
			return List.of();
		}
		List<String> reflectionNamespaces = episodic.namespaces();
		return (reflectionNamespaces != null) ? List.copyOf(reflectionNamespaces) : List.of();
	}

	/**
	 * Represents a discovered memory strategy.
	 *
	 * @param strategyId AWS strategy ID
	 * @param type strategy type (semantic / summary / user-preference / episodic); never
	 * {@code CUSTOM} because auto-discovery skips custom AWS strategies
	 * @param namespaces namespaces registered on the strategy (episodes for episodic
	 * strategies, primary namespace otherwise)
	 * @param reflectionsNamespaces reflection namespaces registered on the strategy;
	 * populated only for {@code EPISODIC} strategies that have a reflection
	 * configuration, otherwise empty
	 */
	public record DiscoveredStrategy(String strategyId, AgentCoreLongTermMemoryStrategyType type,
			List<String> namespaces, List<String> reflectionsNamespaces) {

		public DiscoveredStrategy {
			namespaces = (namespaces != null) ? List.copyOf(namespaces) : List.of();
			reflectionsNamespaces = (reflectionsNamespaces != null) ? List.copyOf(reflectionsNamespaces) : List.of();
		}

		/**
		 * Returns the first namespace (default for episodes).
		 * @return the default namespace
		 */
		public String defaultNamespace() {
			return this.namespaces.get(0);
		}

		/**
		 * Returns the first reflections namespace, or {@code null} if none are
		 * configured.
		 * @return the default reflections namespace, or {@code null}
		 */
		public String defaultReflectionsNamespace() {
			return (this.reflectionsNamespaces.isEmpty()) ? null : this.reflectionsNamespaces.get(0);
		}

	}

}
