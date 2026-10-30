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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springaicommunity.agentcore.memory.longterm.AgentCoreLongTermMemoryStrategyDiscovery.DiscoveredStrategy;
import software.amazon.awssdk.services.bedrockagentcorecontrol.BedrockAgentCoreControlClient;
import software.amazon.awssdk.services.bedrockagentcorecontrol.model.CustomReflectionConfiguration;
import software.amazon.awssdk.services.bedrockagentcorecontrol.model.EpisodicReflectionConfiguration;
import software.amazon.awssdk.services.bedrockagentcorecontrol.model.EpisodicReflectionOverride;
import software.amazon.awssdk.services.bedrockagentcorecontrol.model.GetMemoryRequest;
import software.amazon.awssdk.services.bedrockagentcorecontrol.model.GetMemoryResponse;
import software.amazon.awssdk.services.bedrockagentcorecontrol.model.Memory;
import software.amazon.awssdk.services.bedrockagentcorecontrol.model.MemoryStrategy;
import software.amazon.awssdk.services.bedrockagentcorecontrol.model.MemoryStrategyType;
import software.amazon.awssdk.services.bedrockagentcorecontrol.model.OverrideType;
import software.amazon.awssdk.services.bedrockagentcorecontrol.model.ReflectionConfiguration;
import software.amazon.awssdk.services.bedrockagentcorecontrol.model.StrategyConfiguration;

import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

/**
 * Tests for {@link AgentCoreLongTermMemoryStrategyDiscovery}. Focus on how strategy types
 * and reflection namespaces are read from the AWS strategy configuration.
 */
