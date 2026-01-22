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

package org.springaicommunity.agentcore.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.function.Supplier;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable;
import org.springaicommunity.agentcore.memory.AgentCoreLongMemoryScope;
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

/**
 * Unit tests for {@link AgentCoreLongMemoryAutoConfiguration}.
 *
 * @author Yuriy Bezsonov
 */
@DisplayName("AgentCore Long Memory Auto-Configuration Tests")
class AgentCoreLongMemoryAutoConfigurationTest {

	private static final String MEMORY_ID_PROP = "agentcore.memory.memory-id=test-memory";

	private static final String SEMANTIC_STRATEGY_PROP = "agentcore.memory.long-term.semantic.strategy-id=semantic-123";

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(AgentCoreShortMemoryRepositoryAutoConfiguration.class,
				AgentCoreLongMemoryAutoConfiguration.class));

	@Test
	@DisabledIfEnvironmentVariable(named = "AGENTCORE_MEMORY_LONG_TERM_SEMANTIC_STRATEGY_ID", matches = ".+",
			disabledReason = "Env var AGENTCORE_MEMORY_LONG_TERM_SEMANTIC_STRATEGY_ID is set")
	@DisplayName("Should not create LTM beans when no strategy IDs configured")
	void shouldNotCreateLtmBeansWhenNoStrategyIds() {
		contextRunner.withUserConfiguration(MockClientConfiguration.class)
			.withPropertyValues(MEMORY_ID_PROP)
			.run(context -> {
				assertThat(context).doesNotHaveBean(AgentCoreLongMemoryRetriever.class);
				assertThat(context).doesNotHaveBean(AgentCoreLongMemoryAdvisor.class);
			});
	}

	@Test
	@DisplayName("Should create semantic advisor when strategy ID configured")
	void shouldCreateSemanticAdvisor() {
		contextRunner.withUserConfiguration(MockClientConfiguration.class)
			.withPropertyValues(MEMORY_ID_PROP, SEMANTIC_STRATEGY_PROP)
			.run(context -> {
				assertThat(context).hasSingleBean(AgentCoreLongMemoryRetriever.class);
				assertThat(context).hasBean("semanticAdvisor");
				AgentCoreLongMemoryAdvisor advisor = context.getBean("semanticAdvisor",
						AgentCoreLongMemoryAdvisor.class);
				assertThat(advisor).isNotNull();
				assertThat(advisor.getName()).isEqualTo("AgentCoreLongMemoryAdvisor-SEMANTIC");
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
				AgentCoreLongMemoryAdvisor advisor = context.getBean("userPreferenceAdvisor",
						AgentCoreLongMemoryAdvisor.class);
				assertThat(advisor).isNotNull();
				assertThat(advisor.getName()).isEqualTo("AgentCoreLongMemoryAdvisor-USER_PREFERENCE");
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
				AgentCoreLongMemoryAdvisor advisor = context.getBean("summaryAdvisor",
						AgentCoreLongMemoryAdvisor.class);
				assertThat(advisor).isNotNull();
				assertThat(advisor.getName()).isEqualTo("AgentCoreLongMemoryAdvisor-SUMMARY");
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
				AgentCoreLongMemoryAdvisor advisor = context.getBean("episodicAdvisor",
						AgentCoreLongMemoryAdvisor.class);
				assertThat(advisor).isNotNull();
				assertThat(advisor.getName()).isEqualTo("AgentCoreLongMemoryAdvisor-EPISODIC");
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
				assertThat(context).hasSingleBean(AgentCoreLongMemoryRetriever.class);
				assertThat(context).hasBean("semanticAdvisor");
				assertThat(context).hasBean("userPreferenceAdvisor");
				assertThat(context).hasBean("summaryAdvisor");
				assertThat(context).hasBean("episodicAdvisor");

				// Verify all are AgentCoreLongMemoryAdvisor instances
				java.util.Map<String, AgentCoreLongMemoryAdvisor> advisors = context
					.getBeansOfType(AgentCoreLongMemoryAdvisor.class);
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
				var config = context.getBean(AgentCoreLongMemoryProperties.class);
				assertThat(config.semantic().topK()).isEqualTo(10);
				assertThat(config.episodic().episodesTopK()).isEqualTo(5);
				assertThat(config.episodic().reflectionsTopK()).isEqualTo(3);
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
				java.util.Collection<AgentCoreLongMemoryAdvisor> advisors = context
					.getBeansOfType(AgentCoreLongMemoryAdvisor.class)
					.values();
				assertThat(advisors).hasSize(4);
				assertThat(advisors).extracting(AgentCoreLongMemoryAdvisor::getName)
					.containsExactlyInAnyOrder("AgentCoreLongMemoryAdvisor-SEMANTIC",
							"AgentCoreLongMemoryAdvisor-USER_PREFERENCE", "AgentCoreLongMemoryAdvisor-SUMMARY",
							"AgentCoreLongMemoryAdvisor-EPISODIC");
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
								.namespaces(List.of(AgentCoreLongMemoryScope.ACTOR.getPattern()))
								.build(),
							MemoryStrategy.builder()
								.strategyId("prefs-456")
								.namespaces(List.of(AgentCoreLongMemoryScope.ACTOR.getPattern()))
								.build(),
							MemoryStrategy.builder()
								.strategyId("summary-789")
								.namespaces(List.of(AgentCoreLongMemoryScope.SESSION.getPattern()))
								.build(),
							MemoryStrategy.builder()
								.strategyId("episodic-abc")
								.namespaces(List.of(AgentCoreLongMemoryScope.ACTOR.getPattern()))
								.build()))
					.build())
				.build();

			when(controlClient.getMemory(any(GetMemoryRequest.class))).thenReturn(response);
			return () -> controlClient;
		}

	}

}
