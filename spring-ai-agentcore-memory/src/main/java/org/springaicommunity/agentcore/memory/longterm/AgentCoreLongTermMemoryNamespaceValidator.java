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
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springaicommunity.agentcore.memory.AgentCoreMemoryException;
import software.amazon.awssdk.services.bedrockagentcorecontrol.BedrockAgentCoreControlClient;
import software.amazon.awssdk.services.bedrockagentcorecontrol.model.CustomReflectionConfiguration;
import software.amazon.awssdk.services.bedrockagentcorecontrol.model.EpisodicReflectionOverride;
import software.amazon.awssdk.services.bedrockagentcorecontrol.model.GetMemoryRequest;
import software.amazon.awssdk.services.bedrockagentcorecontrol.model.GetMemoryResponse;
import software.amazon.awssdk.services.bedrockagentcorecontrol.model.MemoryStrategy;
import software.amazon.awssdk.services.bedrockagentcorecontrol.model.MemoryStrategyType;
import software.amazon.awssdk.services.bedrockagentcorecontrol.model.OverrideType;
import software.amazon.awssdk.services.bedrockagentcorecontrol.model.ReflectionConfiguration;
import software.amazon.awssdk.services.bedrockagentcorecontrol.model.StrategyConfiguration;

/**
 * Validates that memory strategy namespaces match the expected format required by
 * {@link AgentCoreLongTermMemoryRetriever}.
 *
 * <p>
 * AgentCore Memory stores long-term memories under namespaces defined during memory
 * creation. If the namespace format doesn't match what the retriever expects, searches
 * will silently return empty results. This validator fails fast at startup to prevent
 * such silent failures.
 *
 * <p>
 * Expected namespace formats:
 * <ul>
 * <li>Actor-scoped (semantic, user-preference, episodic):
 * {@code /strategies/{memoryStrategyId}/actors/{actorId}}</li>
 * <li>Session-scoped (summary):
 * {@code /strategies/{memoryStrategyId}/actors/{actorId}/sessions/{sessionId}}</li>
 * </ul>
 *
 * @author Yuriy Bezsonov
 */
public class AgentCoreLongTermMemoryNamespaceValidator {

	private static final Logger logger = LoggerFactory.getLogger(AgentCoreLongTermMemoryNamespaceValidator.class);

	private static final String STRATEGY_ID_PLACEHOLDER = "{memoryStrategyId}";

	private final BedrockAgentCoreControlClient controlClient;

	private final AgentCoreLongTermMemoryNamespaceRegistrar registrar;

	private final boolean autoRegister;

	private final Map<String, AgentCoreLongTermMemoryStrategyType> configuredTypes;

	public AgentCoreLongTermMemoryNamespaceValidator(BedrockAgentCoreControlClient controlClient,
			AgentCoreLongTermMemoryNamespaceRegistrar registrar, boolean autoRegister) {
		this(controlClient, registrar, autoRegister, Map.of());
	}

	/**
	 * Creates a validator that also checks each strategy's type against the group it is
	 * configured under.
	 * @param controlClient the AgentCore control-plane client
	 * @param registrar the namespace registrar used when auto-registration is on
	 * @param autoRegister whether to register mismatching namespaces
	 * @param configuredTypes the strategy type each strategy ID is configured as, keyed
	 * by strategy ID; IDs without an entry are not type-checked
	 */
	public AgentCoreLongTermMemoryNamespaceValidator(BedrockAgentCoreControlClient controlClient,
			AgentCoreLongTermMemoryNamespaceRegistrar registrar, boolean autoRegister,
			Map<String, AgentCoreLongTermMemoryStrategyType> configuredTypes) {
		this.controlClient = controlClient;
		this.registrar = registrar;
		this.autoRegister = autoRegister;
		this.configuredTypes = Map.copyOf(configuredTypes);
	}

	/**
	 * Validates namespace configuration for all configured strategies. Each strategy maps
	 * to its expected namespace patterns. AWS gives a strategy exactly one namespace, so
	 * configure one pattern per strategy; episodic reflection namespaces are validated
	 * separately (see {@link #validateNamespaces(String, Map, Map)}).
	 * @param memoryId the memory resource ID
	 * @param namespacesByStrategy map of strategy ID to its list of expected namespace
	 * patterns
	 * @throws AgentCoreMemoryException.ConfigurationException if any namespace doesn't
	 * match expected format
	 */
	public void validateNamespaces(String memoryId, Map<String, List<String>> namespacesByStrategy) {
		this.validateNamespaces(memoryId, namespacesByStrategy, Map.of());
	}