@ExtendWith({ MockitoExtension.class, OutputCaptureExtension.class })
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
	void shouldFallBackToReflectionNamespaceTemplates() {
		// AWS marks namespaces as legacy; a memory may only report namespaceTemplates.
		MemoryStrategy strategy = MemoryStrategy.builder()
			.strategyId("ep-4")
			.type(MemoryStrategyType.EPISODIC)
			.namespaces(List.of("/strategy/{memoryStrategyId}/actor/{actorId}/"))
			.configuration(StrategyConfiguration.builder()
				.reflection(ReflectionConfiguration.builder()
					.episodicReflectionConfiguration(EpisodicReflectionConfiguration.builder()
						.namespaceTemplates("/strategy/{memoryStrategyId}/")
						.build())
					.build())
				.build())
			.build();

		this.mockGetMemory(List.of(strategy));

		DiscoveredStrategy ds = new AgentCoreLongTermMemoryStrategyDiscovery(this.controlClient)
			.discoverStrategies("mem-1")
			.get(0);

		assertThat(ds.reflectionsNamespaces()).containsExactly("/strategy/{memoryStrategyId}/");
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
	void shouldExtractReflectionsNamespacesOfCustomEpisodicOverride() {
		// A CUSTOM strategy that overrides the episodic type keeps its reflection
		// namespaces in the episodicReflectionOverride of a customReflectionConfiguration
		MemoryStrategy strategy = MemoryStrategy.builder()
			.strategyId("ep-3")
			.type(MemoryStrategyType.CUSTOM)
			.namespaces(List.of("/strategy/{memoryStrategyId}/actor/{actorId}/session/{sessionId}/"))
			.configuration(StrategyConfiguration.builder()
				.type(OverrideType.EPISODIC_OVERRIDE)
				.reflection(ReflectionConfiguration.builder()
					.customReflectionConfiguration(CustomReflectionConfiguration.builder()
						.episodicReflectionOverride(EpisodicReflectionOverride.builder()
							.namespaceTemplates("/strategy/{memoryStrategyId}/actor/{actorId}/")
							.build())
						.build())
					.build())
				.build())
			.build();

		this.mockGetMemory(List.of(strategy));

		DiscoveredStrategy ds = new AgentCoreLongTermMemoryStrategyDiscovery(this.controlClient)
			.discoverStrategies("mem-1")
			.get(0);

		assertThat(ds.type()).isEqualTo(AgentCoreLongTermMemoryStrategyType.EPISODIC);
		assertThat(ds.reflectionsNamespaces()).containsExactly("/strategy/{memoryStrategyId}/actor/{actorId}/");
	}

	@Test
	void shouldReturnEmptyReflectionsForCustomReflectionConfigurationWithoutOverride() {
		MemoryStrategy strategy = MemoryStrategy.builder()
			.strategyId("ep-5")
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

	@ParameterizedTest
	@CsvSource({ "SEMANTIC_OVERRIDE, SEMANTIC", "SUMMARY_OVERRIDE, SUMMARY",
			"USER_PREFERENCE_OVERRIDE, USER_PREFERENCE", "EPISODIC_OVERRIDE, EPISODIC" })
	void shouldDiscoverCustomStrategyAsTheTypeItOverrides(OverrideType override,
			AgentCoreLongTermMemoryStrategyType expected) {
		MemoryStrategy strategy = MemoryStrategy.builder()
			.strategyId("custom-1")
			.type(MemoryStrategyType.CUSTOM)
			.namespaces(List.of("/strategy/{memoryStrategyId}/actor/{actorId}/"))
			.configuration(StrategyConfiguration.builder().type(override).build())
			.build();

		this.mockGetMemory(List.of(strategy));

		List<DiscoveredStrategy> discovered = new AgentCoreLongTermMemoryStrategyDiscovery(this.controlClient)
			.discoverStrategies("mem-1");

		assertThat(discovered).singleElement().satisfies((ds) -> {
			assertThat(ds.strategyId()).isEqualTo("custom-1");
			assertThat(ds.type()).isEqualTo(expected);
			assertThat(ds.namespaces()).containsExactly("/strategy/{memoryStrategyId}/actor/{actorId}/");
		});
	}

	@Test
	void shouldShowTheOverrideTypeOfCustomStrategiesInTheDiscoveryLog(CapturedOutput output) {
		MemoryStrategy custom = MemoryStrategy.builder()
			.strategyId("custom-sum")
			.type(MemoryStrategyType.CUSTOM)
			.namespaces(List.of("/strategy/{memoryStrategyId}/actor/{actorId}/session/{sessionId}/"))
			.configuration(StrategyConfiguration.builder().type(OverrideType.SUMMARY_OVERRIDE).build())
			.build();
		MemoryStrategy builtIn = MemoryStrategy.builder()
			.strategyId("sem-1")
			.type(MemoryStrategyType.SEMANTIC)
			.namespaces(List.of("/strategy/{memoryStrategyId}/actor/{actorId}/"))
			.build();

		this.mockGetMemory(List.of(custom, builtIn));

		new AgentCoreLongTermMemoryStrategyDiscovery(this.controlClient).discoverStrategies("mem-1");

		assertThat(output).contains("Discovered strategy: custom-sum (type=CUSTOM (SUMMARY_OVERRIDE),")
			.containsPattern("CUSTOM [(]SUMMARY_OVERRIDE[)] +custom-sum +no")
			.containsPattern("SEMANTIC +sem-1 +no");
	}

	@Test
	void shouldSkipSelfManagedAndUntypedCustomStrategies(CapturedOutput output) {
		// Neither has a built-in type whose retrieval fits its records
		MemoryStrategy selfManaged = MemoryStrategy.builder()
			.strategyId("self-1")
			.type(MemoryStrategyType.CUSTOM)
			.namespaces(List.of("/strategy/{memoryStrategyId}/actor/{actorId}/"))
			.configuration(StrategyConfiguration.builder().type(OverrideType.SELF_MANAGED).build())
			.build();
		MemoryStrategy untyped = MemoryStrategy.builder()
			.strategyId("custom-2")
			.type(MemoryStrategyType.CUSTOM)
			.namespaces(List.of("/strategy/{memoryStrategyId}/actor/{actorId}/"))
			.build();

		this.mockGetMemory(List.of(selfManaged, untyped));

		List<DiscoveredStrategy> discovered = new AgentCoreLongTermMemoryStrategyDiscovery(this.controlClient)
			.discoverStrategies("mem-1");

		assertThat(discovered).isEmpty();
		assertThat(output)
			.contains("Unknown or unsupported strategy type 'CUSTOM (SELF_MANAGED)' for strategy 'self-1', skipping")
			.contains("Unknown or unsupported strategy type 'CUSTOM' for strategy 'custom-2', skipping");
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

	@Test
	void shouldSkipStrategyWhoseNamespaceHasAPlaceholderRetrievalCannotFill() {
		// A custom namespace key such as {orgname} would reach AgentCore as literal
		// braces
		MemoryStrategy custom = MemoryStrategy.builder()
			.strategyId("sem-org")
			.type(MemoryStrategyType.SEMANTIC)
			.namespaces(List.of("/orgs/{orgname}/actors/{actorId}/"))
			.build();
		MemoryStrategy usable = MemoryStrategy.builder()
			.strategyId("sem-1")
			.type(MemoryStrategyType.SEMANTIC)
			.namespaces(List.of("/strategy/{memoryStrategyId}/actor/{actorId}/"))
			.build();

		this.mockGetMemory(List.of(custom, usable));

		List<DiscoveredStrategy> discovered = new AgentCoreLongTermMemoryStrategyDiscovery(this.controlClient)
			.discoverStrategies("mem-1");

		assertThat(discovered).extracting(DiscoveredStrategy::strategyId).containsExactly("sem-1");
	}

	private void mockGetMemory(List<MemoryStrategy> strategies) {
		Memory memory = Memory.builder().strategies(strategies).build();
		GetMemoryResponse response = GetMemoryResponse.builder().memory(memory).build();
		given(this.controlClient.getMemory(any(GetMemoryRequest.class))).willReturn(response);
	}

}
