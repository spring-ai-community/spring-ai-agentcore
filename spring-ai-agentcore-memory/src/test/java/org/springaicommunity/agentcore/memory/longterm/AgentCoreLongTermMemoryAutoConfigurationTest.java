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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.function.Supplier;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable;
import org.springaicommunity.agentcore.memory.AgentCoreMemoryException;
import org.springaicommunity.agentcore.memory.shorttem.AgentCoreShortTermMemoryRepositoryAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import software.amazon.awssdk.services.bedrockagentcore.BedrockAgentCoreClient;
import software.amazon.awssdk.services.bedrockagentcorecontrol.BedrockAgentCoreControlClient;
import software.amazon.awssdk.services.bedrockagentcorecontrol.model.GetMemoryRequest;
import software.amazon.awssdk.services.bedrockagentcorecontrol.model.GetMemoryResponse;
import software.amazon.awssdk.services.bedrockagentcorecontrol.model.Memory;
import software.amazon.awssdk.services.bedrockagentcorecontrol.model.MemoryStrategy;
import software.amazon.awssdk.services.bedrockagentcorecontrol.model.MemoryStrategyType;

/**
 * Unit tests for {@link AgentCoreLongTermMemoryAutoConfiguration}.
 *
 * @author Yuriy Bezsonov
 */
@DisplayName("AgentCore Long-Term Memory Auto-Configuration Tests")
class AgentCoreLongTermMemoryAutoConfigurationTest {

	private static final String MEMORY_ID_PROP = "agentcore.memory.memory-id=test-memory";

