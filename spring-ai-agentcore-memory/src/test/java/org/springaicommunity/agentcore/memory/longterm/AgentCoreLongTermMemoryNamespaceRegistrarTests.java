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

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AgentCoreLongTermMemoryNamespaceRegistrar}.
 *
 * @author Andrei Shakirin
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AgentCore Long-Term Memory Namespace Registrar Tests")
class AgentCoreLongTermMemoryNamespaceRegistrarTests {

	@Mock
	private BedrockAgentCoreControlClient controlClient;

	private AgentCoreLongTermMemoryNamespaceRegistrar registrar;

	@BeforeEach
	void setUp() {
		this.registrar = new AgentCoreLongTermMemoryNamespaceRegistrar(this.controlClient);
	}

	@Test
	@DisplayName("Should call updateMemory with correct parameters")
	void shouldCallUpdateMemoryWithCorrectParameters() {
		// Given
		when(this.controlClient.updateMemory(any(UpdateMemoryRequest.class)))
			.thenReturn(UpdateMemoryResponse.builder().build());

		String memoryId = "test-memory";
		String strategyId = "semantic-123";
		String namespacePattern = "/strategies/{memoryStrategyId}/actors/{actorId}";

		// When
		this.registrar.registerNamespace(memoryId, strategyId, namespacePattern);

		// Then
		verify(this.controlClient)
			.updateMemory(argThat((UpdateMemoryRequest request) -> request.memoryId().equals(memoryId)
					&& request.memoryStrategies().modifyMemoryStrategies().size() == 1
					&& request.memoryStrategies().modifyMemoryStrategies().get(0).memoryStrategyId().equals(strategyId)
					&& request.memoryStrategies()
						.modifyMemoryStrategies()
						.get(0)
						.namespaces()
						.contains(namespacePattern)));
	}

	@Test
	@DisplayName("Should throw ConfigurationException when updateMemory fails")
	void shouldThrowConfigurationExceptionOnFailure() {
		// Given
		when(this.controlClient.updateMemory(any(UpdateMemoryRequest.class)))
			.thenThrow(new RuntimeException("API error"));

		// When/Then
		assertThatThrownBy(() -> this.registrar.registerNamespace("test-memory", "semantic-123", "/some/pattern"))
			.isInstanceOf(AgentCoreMemoryException.ConfigurationException.class)
			.hasMessageContaining("Failed to register namespace")
			.hasMessageContaining("semantic-123")
			.hasMessageContaining("API error");
	}

}
