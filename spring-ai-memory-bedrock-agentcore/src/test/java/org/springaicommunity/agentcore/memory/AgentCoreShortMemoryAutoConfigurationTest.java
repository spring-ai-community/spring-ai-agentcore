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
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import software.amazon.awssdk.services.bedrockagentcore.BedrockAgentCoreClient;

/**
 * Unit tests for {@link AgentCoreShortMemoryRepositoryAutoConfiguration}.
 *
 * @author Yuriy Bezsonov
 */
@DisplayName("AgentCore Short Memory Auto-Configuration Tests")
class AgentCoreShortMemoryAutoConfigurationTest {

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(AgentCoreShortMemoryRepositoryAutoConfiguration.class));

	@Test
	@DisabledIfEnvironmentVariable(named = "AGENTCORE_MEMORY_MEMORY_ID", matches = ".+",
			disabledReason = "Env var AGENTCORE_MEMORY_MEMORY_ID is set")
	@DisplayName("Should not create beans when memory-id is not set")
	void shouldNotCreateBeansWhenMemoryIdNotSet() {
		contextRunner.run(context -> {
			assertThat(context).doesNotHaveBean(AgentCoreShortMemoryRepository.class);
			assertThat(context).doesNotHaveBean(BedrockAgentCoreClient.class);
		});
	}

	@Test
	@DisplayName("Should create repository bean when memory-id is set")
	void shouldCreateRepositoryWhenMemoryIdSet() {
		contextRunner.withUserConfiguration(MockClientConfiguration.class)
			.withPropertyValues("agentcore.memory.memory-id=test-memory-123")
			.run(context -> {
				assertThat(context).hasSingleBean(AgentCoreShortMemoryRepository.class);
				AgentCoreShortMemoryRepository repository = context.getBean(AgentCoreShortMemoryRepository.class);
				assertThat(repository).isNotNull();
			});
	}

	@Test
	@DisplayName("Should use custom configuration values")
	void shouldUseCustomConfigurationValues() {
		contextRunner.withUserConfiguration(MockClientConfiguration.class)
			.withPropertyValues("agentcore.memory.memory-id=custom-memory", "agentcore.memory.total-events-limit=500",
					"agentcore.memory.default-session=my-session", "agentcore.memory.page-size=50",
					"agentcore.memory.ignore-unknown-roles=true")
			.run(context -> {
				assertThat(context).hasSingleBean(AgentCoreMemoryProperties.class);
				AgentCoreMemoryProperties config = context.getBean(AgentCoreMemoryProperties.class);
				assertThat(config.memoryId()).isEqualTo("custom-memory");
				assertThat(config.totalEventsLimit()).isEqualTo(500);
				assertThat(config.defaultSession()).isEqualTo("my-session");
				assertThat(config.pageSize()).isEqualTo(50);
				assertThat(config.ignoreUnknownRoles()).isTrue();
			});
	}

	@Test
	@DisplayName("Should use provided BedrockAgentCoreClient bean")
	void shouldUseProvidedClientBean() {
		contextRunner.withUserConfiguration(MockClientConfiguration.class)
			.withPropertyValues("agentcore.memory.memory-id=test-memory")
			.run(context -> {
				assertThat(context).hasSingleBean(BedrockAgentCoreClient.class);
				assertThat(context).hasSingleBean(AgentCoreShortMemoryRepository.class);
			});
	}

	@Configuration
	static class MockClientConfiguration {

		@Bean
		BedrockAgentCoreClient bedrockAgentCoreClient() {
			return mock(BedrockAgentCoreClient.class);
		}

	}

}