	/**
	 * Validates namespace configuration for all configured strategies, including the
	 * reflection namespaces of episodic strategies. Reflection namespaces live in the
	 * strategy's reflection configuration, not in its namespaces, so they are validated
	 * against those and are never auto-registered (registration writes the strategy's
	 * episode namespaces). With auto-registration, a mismatching pattern is registered
	 * only if it contains {@code {actorId}}, and only after every check has passed, so a
	 * failed validation never changes the memory.
	 * @param memoryId the memory resource ID
	 * @param namespacesByStrategy map of strategy ID to its list of expected namespace
	 * patterns
	 * @param reflectionNamespacesByStrategy map of episodic strategy ID to its expected
	 * reflection namespace pattern
	 * @throws AgentCoreMemoryException.ConfigurationException if any namespace doesn't
	 * match expected format
	 */
	public void validateNamespaces(String memoryId, Map<String, List<String>> namespacesByStrategy,
			Map<String, String> reflectionNamespacesByStrategy) {
		if (namespacesByStrategy.isEmpty() && reflectionNamespacesByStrategy.isEmpty()) {
			return;
		}

		logger.info("Validating namespace configuration for memory: {}", memoryId);

		GetMemoryResponse response = this.controlClient
			.getMemory(GetMemoryRequest.builder().memoryId(memoryId).build());

		List<MemoryStrategy> strategies = response.memory().strategies();
		if (strategies == null || strategies.isEmpty()) {
			throw new AgentCoreMemoryException.ConfigurationException(
					"Memory '" + memoryId + "' has no strategies configured. " + "LTM requires at least one strategy.");
		}

		List<Registration> registrations = new ArrayList<>();
		for (Map.Entry<String, List<String>> entry : namespacesByStrategy.entrySet()) {
			String strategyId = entry.getKey();
			List<String> expectedPatterns = entry.getValue();
			this.validateStrategy(memoryId, strategies, strategyId, expectedPatterns, registrations);
		}
		for (Map.Entry<String, String> entry : reflectionNamespacesByStrategy.entrySet()) {
			this.validateReflections(memoryId, strategies, entry.getKey(), entry.getValue());
		}
		// Registration writes to AWS, so it waits until every check above has passed.
		for (Registration registration : registrations) {
			this.registrar.registerNamespace(memoryId, registration.strategyId(), registration.namespacePattern());
		}

		logger.info("Namespace validation passed for {} strategies", namespacesByStrategy.size());
	}

	private void validateStrategy(String memoryId, List<MemoryStrategy> strategies, String strategyId,
			List<String> expectedPatterns, List<Registration> registrations) {
		MemoryStrategy strategy = findStrategy(memoryId, strategies, strategyId);
		this.requireConfiguredType(memoryId, strategy);

		List<String> actualNamespaces = AgentCoreLongTermMemoryStrategyDiscovery.strategyNamespaces(strategy);
		if (actualNamespaces.isEmpty()) {
			throw new AgentCoreMemoryException.ConfigurationException(
					"Strategy '" + strategyId + "' has no namespaces configured.");
		}

		// Every expected pattern must match at least one actual namespace.
		for (String expected : expectedPatterns) {
			requireFillablePlaceholders(strategyId, expected);
			boolean matched = actualNamespaces.stream()
				.anyMatch((actual) -> this.matchesPattern(actual, expected, strategyId));
			if (!matched) {
				// Registering a pattern without {actorId} would make all actors share
				// one namespace, so such a mismatch fails even with auto-registration.
				if (this.autoRegister && isSupported(expected)) {
					registrations.add(new Registration(strategyId, expected));
				}
				else {
					String message = this.buildErrorMessage(strategy, actualNamespaces, expected);
					throw new AgentCoreMemoryException.ConfigurationException(message + this.notRegisteredHint());
				}
			}
		}

		logger.debug("Strategy '{}' namespace validated: expected={}, actual={}", strategyId, expectedPatterns,
				actualNamespaces);
	}

