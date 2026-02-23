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

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.springaicommunity.agentcore.memory.AgentCoreMemoryException;
import software.amazon.awssdk.services.bedrockagentcorecontrol.BedrockAgentCoreControlClient;
import software.amazon.awssdk.services.bedrockagentcorecontrol.model.UpdateMemoryRequest;
import software.amazon.awssdk.services.bedrockagentcorecontrol.model.UpdateMemoryResponse;

/**
 * Unit tests for {@link AgentCoreLongTermMemoryNamespaceRegistrar}.
 *
 * @author Andrei Shakirin
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AgentCore Long-Term Memory Namespace Registrar Tests")
class AgentCoreLongTermMemoryNamespaceRegistrarTest {

	@Mock
	private BedrockAgentCoreControlClient controlClient;

	private AgentCoreLongTermMemoryNamespaceRegistrar registrar;

	@BeforeEach
	void setUp() {
		registrar = new AgentCoreLongTermMemoryNamespaceRegistrar(controlClient);
	}

	@Test
	@DisplayName("Should call updateMemory with correct parameters")
	void shouldCallUpdateMemoryWithCorrectParameters() {
		// Given
		when(controlClient.updateMemory(any(UpdateMemoryRequest.class)))
			.thenReturn(UpdateMemoryResponse.builder().build());

		String memoryId = "test-memory";
		String strategyId = "semantic-123";
		String namespacePattern = "/strategies/{memoryStrategyId}/actors/{actorId}";

		// When
		registrar.registerNamespace(memoryId, strategyId, namespacePattern);

		// Then
		verify(controlClient).updateMemory(argThat((UpdateMemoryRequest request) -> request.memoryId().equals(memoryId)
				&& request.memoryStrategies().modifyMemoryStrategies().size() == 1
				&& request.memoryStrategies().modifyMemoryStrategies().get(0).memoryStrategyId().equals(strategyId)
				&& request.memoryStrategies().modifyMemoryStrategies().get(0).namespaces().contains(namespacePattern)));
	}

	@Test
	@DisplayName("Should throw ConfigurationException when updateMemory fails")
	void shouldThrowConfigurationExceptionOnFailure() {
		// Given
		when(controlClient.updateMemory(any(UpdateMemoryRequest.class))).thenThrow(new RuntimeException("API error"));

		// When/Then
		assertThatThrownBy(() -> registrar.registerNamespace("test-memory", "semantic-123", "/some/pattern"))
			.isInstanceOf(AgentCoreMemoryException.ConfigurationException.class)
			.hasMessageContaining("Failed to register namespace")
			.hasMessageContaining("semantic-123")
			.hasMessageContaining("API error");
	}

}