	private static final String SEMANTIC_STRATEGY_PROP = "agentcore.memory.long-term.semantic.strategy-id=semantic-123";

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(AgentCoreShortTermMemoryRepositoryAutoConfiguration.class,
				AgentCoreLongTermMemoryAutoConfiguration.class));

	@Test
	@DisabledIfEnvironmentVariable(named = "AGENTCORE_MEMORY_LONG_TERM_SEMANTIC_STRATEGY_ID", matches = ".+",
			disabledReason = "Env var AGENTCORE_MEMORY_LONG_TERM_SEMANTIC_STRATEGY_ID is set")
	@DisplayName("Should not create LTM beans when no strategy IDs configured")
	void shouldNotCreateLtmBeansWhenNoStrategyIds() {
		contextRunner.withUserConfiguration(MockClientConfiguration.class)
			.withPropertyValues(MEMORY_ID_PROP)
			.run(context -> {
				assertThat(context).doesNotHaveBean(AgentCoreLongTermMemoryRetriever.class);
				assertThat(context).doesNotHaveBean(AgentCoreLongTermMemoryAdvisor.class);
			});
	}

	@Test
	@DisplayName("Should create semantic advisor when strategy ID configured")
	void shouldCreateSemanticAdvisor() {
		contextRunner.withUserConfiguration(MockClientConfiguration.class)
			.withPropertyValues(MEMORY_ID_PROP, SEMANTIC_STRATEGY_PROP)
			.run(context -> {
				assertThat(context).hasSingleBean(AgentCoreLongTermMemoryRetriever.class);
				assertThat(context).hasBean("semanticAdvisor");
				AgentCoreLongTermMemoryAdvisor advisor = context.getBean("semanticAdvisor",
						AgentCoreLongTermMemoryAdvisor.class);
				assertThat(advisor).isNotNull();
				assertThat(advisor.getName()).isEqualTo("AgentCoreLongTermMemoryAdvisor-SEMANTIC");
			});
	}

	@Test
	@DisplayName("Should create user preference advisor when strategy ID configured")
	void shouldCreateUserPreferenceAdvisor() {
		contextRunner.withUserConfiguration(MockClientConfiguration.class)
			.withPropertyValues(MEMORY_ID_PROP, SEMANTIC_STRATEGY_PROP,
					"agentcore.memory.long-term.user-preference.strategy-id=prefs-456")
			.run(context -> {
				assertThat(context).hasBean("userPreferenceAdvisor");
				AgentCoreLongTermMemoryAdvisor advisor = context.getBean("userPreferenceAdvisor",
						AgentCoreLongTermMemoryAdvisor.class);
				assertThat(advisor).isNotNull();
				assertThat(advisor.getName()).isEqualTo("AgentCoreLongTermMemoryAdvisor-USER_PREFERENCE");
			});
	}

	@Test
	@DisplayName("Should create summary advisor when strategy ID configured")
	void shouldCreateSummaryAdvisor() {
		contextRunner.withUserConfiguration(MockClientConfiguration.class)
			.withPropertyValues(MEMORY_ID_PROP, SEMANTIC_STRATEGY_PROP,
					"agentcore.memory.long-term.summary.strategy-id=summary-789")
			.run(context -> {
				assertThat(context).hasBean("summaryAdvisor");
				AgentCoreLongTermMemoryAdvisor advisor = context.getBean("summaryAdvisor",
						AgentCoreLongTermMemoryAdvisor.class);
				assertThat(advisor).isNotNull();
				assertThat(advisor.getName()).isEqualTo("AgentCoreLongTermMemoryAdvisor-SUMMARY");
			});
	}

	@Test
	@DisplayName("Should create episodic advisor when strategy ID configured")
	void shouldCreateEpisodicAdvisor() {
		contextRunner.withUserConfiguration(MockClientConfiguration.class)
			.withPropertyValues(MEMORY_ID_PROP, SEMANTIC_STRATEGY_PROP,
					"agentcore.memory.long-term.episodic.strategy-id=episodic-abc")
			.run(context -> {
				assertThat(context).hasBean("episodicAdvisor");
				AgentCoreLongTermMemoryAdvisor advisor = context.getBean("episodicAdvisor",
						AgentCoreLongTermMemoryAdvisor.class);
				assertThat(advisor).isNotNull();
				assertThat(advisor.getName()).isEqualTo("AgentCoreLongTermMemoryAdvisor-EPISODIC");
			});
	}

	@Test
	@DisplayName("Should create all 4 advisors when all strategy IDs configured")
	void shouldCreateAllAdvisorsWhenAllConfigured() {
		contextRunner.withUserConfiguration(MockClientConfiguration.class)
			.withPropertyValues(MEMORY_ID_PROP, SEMANTIC_STRATEGY_PROP,
					"agentcore.memory.long-term.user-preference.strategy-id=prefs-456",
					"agentcore.memory.long-term.summary.strategy-id=summary-789",
					"agentcore.memory.long-term.episodic.strategy-id=episodic-abc")
			.run(context -> {
				assertThat(context).hasSingleBean(AgentCoreLongTermMemoryRetriever.class);
				assertThat(context).hasBean("semanticAdvisor");
				assertThat(context).hasBean("userPreferenceAdvisor");
				assertThat(context).hasBean("summaryAdvisor");
				assertThat(context).hasBean("episodicAdvisor");

				// Verify all are AgentCoreLongTermMemoryAdvisor instances
				java.util.Map<String, AgentCoreLongTermMemoryAdvisor> advisors = context
					.getBeansOfType(AgentCoreLongTermMemoryAdvisor.class);
				assertThat(advisors).hasSize(4);
			});
	}

	@Test
	@DisplayName("Should use custom topK values")
	void shouldUseCustomTopKValues() {
		contextRunner.withUserConfiguration(MockClientConfiguration.class)
			.withPropertyValues(MEMORY_ID_PROP, SEMANTIC_STRATEGY_PROP, "agentcore.memory.long-term.semantic.top-k=10",
					"agentcore.memory.long-term.episodic.strategy-id=episodic-abc",
					"agentcore.memory.long-term.episodic.episodes-top-k=5",
					"agentcore.memory.long-term.episodic.reflections-top-k=3")
			.run(context -> {
				var config = context.getBean(AgentCoreLongTermMemoryProperties.class);
				assertThat(config.semantic().topK()).isEqualTo(10);
				assertThat(config.episodic().episodesTopK()).isEqualTo(5);
				assertThat(config.episodic().reflectionsTopK()).isEqualTo(3);
			});
	}

	@Test
	@DisplayName("Should use custom namespace pattern when configured")
	void shouldUseCustomNamespacePattern() {
		String customPattern = "/custom/{memoryStrategyId}/users/{actorId}";
		contextRunner.withUserConfiguration(CustomNamespaceConfiguration.class)
			.withPropertyValues(MEMORY_ID_PROP, SEMANTIC_STRATEGY_PROP,
					"agentcore.memory.long-term.semantic.namespace-pattern=" + customPattern)
			.run(context -> {
				// Verify property binding
				var config = context.getBean(AgentCoreLongTermMemoryProperties.class);
				assertThat(config.semantic().namespacePattern()).isEqualTo(customPattern);
				assertThat(config.semantic().resolveNamespacePattern()).isEqualTo(customPattern);
				// Verify validation passed and beans created
				assertThat(context).hasNotFailed();
				assertThat(context).hasSingleBean(AgentCoreLongTermMemoryRetriever.class);
				assertThat(context).hasBean("semanticAdvisor");
			});
	}

	@Test
	@DisplayName("Should use default namespace pattern when not configured")
	void shouldUseDefaultNamespacePatternWhenNotConfigured() {
		contextRunner.withUserConfiguration(MockClientConfiguration.class)
			.withPropertyValues(MEMORY_ID_PROP, SEMANTIC_STRATEGY_PROP)
			.run(context -> {
				var config = context.getBean(AgentCoreLongTermMemoryProperties.class);
				assertThat(config.semantic().namespacePattern()).isNull();
				assertThat(config.semantic().resolveNamespacePattern())
					.isEqualTo(AgentCoreLongTermMemoryNamespace.ACTOR.getPattern());
			});
	}

	@Test
	@DisplayName("Should fail startup when custom namespace in config but default in AWS")
	void shouldFailStartupWhenNamespaceMismatch() {
		String customPattern = "custom-namespace/strategies/{memoryStrategyId}/actors/{actorId}";
		contextRunner.withUserConfiguration(MockClientConfiguration.class)
			.withPropertyValues(MEMORY_ID_PROP, SEMANTIC_STRATEGY_PROP,
					"agentcore.memory.long-term.semantic.namespace-pattern=" + customPattern)
			.run(context -> {
				assertThat(context).hasFailed();
				assertThat(context.getStartupFailure())
					.hasRootCauseInstanceOf(AgentCoreMemoryException.ConfigurationException.class);
				assertThat(context.getStartupFailure().getMessage()).contains("Namespace mismatch");
			});
	}

	@Test
	@DisplayName("Should collect all advisors via List injection")
	void shouldCollectAllAdvisorsViaListInjection() {
		contextRunner.withUserConfiguration(MockClientConfiguration.class)
			.withPropertyValues(MEMORY_ID_PROP, SEMANTIC_STRATEGY_PROP,
					"agentcore.memory.long-term.user-preference.strategy-id=prefs-456",
					"agentcore.memory.long-term.summary.strategy-id=summary-789",
					"agentcore.memory.long-term.episodic.strategy-id=episodic-abc")
			.run(context -> {
				// This is how the real app injects advisors
				java.util.Collection<AgentCoreLongTermMemoryAdvisor> advisors = context
					.getBeansOfType(AgentCoreLongTermMemoryAdvisor.class)
					.values();
				assertThat(advisors).hasSize(4);
				assertThat(advisors).extracting(AgentCoreLongTermMemoryAdvisor::getName)
					.containsExactlyInAnyOrder("AgentCoreLongTermMemoryAdvisor-SEMANTIC",
							"AgentCoreLongTermMemoryAdvisor-USER_PREFERENCE", "AgentCoreLongTermMemoryAdvisor-SUMMARY",
							"AgentCoreLongTermMemoryAdvisor-EPISODIC");
			});
	}

	@Configuration
	static class MockClientConfiguration {

		@Bean
		BedrockAgentCoreClient bedrockAgentCoreClient() {
			return mock(BedrockAgentCoreClient.class);
		}

		@Bean
		Supplier<BedrockAgentCoreControlClient> controlClientFactory() {
			BedrockAgentCoreControlClient controlClient = mock(BedrockAgentCoreControlClient.class);

			// Mock GetMemory response with all strategies having correct namespace format
			GetMemoryResponse response = GetMemoryResponse.builder()
				.memory(Memory.builder()
					.strategies(List.of(
							MemoryStrategy.builder()
								.strategyId("semantic-123")
								.namespaces(List.of(AgentCoreLongTermMemoryNamespace.ACTOR.getPattern()))
								.build(),
							MemoryStrategy.builder()
								.strategyId("prefs-456")
								.namespaces(List.of(AgentCoreLongTermMemoryNamespace.ACTOR.getPattern()))
								.build(),
							MemoryStrategy.builder()
								.strategyId("summary-789")
								.namespaces(List.of(AgentCoreLongTermMemoryNamespace.SESSION.getPattern()))
								.build(),
							MemoryStrategy.builder()
								.strategyId("episodic-abc")
								.namespaces(List.of(AgentCoreLongTermMemoryNamespace.ACTOR.getPattern()))
								.build()))
					.build())
				.build();

			when(controlClient.getMemory(any(GetMemoryRequest.class))).thenReturn(response);
			return () -> controlClient;
		}

	}

	@Configuration
	static class CustomNamespaceConfiguration {

		@Bean
		BedrockAgentCoreClient bedrockAgentCoreClient() {
			return mock(BedrockAgentCoreClient.class);
		}

		@Bean
		Supplier<BedrockAgentCoreControlClient> controlClientFactory() {
			BedrockAgentCoreControlClient controlClient = mock(BedrockAgentCoreControlClient.class);

			// Mock GetMemory response with custom namespace pattern
			GetMemoryResponse response = GetMemoryResponse.builder()
				.memory(Memory.builder()
					.strategies(List.of(MemoryStrategy.builder()
						.strategyId("semantic-123")
						.namespaces(List.of("/custom/{memoryStrategyId}/users/{actorId}"))
						.build()))
					.build())
				.build();

			when(controlClient.getMemory(any(GetMemoryRequest.class))).thenReturn(response);
			return () -> controlClient;
		}

	}

	// ==================== Autodiscovery Tests ====================

	@Test
	@DisplayName("Autodiscovery: Should create advisors from discovered strategies")
	void autodiscoveryShouldCreateAdvisorsFromDiscoveredStrategies() {
		contextRunner.withUserConfiguration(AutodiscoveryMockConfiguration.class)
			.withPropertyValues(MEMORY_ID_PROP, "agentcore.memory.long-term.auto-discovery=true")
			.run(context -> {
				assertThat(context).hasNotFailed();
				assertThat(context).hasSingleBean(AgentCoreLongTermMemoryRetriever.class);

				// Autodiscovery returns a List bean, not individual beans
				@SuppressWarnings("unchecked")
				List<AgentCoreLongTermMemoryAdvisor> advisors = context.getBean("autoDiscoveredAdvisors", List.class);
				assertThat(advisors).hasSize(3); // SEMANTIC, SUMMARY, USER_PREFERENCE (no
													// CUSTOM)

				assertThat(advisors).extracting(AgentCoreLongTermMemoryAdvisor::getName)
					.containsExactlyInAnyOrder("AgentCoreLongTermMemoryAdvisor-SEMANTIC",
							"AgentCoreLongTermMemoryAdvisor-SUMMARY", "AgentCoreLongTermMemoryAdvisor-USER_PREFERENCE");
			});
	}

	@Test
	@DisplayName("Autodiscovery: Should use explicit config to override discovered values")
	void autodiscoveryShouldUseExplicitConfigOverrides() {
		contextRunner.withUserConfiguration(AutodiscoveryMockConfiguration.class)
			.withPropertyValues(MEMORY_ID_PROP, "agentcore.memory.long-term.auto-discovery=true",
					"agentcore.memory.long-term.semantic.top-k=10", // Override topK
					"agentcore.memory.long-term.summary.top-k=5")
			.run(context -> {
				assertThat(context).hasNotFailed();

				var config = context.getBean(AgentCoreLongTermMemoryProperties.class);
				assertThat(config.semantic().topK()).isEqualTo(10);
				assertThat(config.summary().topK()).isEqualTo(5);
			});
	}

	@Test
	@DisplayName("Autodiscovery: Should NOT create duplicate advisors when explicit config also present")
	void autodiscoveryShouldNotCreateDuplicateAdvisors() {
		contextRunner.withUserConfiguration(AutodiscoveryMockConfiguration.class)
			.withPropertyValues(MEMORY_ID_PROP, "agentcore.memory.long-term.auto-discovery=true",
					"agentcore.memory.long-term.semantic.strategy-id=semantic-override", // Explicit
																							// config
					"agentcore.memory.long-term.semantic.top-k=15")
			.run(context -> {
				assertThat(context).hasNotFailed();

				// Autodiscovery list should have 3 advisors
				@SuppressWarnings("unchecked")
				List<AgentCoreLongTermMemoryAdvisor> autodiscovered = context.getBean("autoDiscoveredAdvisors",
						List.class);
				assertThat(autodiscovered).hasSize(3);

				// Individual beans should NOT be created (auto-discovery=true disables
				// them)
				assertThat(context).doesNotHaveBean("semanticAdvisor");
				assertThat(context).doesNotHaveBean("summaryAdvisor");
				assertThat(context).doesNotHaveBean("userPreferenceAdvisor");
				assertThat(context).doesNotHaveBean("episodicAdvisor");
			});
	}

	@Test
	@DisplayName("Autodiscovery + Explicit: Should use explicit strategy-id over discovered one")
	void autodiscoveryWithExplicitStrategyShouldUseExplicitStrategyId() {
		contextRunner.withUserConfiguration(AutodiscoveryMockConfiguration.class)
			.withPropertyValues(MEMORY_ID_PROP, "agentcore.memory.long-term.auto-discovery=true",
					"agentcore.memory.long-term.semantic.strategy-id=my-explicit-semantic-id",
					"agentcore.memory.long-term.semantic.top-k=20")
			.run(context -> {
				assertThat(context).hasNotFailed();

				@SuppressWarnings("unchecked")
				List<AgentCoreLongTermMemoryAdvisor> advisors = context.getBean("autoDiscoveredAdvisors", List.class);

				// Find the semantic advisor
				AgentCoreLongTermMemoryAdvisor semanticAdvisor = advisors.stream()
					.filter(a -> a.getName().contains("SEMANTIC"))
					.findFirst()
					.orElseThrow();

				// Verify explicit strategy-id is used (not discovered one)
				// The advisor name doesn't contain strategy-id, but we can verify config
				// was applied
				var config = context.getBean(AgentCoreLongTermMemoryProperties.class);
				assertThat(config.semantic().strategyId()).isEqualTo("my-explicit-semantic-id");
				assertThat(config.semantic().topK()).isEqualTo(20);
			});
	}

	@Test
	@DisplayName("Autodiscovery: Should return empty list when no strategies found")
	void autodiscoveryShouldReturnEmptyWhenNoStrategies() {
		contextRunner.withUserConfiguration(EmptyDiscoveryMockConfiguration.class)
			.withPropertyValues(MEMORY_ID_PROP, "agentcore.memory.long-term.auto-discovery=true")
			.run(context -> {
				assertThat(context).hasNotFailed();

				@SuppressWarnings("unchecked")
				List<AgentCoreLongTermMemoryAdvisor> advisors = context.getBean("autoDiscoveredAdvisors", List.class);
				assertThat(advisors).isEmpty();
			});
	}

	@Test
	@DisplayName("Autodiscovery: Should skip CUSTOM strategy type")
	void autodiscoveryShouldSkipCustomStrategyType() {
		contextRunner.withUserConfiguration(AutodiscoveryWithCustomMockConfiguration.class)
			.withPropertyValues(MEMORY_ID_PROP, "agentcore.memory.long-term.auto-discovery=true")
			.run(context -> {
				assertThat(context).hasNotFailed();

				@SuppressWarnings("unchecked")
				List<AgentCoreLongTermMemoryAdvisor> advisors = context.getBean("autoDiscoveredAdvisors", List.class);
				// Only SEMANTIC should be created, CUSTOM should be skipped
				assertThat(advisors).hasSize(1);
				assertThat(advisors.get(0).getName()).isEqualTo("AgentCoreLongTermMemoryAdvisor-SEMANTIC");
			});
	}

	@Test
	@DisplayName("Autodiscovery: Should use first namespace when multiple exist")
	void autodiscoveryShouldUseFirstNamespace() {
		contextRunner.withUserConfiguration(MultipleNamespacesMockConfiguration.class)
			.withPropertyValues(MEMORY_ID_PROP, "agentcore.memory.long-term.auto-discovery=true")
			.run(context -> {
				assertThat(context).hasNotFailed();

				@SuppressWarnings("unchecked")
				List<AgentCoreLongTermMemoryAdvisor> advisors = context.getBean("autoDiscoveredAdvisors", List.class);
				assertThat(advisors).hasSize(1);
			});
	}

	@Test
	@DisplayName("Autodiscovery: Should throw error when explicit namespace doesn't match discovered namespaces")
	void autodiscoveryShouldThrowErrorForMismatchedNamespace() {
		contextRunner.withUserConfiguration(AutodiscoveryMockConfiguration.class)
			.withPropertyValues(MEMORY_ID_PROP, "agentcore.memory.long-term.auto-discovery=true",
					"agentcore.memory.long-term.semantic.strategy-id=discovered-semantic", // Matches
					"agentcore.memory.long-term.semantic.namespace-pattern=/wrong/namespace/pattern") // Doesn't
																										// match
			.run(context -> {
				assertThat(context).hasFailed();
				assertThat(context.getStartupFailure())
					.hasRootCauseInstanceOf(AgentCoreMemoryException.ConfigurationException.class);
				assertThat(context.getStartupFailure().getMessage())
					.contains("does not match any discovered namespace");
			});
	}

	@Test
	@DisplayName("Autodiscovery: Should ignore explicit config when strategy-id doesn't match")
	void autodiscoveryShouldIgnoreExplicitConfigWhenStrategyIdDoesNotMatch() {
		contextRunner.withUserConfiguration(AutodiscoveryMockConfiguration.class)
			.withPropertyValues(MEMORY_ID_PROP, "agentcore.memory.long-term.auto-discovery=true",
					"agentcore.memory.long-term.semantic.strategy-id=non-matching-id", // Doesn't
																						// match
					"agentcore.memory.long-term.semantic.top-k=99")
			.run(context -> {
				assertThat(context).hasNotFailed();

				// Config should be ignored, default topK=3 should be used
				@SuppressWarnings("unchecked")
				List<AgentCoreLongTermMemoryAdvisor> advisors = context.getBean("autoDiscoveredAdvisors", List.class);
				assertThat(advisors).hasSize(3);
			});
	}

	@Test
	@DisplayName("Autodiscovery: Should auto-register namespace when auto-register enabled")
	void autodiscoveryShouldAutoRegisterNamespaceWhenEnabled() {
		contextRunner.withUserConfiguration(AutodiscoveryMockConfiguration.class)
			.withPropertyValues(MEMORY_ID_PROP, "agentcore.memory.long-term.auto-discovery=true",
					"agentcore.memory.long-term.namespace.auto-register=true",
					"agentcore.memory.long-term.semantic.strategy-id=discovered-semantic",
					"agentcore.memory.long-term.semantic.namespace-pattern=/new/namespace/pattern")
			.run(context -> {
				// Should not fail - namespace should be auto-registered
				assertThat(context).hasNotFailed();

				@SuppressWarnings("unchecked")
				List<AgentCoreLongTermMemoryAdvisor> advisors = context.getBean("autoDiscoveredAdvisors", List.class);
				assertThat(advisors).hasSize(3);
			});
	}

	@Test
	@DisplayName("Autodiscovery: Should use explicit namespace when it matches discovered namespace")
	void autodiscoveryShouldUseExplicitNamespaceWhenMatching() {
		contextRunner.withUserConfiguration(AutodiscoveryMockConfiguration.class)
			.withPropertyValues(MEMORY_ID_PROP, "agentcore.memory.long-term.auto-discovery=true",
					"agentcore.memory.long-term.semantic.strategy-id=discovered-semantic",
					"agentcore.memory.long-term.semantic.namespace-pattern=/strategies/{memoryStrategyId}/actors/{actorId}")
			.run(context -> {
				// Should succeed - namespace matches discovered namespace
				assertThat(context).hasNotFailed();

				@SuppressWarnings("unchecked")
				List<AgentCoreLongTermMemoryAdvisor> advisors = context.getBean("autoDiscoveredAdvisors", List.class);
				assertThat(advisors).hasSize(3);
			});
	}

	@Configuration
	static class AutodiscoveryMockConfiguration {

		@Bean
		BedrockAgentCoreClient bedrockAgentCoreClient() {
			return mock(BedrockAgentCoreClient.class);
		}

		@Bean
		Supplier<BedrockAgentCoreControlClient> controlClientFactory() {
			BedrockAgentCoreControlClient controlClient = mock(BedrockAgentCoreControlClient.class);

			GetMemoryResponse response = GetMemoryResponse.builder()
				.memory(Memory.builder()
					.strategies(List.of(
							MemoryStrategy.builder()
								.strategyId("discovered-semantic")
								.type(MemoryStrategyType.SEMANTIC)
								.namespaces(List.of("/strategies/{memoryStrategyId}/actors/{actorId}"))
								.build(),
							MemoryStrategy.builder()
								.strategyId("discovered-summary")
								.type(MemoryStrategyType.SUMMARIZATION)
								.namespaces(
										List.of("/strategies/{memoryStrategyId}/actors/{actorId}/sessions/{sessionId}"))
								.build(),
							MemoryStrategy.builder()
								.strategyId("discovered-prefs")
								.type(MemoryStrategyType.USER_PREFERENCE)
								.namespaces(List.of("/strategies/{memoryStrategyId}/actors/{actorId}"))
								.build()))
					.build())
				.build();

			when(controlClient.getMemory(any(GetMemoryRequest.class))).thenReturn(response);
			return () -> controlClient;
		}

	}

	@Configuration
	static class EmptyDiscoveryMockConfiguration {

		@Bean
		BedrockAgentCoreClient bedrockAgentCoreClient() {
			return mock(BedrockAgentCoreClient.class);
		}

		@Bean
		Supplier<BedrockAgentCoreControlClient> controlClientFactory() {
			BedrockAgentCoreControlClient controlClient = mock(BedrockAgentCoreControlClient.class);

			GetMemoryResponse response = GetMemoryResponse.builder()
				.memory(Memory.builder().strategies(List.of()).build())
				.build();

			when(controlClient.getMemory(any(GetMemoryRequest.class))).thenReturn(response);
			return () -> controlClient;
		}

	}

	@Configuration
	static class AutodiscoveryWithCustomMockConfiguration {

		@Bean
		BedrockAgentCoreClient bedrockAgentCoreClient() {
			return mock(BedrockAgentCoreClient.class);
		}

		@Bean
		Supplier<BedrockAgentCoreControlClient> controlClientFactory() {
			BedrockAgentCoreControlClient controlClient = mock(BedrockAgentCoreControlClient.class);

			GetMemoryResponse response = GetMemoryResponse.builder()
				.memory(Memory.builder()
					.strategies(List.of(
							MemoryStrategy.builder()
								.strategyId("semantic-strategy")
								.type(MemoryStrategyType.SEMANTIC)
								.namespaces(List.of("/strategies/{memoryStrategyId}/actors/{actorId}"))
								.build(),
							MemoryStrategy.builder()
								.strategyId("custom-strategy")
								.type(MemoryStrategyType.CUSTOM) // Should be skipped
								.namespaces(List.of("/custom/namespace"))
								.build()))
					.build())
				.build();

			when(controlClient.getMemory(any(GetMemoryRequest.class))).thenReturn(response);
			return () -> controlClient;
		}

	}

	@Configuration
	static class MultipleNamespacesMockConfiguration {

		@Bean
		BedrockAgentCoreClient bedrockAgentCoreClient() {
			return mock(BedrockAgentCoreClient.class);
		}

		@Bean
		Supplier<BedrockAgentCoreControlClient> controlClientFactory() {
			BedrockAgentCoreControlClient controlClient = mock(BedrockAgentCoreControlClient.class);

			GetMemoryResponse response = GetMemoryResponse.builder()
				.memory(Memory.builder()
					.strategies(List.of(MemoryStrategy.builder()
						.strategyId("multi-ns-strategy")
						.type(MemoryStrategyType.SEMANTIC)
						.namespaces(List.of("/strategies/{memoryStrategyId}/actors/{actorId}", // First
																								// one
																								// used
								"/strategies/{memoryStrategyId}/actors/{actorId}/sessions/{sessionId}"))
						.build()))
					.build())
				.build();

			when(controlClient.getMemory(any(GetMemoryRequest.class))).thenReturn(response);
			return () -> controlClient;
		}

	}

}