	private void validateReflections(String memoryId, List<MemoryStrategy> strategies, String strategyId,
			String expectedPattern) {
		requireFillablePlaceholders(strategyId, expectedPattern);
		MemoryStrategy strategy = findStrategy(memoryId, strategies, strategyId);
		List<String> actualNamespaces = reflectionNamespaces(strategy);
		boolean matched = actualNamespaces.stream()
			.anyMatch((actual) -> this.matchesPattern(actual, expectedPattern, strategyId));
		if (!matched) {
			String header = String.format("Reflection namespace mismatch for episodic strategy '%s'.%n"
					+ "  In AWS Memory:    %s%n" + "  In Spring Config: %s%n%n", strategyId, actualNamespaces,
					expectedPattern);
			String supported = firstSupported(actualNamespaces);
			if (!actualNamespaces.isEmpty() && supported == null) {
				throw new AgentCoreMemoryException.ConfigurationException(
						header + unsupportedNamespaceHint("reflection namespace"));
			}
			throw new AgentCoreMemoryException.ConfigurationException(header + String.format(
					"Configure the reflection namespace of the memory in application.properties:%n"
							+ "  %s.reflections-namespace-pattern=%s%n"
							+ "Reflection namespaces are not registered automatically.",
					AgentCoreLongTermMemoryProperties.Episodic.CONFIG_PREFIX,
					(supported != null) ? supported : "<none configured in the memory>"));
		}
		logger.debug("Strategy '{}' reflection namespace validated: expected={}, actual={}", strategyId,
				expectedPattern, actualNamespaces);
	}

	private static MemoryStrategy findStrategy(String memoryId, List<MemoryStrategy> strategies, String strategyId) {
		return strategies.stream()
			.filter((s) -> strategyId.equals(s.strategyId()))
			.findFirst()
			.orElseThrow(() -> new AgentCoreMemoryException.ConfigurationException(
					"Strategy '" + strategyId + "' not found in memory '" + memoryId + "'. " + "Available strategies: "
							+ strategies.stream().map(MemoryStrategy::strategyId).toList()));
	}

	// Reflection namespaces live in episodicReflectionConfiguration, or in the
	// episodicReflectionOverride of a customReflectionConfiguration.
	private static List<String> reflectionNamespaces(MemoryStrategy strategy) {
		List<String> episodic = AgentCoreLongTermMemoryStrategyDiscovery.extractReflectionsNamespaces(strategy);
		if (!episodic.isEmpty()) {
			return episodic;
		}
		StrategyConfiguration configuration = strategy.configuration();
		ReflectionConfiguration reflection = (configuration != null) ? configuration.reflection() : null;
		CustomReflectionConfiguration custom = (reflection != null) ? reflection.customReflectionConfiguration() : null;
		EpisodicReflectionOverride override = (custom != null) ? custom.episodicReflectionOverride() : null;
		return (override != null) ? AgentCoreLongTermMemoryStrategyDiscovery
			.templatesOrNamespaces(override.namespaceTemplates(), override.namespaces()) : List.of();
	}

	// Placeholders are filled at retrieval time, so a placeholder only matches the same
	// placeholder: a swapped {actorId}/{sessionId} would read another scope's records.
	// A literal in AWS matches a placeholder only when it is this strategy's resolved id.
	private boolean matchesPattern(String actual, String expected, String strategyId) {
		String[] actualParts = actual.split("/");
		String[] expectedParts = expected.split("/");

		if (actualParts.length != expectedParts.length) {
			return false;
		}

		for (int i = 0; i < expectedParts.length; i++) {
			String expectedPart = expectedParts[i];
			String actualPart = actualParts[i];
			if (!this.isPlaceholder(expectedPart) || this.isPlaceholder(actualPart)) {
				if (!expectedPart.equals(actualPart)) {
					return false;
				}
			}
			else if (!STRATEGY_ID_PLACEHOLDER.equals(expectedPart) || !actualPart.equals(strategyId)) {
				return false;
			}
		}
		return true;
	}

	private boolean isPlaceholder(String segment) {
		return segment.startsWith("{") && segment.endsWith("}");
	}

	private String buildErrorMessage(MemoryStrategy strategy, List<String> actual, String expected) {
		String header = String.format(
				"Namespace mismatch for strategy '%s'.%n" + "  In AWS Memory:    %s%n" + "  In Spring Config: %s%n%n",
				strategy.strategyId(), actual, expected);
		String supported = firstSupported(actual);
		if (supported == null) {
			return header + unsupportedNamespaceHint("namespace");
		}
		return header + String.format(
				"The memory was created with a different namespace format than configured in Spring.%n"
						+ "Either update the memory namespace or configure the matching pattern in "
						+ "application.properties:%n  %s.namespace-pattern=%s",
				this.configPrefixFor(strategy), supported);
	}

