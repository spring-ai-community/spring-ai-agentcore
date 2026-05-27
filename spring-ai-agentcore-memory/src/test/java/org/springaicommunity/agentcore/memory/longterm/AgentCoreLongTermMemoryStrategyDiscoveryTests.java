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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springaicommunity.agentcore.memory.longterm.AgentCoreLongTermMemoryStrategyDiscovery.DiscoveredStrategy;
import software.amazon.awssdk.services.bedrockagentcorecontrol.BedrockAgentCoreControlClient;
import software.amazon.awssdk.services.bedrockagentcorecontrol.model.CustomReflectionConfiguration;
import software.amazon.awssdk.services.bedrockagentcorecontrol.model.EpisodicReflectionConfiguration;
import software.amazon.awssdk.services.bedrockagentcorecontrol.model.GetMemoryRequest;
import software.amazon.awssdk.services.bedrockagentcorecontrol.model.GetMemoryResponse;
import software.amazon.awssdk.services.bedrockagentcorecontrol.model.Memory;
import software.amazon.awssdk.services.bedrockagentcorecontrol.model.MemoryStrategy;
import software.amazon.awssdk.services.bedrockagentcorecontrol.model.MemoryStrategyType;
import software.amazon.awssdk.services.bedrockagentcorecontrol.model.ReflectionConfiguration;
import software.amazon.awssdk.services.bedrockagentcorecontrol.model.StrategyConfiguration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

/**
 * Tests for {@link AgentCoreLongTermMemoryStrategyDiscovery}. Focus on how reflection
 * namespaces are extracted from the AWS strategy configuration.
 */
@ExtendWith(MockitoExtension.class)
class AgentCoreLongTermMemoryStrategyDiscoveryTests {

	@Mock
	BedrockAgentCoreControlClient controlClient;

	@Test
	void shouldExtractReflectionsNamespacesForEpisodicStrategy() {
		MemoryStrategy strategy = MemoryStrategy.builder()
			.strategyId("ep-1")
			.type(MemoryStrategyType.EPISODIC)
			.namespaces(List.of("/strategy/{memoryStrategyId}/actor/{actorId}/"))
			.configuration(StrategyConfiguration.builder()
				.reflection(ReflectionConfiguration.builder()
					.episodicReflectionConfiguration(EpisodicReflectionConfiguration.builder()
						.namespaces("/strategy/{memoryStrategyId}/")
						.build())
					.build())
				.build())
			.build();

		this.mockGetMemory(List.of(strategy));

		List<DiscoveredStrategy> discovered = new AgentCoreLongTermMemoryStrategyDiscovery(this.controlClient)
			.discoverStrategies("mem-1");

		assertThat(discovered).hasSize(1);
		DiscoveredStrategy ds = discovered.get(0);
		assertThat(ds.strategyId()).isEqualTo("ep-1");
		assertThat(ds.type()).isEqualTo(AgentCoreLongTermMemoryStrategyType.EPISODIC);
		assertThat(ds.namespaces()).containsExactly("/strategy/{memoryStrategyId}/actor/{actorId}/");
		assertThat(ds.reflectionsNamespaces()).containsExactly("/strategy/{memoryStrategyId}/");
		assertThat(ds.defaultReflectionsNamespace()).isEqualTo("/strategy/{memoryStrategyId}/");
	}

	@Test
	void shouldReturnEmptyReflectionsForEpisodicWithoutReflectionConfig() {
		MemoryStrategy strategy = MemoryStrategy.builder()
			.strategyId("ep-2")
			.type(MemoryStrategyType.EPISODIC)
			.namespaces(List.of("/strategy/{memoryStrategyId}/actor/{actorId}/"))
			// no configuration() — null StrategyConfiguration
			.build();

		this.mockGetMemory(List.of(strategy));

		DiscoveredStrategy ds = new AgentCoreLongTermMemoryStrategyDiscovery(this.controlClient)
			.discoverStrategies("mem-1")
			.get(0);

		assertThat(ds.reflectionsNamespaces()).isEmpty();
		assertThat(ds.defaultReflectionsNamespace()).isNull();
	}

	@Test
	void shouldSkipCustomReflectionConfigurationForNow() {
		// Strategy has a CustomReflectionConfiguration rather than an
		// EpisodicReflectionConfiguration. Behaviour: log and skip;
		// reflectionsNamespaces empty.
		MemoryStrategy strategy = MemoryStrategy.builder()
			.strategyId("ep-3")
			.type(MemoryStrategyType.EPISODIC)
			.namespaces(List.of("/strategy/{memoryStrategyId}/actor/{actorId}/"))
			.configuration(StrategyConfiguration.builder()
				.reflection(ReflectionConfiguration.builder()
					.customReflectionConfiguration(CustomReflectionConfiguration.builder().build())
					.build())
				.build())
			.build();

		this.mockGetMemory(List.of(strategy));

		DiscoveredStrategy ds = new AgentCoreLongTermMemoryStrategyDiscovery(this.controlClient)
			.discoverStrategies("mem-1")
			.get(0);

		assertThat(ds.reflectionsNamespaces()).isEmpty();
	}

	@Test
	void shouldReturnEmptyReflectionsForNonEpisodicStrategies() {
		// Even if somehow a reflection config is set on a non-episodic strategy, we only
		// extract reflections for EPISODIC. This keeps the behaviour consistent with
		// AWS's model.
		MemoryStrategy strategy = MemoryStrategy.builder()
			.strategyId("sem-1")
			.type(MemoryStrategyType.SEMANTIC)
			.namespaces(List.of("/strategy/{memoryStrategyId}/actor/{actorId}/"))
			.build();

		this.mockGetMemory(List.of(strategy));

		DiscoveredStrategy ds = new AgentCoreLongTermMemoryStrategyDiscovery(this.controlClient)
			.discoverStrategies("mem-1")
			.get(0);

		assertThat(ds.type()).isEqualTo(AgentCoreLongTermMemoryStrategyType.SEMANTIC);
		assertThat(ds.reflectionsNamespaces()).isEmpty();
	}

	private void mockGetMemory(List<MemoryStrategy> strategies) {
		Memory memory = Memory.builder().strategies(strategies).build();
		GetMemoryResponse response = GetMemoryResponse.builder().memory(memory).build();
		given(this.controlClient.getMemory(any(GetMemoryRequest.class))).willReturn(response);
	}

}
