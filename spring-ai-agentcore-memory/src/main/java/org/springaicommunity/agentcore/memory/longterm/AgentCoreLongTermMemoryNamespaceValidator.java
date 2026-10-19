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

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springaicommunity.agentcore.memory.AgentCoreMemoryException;
import software.amazon.awssdk.services.bedrockagentcorecontrol.BedrockAgentCoreControlClient;
import software.amazon.awssdk.services.bedrockagentcorecontrol.model.GetMemoryRequest;
import software.amazon.awssdk.services.bedrockagentcorecontrol.model.GetMemoryResponse;
import software.amazon.awssdk.services.bedrockagentcorecontrol.model.MemoryStrategy;

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

	private static final Pattern PLACEHOLDER = Pattern.compile("\\{[^}]*}");

	private static final List<String> SUPPORTED_PLACEHOLDERS = List.of("{memoryStrategyId}", "{actorId}",
			"{sessionId}");

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
	 * Validates namespace configuration for all configured strategies. Each strategy may
	 * map to one or more expected namespace patterns (e.g. episodic strategies with
	 * reflections have two namespaces under the same strategy id).
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
	 * episode namespaces).
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

		for (Map.Entry<String, List<String>> entry : namespacesByStrategy.entrySet()) {
			String strategyId = entry.getKey();
			List<String> expectedPatterns = entry.getValue();
			this.validateStrategy(memoryId, strategies, strategyId, expectedPatterns);
		}
		for (Map.Entry<String, String> entry : reflectionNamespacesByStrategy.entrySet()) {
			this.validateReflections(memoryId, strategies, entry.getKey(), entry.getValue());
		}

		logger.info("Namespace validation passed for {} strategies", namespacesByStrategy.size());
	}

	private void validateStrategy(String memoryId, List<MemoryStrategy> strategies, String strategyId,
			List<String> expectedPatterns) {
		MemoryStrategy strategy = findStrategy(memoryId, strategies, strategyId);
		this.requireConfiguredType(memoryId, strategy);

		List<String> actualNamespaces = strategy.namespaces();
		if (actualNamespaces == null || actualNamespaces.isEmpty()) {
			throw new AgentCoreMemoryException.ConfigurationException(
					"Strategy '" + strategyId + "' has no namespaces configured.");
		}

		// Every expected pattern must match at least one actual namespace.
		for (String expected : expectedPatterns) {
			boolean matched = actualNamespaces.stream().anyMatch((actual) -> this.matchesPattern(actual, expected));
			if (!matched) {
				if (this.autoRegister) {
					this.registrar.registerNamespace(memoryId, strategyId, expected);
				}
				else {
					throw new AgentCoreMemoryException.ConfigurationException(
							this.buildErrorMessage(strategy, actualNamespaces, expected));
				}
			}
		}

		logger.debug("Strategy '{}' namespace validated: expected={}, actual={}", strategyId, expectedPatterns,
				actualNamespaces);
	}

	private void validateReflections(String memoryId, List<MemoryStrategy> strategies, String strategyId,
			String expectedPattern) {
		MemoryStrategy strategy = findStrategy(memoryId, strategies, strategyId);
		List<String> actualNamespaces = AgentCoreLongTermMemoryStrategyDiscovery.extractReflectionsNamespaces(strategy);
		boolean matched = actualNamespaces.stream().anyMatch((actual) -> this.matchesPattern(actual, expectedPattern));
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

	private boolean matchesPattern(String actual, String expected) {
		String[] actualParts = actual.split("/");
		String[] expectedParts = expected.split("/");

		if (actualParts.length != expectedParts.length) {
			return false;
		}

		for (int i = 0; i < expectedParts.length; i++) {
			String expectedPart = expectedParts[i];
			if (!this.isPlaceholder(expectedPart) && !expectedPart.equals(actualParts[i])) {
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
		AgentCoreLongTermMemoryStrategyType actual = AgentCoreLongTermMemoryStrategyType.fromAwsType(strategy.type());
		// CUSTOM and unknown types can back any group
		if (configured != null && actual != null && configured != actual) {
			throw new AgentCoreMemoryException.ConfigurationException(String.format(
					"Strategy '%s' is a %s strategy in memory '%s', but it is configured as %s.strategy-id. "
							+ "Configure it as %s.strategy-id instead.",
					strategy.strategyId(), strategy.type(), memoryId, configPrefix(configured), configPrefix(actual)));
		}
	}

	// Prefer the group the ID is configured under: that is the property the user can set
	private String configPrefixFor(MemoryStrategy strategy) {
		AgentCoreLongTermMemoryStrategyType configured = this.configuredTypes.get(strategy.strategyId());
		return (configured != null) ? configPrefix(configured) : configPrefix(strategy);
	}

	// The property prefix of the strategy's config, derived from its AWS type
	private static String configPrefix(MemoryStrategy strategy) {
		return configPrefix(AgentCoreLongTermMemoryStrategyType.fromAwsType(strategy.type()));
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

	// A memory namespace can be copied into the Spring config only if it is scoped by
	// actor and uses no placeholders that retrieval cannot fill.
	private static String firstSupported(List<String> namespaces) {
		return namespaces.stream()
			.filter(AgentCoreLongTermMemoryNamespaceValidator::isSupported)
			.findFirst()
			.orElse(null);
	}

	private static boolean isSupported(String namespace) {
		Matcher matcher = PLACEHOLDER.matcher(namespace);
		boolean hasActor = false;
		while (matcher.find()) {
			String placeholder = matcher.group();
			if (!SUPPORTED_PLACEHOLDERS.contains(placeholder)) {
				return false;
			}
			hasActor |= "{actorId}".equals(placeholder);
		}
		return hasActor;
	}

	private static String unsupportedNamespaceHint(String what) {
		return "The " + what + " of the memory cannot be used: it must contain {actorId} and may only use the "
				+ "placeholders " + SUPPORTED_PLACEHOLDERS + ". Change the " + what
				+ " of the memory strategy with the AWS console, CLI or your infrastructure tooling.";
	}

}