	// A strategy ID under another group's property gets that group's handler, and the
	// mismatch hint would name a property that has no effect, so name the right group.
	private void requireConfiguredType(String memoryId, MemoryStrategy strategy) {
		AgentCoreLongTermMemoryStrategyType configured = this.configuredTypes.get(strategy.strategyId());
		AgentCoreLongTermMemoryStrategyType actual = effectiveType(strategy);
		// SELF_MANAGED and unknown types can back any group
		if (configured != null && actual != null && configured != actual) {
			throw new AgentCoreMemoryException.ConfigurationException(String.format(
					"Strategy '%s' is a %s strategy in memory '%s', but it is configured as %s.strategy-id. "
							+ "Configure it as %s.strategy-id instead.",
					strategy.strategyId(), describeType(strategy), memoryId, configPrefix(configured),
					configPrefix(actual)));
		}
	}

	// Prefer the group the ID is configured under: that is the property the user can set
	private String configPrefixFor(MemoryStrategy strategy) {
		AgentCoreLongTermMemoryStrategyType configured = this.configuredTypes.get(strategy.strategyId());
		return (configured != null) ? configPrefix(configured) : configPrefix(strategy);
	}

	// The property prefix of the strategy's config, derived from its AWS type, or from
	// the override type of a CUSTOM strategy
	private static String configPrefix(MemoryStrategy strategy) {
		return configPrefix(effectiveType(strategy));
	}

	// The property prefix of a strategy group; CUSTOM and unknown types have none
	static String configPrefix(AgentCoreLongTermMemoryStrategyType type) {
		if (type == null) {
			return AgentCoreLongTermMemoryProperties.CONFIG_PREFIX + ".<strategy>";
		}
		return switch (type) {
			case SEMANTIC -> AgentCoreLongTermMemoryProperties.Semantic.CONFIG_PREFIX;
			case USER_PREFERENCE -> AgentCoreLongTermMemoryProperties.UserPreference.CONFIG_PREFIX;
			case SUMMARY -> AgentCoreLongTermMemoryProperties.Summary.CONFIG_PREFIX;
			case EPISODIC -> AgentCoreLongTermMemoryProperties.Episodic.CONFIG_PREFIX;
			case CUSTOM -> AgentCoreLongTermMemoryProperties.CONFIG_PREFIX + ".<strategy>";
		};
	}

	// The group a strategy belongs to. A CUSTOM strategy overrides one built-in type;
	// SELF_MANAGED and unknown overrides belong to none.
	private static AgentCoreLongTermMemoryStrategyType effectiveType(MemoryStrategy strategy) {
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
	private static String describeType(MemoryStrategy strategy) {
		OverrideType override = overrideType(strategy);
		return (override != null) ? strategy.type() + " (" + override + ")" : String.valueOf(strategy.type());
	}

	// A memory namespace can be copied into the Spring config only if it is scoped by
	// actor and uses no placeholders that retrieval cannot fill.
	private static String firstSupported(List<String> namespaces) {
		return namespaces.stream()
			.filter(AgentCoreLongTermMemoryNamespaceValidator::isSupported)
			.findFirst()
			.orElse(null);
	}

	private static boolean isSupported(String namespace) {
		return AgentCoreLongTermMemoryNamespace.unfilledPlaceholder(namespace) == null
				&& namespace.contains("{actorId}");
	}

	private static String unsupportedNamespaceHint(String what) {
		return "The " + what + " of the memory cannot be used: it must contain {actorId} and may only use the "
				+ "placeholders " + AgentCoreLongTermMemoryNamespace.FILLED_PLACEHOLDERS + ". Change the " + what
				+ " of the memory strategy with the AWS console, CLI or your infrastructure tooling.";
	}

	// Why auto-registration skipped a mismatching pattern; empty when it is off.
	private String notRegisteredHint() {
		return (this.autoRegister) ? String.format("%n%nThe configured pattern was not registered automatically: it "
				+ "does not contain {actorId}, so all actors would share its records.") : "";
	}

	// Retrieval fills only the supported placeholders. Any other one, such as a custom
	// namespace key, would reach AWS as literal braces and fail every retrieval.
	private static void requireFillablePlaceholders(String strategyId, String pattern) {
		String unfilled = AgentCoreLongTermMemoryNamespace.unfilledPlaceholder(pattern);
		if (unfilled != null) {
			throw new AgentCoreMemoryException.ConfigurationException(String.format(
					"Namespace pattern '%s' for strategy '%s' uses the placeholder %s, which retrieval cannot "
							+ "fill. Supported placeholders: %s.",
					pattern, strategyId, unfilled, AgentCoreLongTermMemoryNamespace.FILLED_PLACEHOLDERS));
		}
	}

	private record Registration(String strategyId, String namespacePattern) {
	}

}
