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
import software.amazon.awssdk.services.bedrockagentcorecontrol.model.CustomReflectionConfiguration;
import software.amazon.awssdk.services.bedrockagentcorecontrol.model.EpisodicReflectionConfiguration;
import software.amazon.awssdk.services.bedrockagentcorecontrol.model.EpisodicReflectionOverride;
import software.amazon.awssdk.services.bedrockagentcorecontrol.model.GetMemoryRequest;
import software.amazon.awssdk.services.bedrockagentcorecontrol.model.MemoryStrategy;
import software.amazon.awssdk.services.bedrockagentcorecontrol.model.MemoryStrategyType;
import software.amazon.awssdk.services.bedrockagentcorecontrol.model.OverrideType;
import software.amazon.awssdk.services.bedrockagentcorecontrol.model.ReflectionConfiguration;
import software.amazon.awssdk.services.bedrockagentcorecontrol.model.StrategyConfiguration;

/**
 * Discovers memory strategies from AgentCore Memory for autodiscovery mode.
 *
 * <p>
 * A {@code CUSTOM} strategy overrides one built-in type, recorded in its configuration's
 * override type, and is discovered as that type. Self-managed and unknown overrides have
 * no built-in retrieval and are skipped.
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
	 * @return list of discovered strategies (CUSTOM ones as the built-in type they
	 * override; excludes self-managed and unknown types)
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
		List<String> typeLabels = new ArrayList<>();
		int skipped = 0;
		for (MemoryStrategy strategy : strategies) {
			var mapped = this.mapStrategy(strategy);
			if (mapped != null) {
				// A CUSTOM strategy shows its override type, which is why it got this
				// advisor
				String typeLabel = (strategy.type() == MemoryStrategyType.CUSTOM) ? describeType(strategy)
						: mapped.type().name();
				discovered.add(mapped);
				typeLabels.add(typeLabel);
				logger.info("Discovered strategy: {} (type={}, namespaces={}, reflectionsNamespaces={})",
						mapped.strategyId(), typeLabel, mapped.namespaces(), mapped.reflectionsNamespaces());
			}
			else {
				skipped++;
			}
		}

		this.logDiscoveryOverview(memoryId, strategies.size(), discovered, typeLabels, skipped);
		return discovered;
	}

	private void logDiscoveryOverview(String memoryId, int total, List<DiscoveredStrategy> discovered,
			List<String> typeLabels, int skipped) {
		if (discovered.isEmpty()) {
			logger.warn("Strategy discovery for memory {}: {} total, 0 usable, {} skipped", memoryId, total, skipped);
			return;
		}
		// Wide enough for labels such as CUSTOM (USER_PREFERENCE_OVERRIDE)
		int typeWidth = Math.max(20, typeLabels.stream().mapToInt(String::length).max().orElse(0));
		String row = "  %-" + typeWidth + "s %-45s %s%n";
		StringBuilder table = new StringBuilder();
		table.append(String.format("%n  Strategy discovery for memory '%s':%n", memoryId));
		table.append(String.format(row, "TYPE", "STRATEGY ID", "REFLECTIONS"));
		table.append("  ").append("-".repeat(typeWidth + 60)).append(System.lineSeparator());
		for (int i = 0; i < discovered.size(); i++) {
			DiscoveredStrategy ds = discovered.get(i);
			String refl = (ds.reflectionsNamespaces().isEmpty()) ? "no" : "yes";
			table.append(String.format(row, typeLabels.get(i), ds.strategyId(), refl));
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

		AgentCoreLongTermMemoryStrategyType type = effectiveType(strategy);
		if (type == null) {
			logger.warn("Unknown or unsupported strategy type '{}' for strategy '{}', skipping", describeType(strategy),
					strategy.strategyId());
			return null;
		}

		var namespaces = strategy.namespaces();
		if (namespaces == null || namespaces.isEmpty()) {
			logger.warn("Strategy '{}' has no namespaces, skipping", strategy.strategyId());
			return null;
		}
		// Retrieval cannot fill custom namespace keys; every request for this strategy
		// would fail, and the failure would reach the chat call.
		for (String namespace : namespaces) {
			String unfilled = AgentCoreLongTermMemoryNamespace.unfilledPlaceholder(namespace);
			if (unfilled != null) {
				logger.warn("Strategy '{}' namespace '{}' uses the placeholder {}, which retrieval cannot fill, "
						+ "skipping", strategy.strategyId(), namespace, unfilled);
				return null;
			}
		}

		List<String> reflectionsNamespaces = (type != AgentCoreLongTermMemoryStrategyType.EPISODIC) ? List.of()
				: extractReflectionsNamespaces(strategy);

		return new DiscoveredStrategy(strategy.strategyId(), type, List.copyOf(namespaces), reflectionsNamespaces);
	}

	/**
	 * The built-in type whose retrieval fits the strategy: its AWS type, or for a
	 * {@code CUSTOM} strategy the type it overrides. Shared with the namespace validator,
	 * which checks the configured group against it.
	 * @param strategy the AWS memory strategy descriptor
	 * @return the strategy type, or {@code null} for self-managed, unknown or missing
	 * types
	 */
	static AgentCoreLongTermMemoryStrategyType effectiveType(MemoryStrategy strategy) {
		AgentCoreLongTermMemoryStrategyType type = AgentCoreLongTermMemoryStrategyType.fromAwsType(strategy.type());
		OverrideType override = overrideType(strategy);
		if (type != null || override == null) {
			return type;
		}
		return switch (override) {
			case SEMANTIC_OVERRIDE -> AgentCoreLongTermMemoryStrategyType.SEMANTIC;
			case SUMMARY_OVERRIDE -> AgentCoreLongTermMemoryStrategyType.SUMMARY;
			case USER_PREFERENCE_OVERRIDE -> AgentCoreLongTermMemoryStrategyType.USER_PREFERENCE;
			case EPISODIC_OVERRIDE -> AgentCoreLongTermMemoryStrategyType.EPISODIC;
			case SELF_MANAGED, UNKNOWN_TO_SDK_VERSION -> null;
		};
	}

	private static OverrideType overrideType(MemoryStrategy strategy) {
		StrategyConfiguration configuration = strategy.configuration();
		return (strategy.type() == MemoryStrategyType.CUSTOM && configuration != null) ? configuration.type() : null;
	}

	// "SUMMARIZATION", or "CUSTOM (SUMMARY_OVERRIDE)" for a custom override
	static String describeType(MemoryStrategy strategy) {
		OverrideType override = overrideType(strategy);
		return (override != null) ? strategy.type() + " (" + override + ")" : String.valueOf(strategy.type());
	}

	/**
	 * Walks the nullable {@link StrategyConfiguration} &rarr;
	 * {@link ReflectionConfiguration} chain to extract reflection namespaces for an
	 * episodic strategy, from its {@link EpisodicReflectionConfiguration} or, for a
	 * {@code CUSTOM} strategy, from the {@link EpisodicReflectionOverride} of its
	 * {@link CustomReflectionConfiguration}. Returns an empty list when any link is
	 * missing. The namespace validator calls it as well.
	 * @param strategy the AWS memory strategy descriptor
	 * @return the configured reflection namespaces, or empty when not applicable
	 */
	static List<String> extractReflectionsNamespaces(MemoryStrategy strategy) {
		StrategyConfiguration configuration = strategy.configuration();
		ReflectionConfiguration reflection = (configuration != null) ? configuration.reflection() : null;
		if (reflection == null) {
			return List.of();
		}
		EpisodicReflectionConfiguration episodic = reflection.episodicReflectionConfiguration();
		if (episodic != null) {
			return namespacesOrTemplates(episodic.namespaces(), episodic.namespaceTemplates());
		}
		CustomReflectionConfiguration custom = reflection.customReflectionConfiguration();
		EpisodicReflectionOverride override = (custom != null) ? custom.episodicReflectionOverride() : null;
		return (override != null) ? namespacesOrTemplates(override.namespaces(), override.namespaceTemplates())
				: List.of();
	}

	// AWS marks the reflection namespaces field as legacy in favor of
	// namespaceTemplates, so fall back to the templates when it is empty.
	static List<String> namespacesOrTemplates(List<String> namespaces, List<String> namespaceTemplates) {
		List<String> chosen = (namespaces != null && !namespaces.isEmpty()) ? namespaces : namespaceTemplates;
		return (chosen != null) ? List.copyOf(chosen) : List.of();
	}

	/**
	 * Represents a discovered memory strategy.
	 *
	 * @param strategyId AWS strategy ID
	 * @param type strategy type (semantic / summary / user-preference / episodic); never
	 * {@code CUSTOM} because a custom AWS strategy is discovered as the type it overrides
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
