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

	private final BedrockAgentCoreControlClient controlClient;

	private final AgentCoreLongTermMemoryNamespaceRegistrar registrar;

	private final boolean autoRegister;

	public AgentCoreLongTermMemoryNamespaceValidator(BedrockAgentCoreControlClient controlClient,
			AgentCoreLongTermMemoryNamespaceRegistrar registrar, boolean autoRegister) {
		this.controlClient = controlClient;
		this.registrar = registrar;
		this.autoRegister = autoRegister;
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
		if (namespacesByStrategy.isEmpty()) {
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

		logger.info("Namespace validation passed for {} strategies", namespacesByStrategy.size());
	}

	private void validateStrategy(String memoryId, List<MemoryStrategy> strategies, String strategyId,
			List<String> expectedPatterns) {
		MemoryStrategy strategy = strategies.stream()
			.filter((s) -> strategyId.equals(s.strategyId()))
			.findFirst()
			.orElseThrow(() -> new AgentCoreMemoryException.ConfigurationException(
					"Strategy '" + strategyId + "' not found in memory '" + memoryId + "'. " + "Available strategies: "
							+ strategies.stream().map(MemoryStrategy::strategyId).toList()));

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
							this.buildErrorMessage(strategyId, actualNamespaces, expected));
				}
			}
		}

		logger.debug("Strategy '{}' namespace validated: expected={}, actual={}", strategyId, expectedPatterns,
				actualNamespaces);
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

	private String buildErrorMessage(String strategyId, List<String> actual, String expected) {
		return String.format("Namespace mismatch for strategy '%s'.%n" + "  In AWS Memory:    %s%n"
				+ "  In Spring Config: %s%n%n"
				+ "The memory was created with a different namespace format than configured in Spring.%n"
				+ "Either update the memory namespace or configure the matching pattern in application.properties:%n"
				+ "  agentcore.memory.long-term.<strategy>.namespace-pattern=%s", strategyId, actual, expected,
				(actual.isEmpty()) ? expected : actual.get(0));
	}

}
