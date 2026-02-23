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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springaicommunity.agentcore.memory.AgentCoreMemoryException;
import software.amazon.awssdk.services.bedrockagentcorecontrol.BedrockAgentCoreControlClient;
import software.amazon.awssdk.services.bedrockagentcorecontrol.model.ModifyMemoryStrategies;
import software.amazon.awssdk.services.bedrockagentcorecontrol.model.ModifyMemoryStrategyInput;
import software.amazon.awssdk.services.bedrockagentcorecontrol.model.UpdateMemoryRequest;

/**
 * Registers namespaces for memory strategies in AgentCore Memory.
 *
 * @author Andrei Shakirin
 */
public class AgentCoreLongTermMemoryNamespaceRegistrar {

	private static final Logger logger = LoggerFactory.getLogger(AgentCoreLongTermMemoryNamespaceRegistrar.class);

	private final BedrockAgentCoreControlClient controlClient;

	public AgentCoreLongTermMemoryNamespaceRegistrar(BedrockAgentCoreControlClient controlClient) {
		this.controlClient = controlClient;
	}

	public void registerNamespace(String memoryId, String strategyId, String namespacePattern) {
		logger.info("Registering namespace '{}' for strategy '{}'", namespacePattern, strategyId);
		try {
			this.controlClient.updateMemory(UpdateMemoryRequest.builder()
				.memoryId(memoryId)
				.memoryStrategies(ModifyMemoryStrategies.builder()
					.modifyMemoryStrategies(ModifyMemoryStrategyInput.builder()
						.memoryStrategyId(strategyId)
						.namespaces(List.of(namespacePattern))
						.build())
					.build())
				.build());
			logger.info("Namespace '{}' registered successfully", namespacePattern);
		}
		catch (Exception e) {
			throw new AgentCoreMemoryException.ConfigurationException("Failed to register namespace '"
					+ namespacePattern + "' for strategy '" + strategyId + "': " + e.getMessage());
		}
	}

}
