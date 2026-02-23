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

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import software.amazon.awssdk.services.bedrockagentcorecontrol.BedrockAgentCoreControlClient;
import software.amazon.awssdk.services.bedrockagentcorecontrol.model.GetMemoryRequest;
import software.amazon.awssdk.services.bedrockagentcorecontrol.model.MemoryStrategy;
import software.amazon.awssdk.services.bedrockagentcorecontrol.model.MemoryStrategyType;

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

		var response = controlClient.getMemory(GetMemoryRequest.builder().memoryId(memoryId).build());
		var strategies = response.memory().strategies();

		if (strategies == null || strategies.isEmpty()) {
			logger.warn("No strategies found in memory: {}", memoryId);
			return List.of();
		}

		List<DiscoveredStrategy> discovered = new ArrayList<>();
		for (MemoryStrategy strategy : strategies) {
			var mapped = mapStrategy(strategy);
			if (mapped != null) {
				discovered.add(mapped);
				logger.info("Discovered strategy: {} (type={}, namespaces={})", mapped.strategyId(), mapped.type(),
						mapped.namespaces());
			}
		}

		logger.info("Discovered {} strategies for memory: {}", discovered.size(), memoryId);
		return discovered;
	}

	private DiscoveredStrategy mapStrategy(MemoryStrategy strategy) {
		MemoryStrategyType sdkType = strategy.type();
		if (sdkType == null) {
			logger.warn("Strategy '{}' has no type, skipping", strategy.strategyId());
			return null;
		}

		StrategyType type = mapType(sdkType);
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

		return new DiscoveredStrategy(strategy.strategyId(), type, List.copyOf(namespaces));
	}

	private StrategyType mapType(MemoryStrategyType sdkType) {
		return switch (sdkType) {
			case SEMANTIC -> StrategyType.SEMANTIC;
			case SUMMARIZATION -> StrategyType.SUMMARY;
			case USER_PREFERENCE -> StrategyType.USER_PREFERENCE;
			case EPISODIC -> StrategyType.EPISODIC;
			case CUSTOM -> null; // Skip custom strategies
			case UNKNOWN_TO_SDK_VERSION -> null;
		};
	}

	/**
	 * Represents a discovered memory strategy.
	 */
	public record DiscoveredStrategy(String strategyId, StrategyType type, List<String> namespaces) {

		/**
		 * Returns the first namespace (default).
		 */
		public String defaultNamespace() {
			return namespaces.get(0);
		}

	}

	/**
	 * Strategy types supported by autodiscovery.
	 */
	public enum StrategyType {

		SEMANTIC, SUMMARY, USER_PREFERENCE, EPISODIC

	}

}
