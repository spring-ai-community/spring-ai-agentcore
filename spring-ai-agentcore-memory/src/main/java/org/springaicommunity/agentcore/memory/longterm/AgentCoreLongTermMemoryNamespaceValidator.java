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
	 * Validates namespace configuration for all configured strategies.
	 * @param memoryId the memory resource ID
	 * @param strategyConfigs map of strategy ID to its expected namespace pattern
	 * @throws AgentCoreMemoryException.ConfigurationException if any namespace doesn't
	 * match expected format
	 */
	public void validateNamespaces(String memoryId, Map<String, String> strategyConfigs) {
		if (strategyConfigs.isEmpty()) {
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

		for (Map.Entry<String, String> entry : strategyConfigs.entrySet()) {
			String strategyId = entry.getKey();
			String namespacePattern = entry.getValue();
			validateStrategy(memoryId, strategies, strategyId, namespacePattern);
		}

		logger.info("Namespace validation passed for {} strategies", strategyConfigs.size());
	}

	private void validateStrategy(String memoryId, List<MemoryStrategy> strategies, String strategyId,
			String namespacePattern) {
		MemoryStrategy strategy = strategies.stream()
			.filter(s -> strategyId.equals(s.strategyId()))
			.findFirst()
			.orElseThrow(() -> new AgentCoreMemoryException.ConfigurationException(
					"Strategy '" + strategyId + "' not found in memory '" + memoryId + "'. " + "Available strategies: "
							+ strategies.stream().map(MemoryStrategy::strategyId).toList()));

		List<String> namespaces = strategy.namespaces();
		if (namespaces == null || namespaces.isEmpty()) {
			throw new AgentCoreMemoryException.ConfigurationException(
					"Strategy '" + strategyId + "' has no namespaces configured.");
		}

		String actualNamespace = namespaces.get(0);

		if (!matchesPattern(actualNamespace, namespacePattern)) {
			if (this.autoRegister) {
				this.registrar.registerNamespace(memoryId, strategyId, namespacePattern);
			}
			else {
				throw new AgentCoreMemoryException.ConfigurationException(
						buildErrorMessage(strategyId, actualNamespace, namespacePattern));
			}
		}

		logger.debug("Strategy '{}' namespace validated: {}", strategyId, actualNamespace);
	}

	private boolean matchesPattern(String actual, String expected) {
		String[] actualParts = actual.split("/");
		String[] expectedParts = expected.split("/");

		if (actualParts.length != expectedParts.length) {
			return false;
		}

		for (int i = 0; i < expectedParts.length; i++) {
			String expectedPart = expectedParts[i];
			if (!isPlaceholder(expectedPart) && !expectedPart.equals(actualParts[i])) {
				return false;
			}
		}
		return true;
	}

	private boolean isPlaceholder(String segment) {
		return segment.startsWith("{") && segment.endsWith("}");
	}

	private String buildErrorMessage(String strategyId, String actual, String expected) {
		return String.format("Namespace mismatch for strategy '%s'.%n" + "  In AWS Memory:    %s%n"
				+ "  In Spring Config: %s%n%n"
				+ "The memory was created with a different namespace format than configured in Spring.%n"
				+ "Either update the memory namespace or configure the matching pattern in application.properties:%n"
				+ "  agentcore.memory.long-term.<strategy>.namespace-pattern=%s", strategyId, actual, expected, actual);
	}

}
