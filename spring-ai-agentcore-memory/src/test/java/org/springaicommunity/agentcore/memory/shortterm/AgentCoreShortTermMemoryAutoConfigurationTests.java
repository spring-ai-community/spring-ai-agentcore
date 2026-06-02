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

package org.springaicommunity.agentcore.memory.shortterm;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springaicommunity.agentcore.memory.AgentCoreMemoryProperties;
import software.amazon.awssdk.services.bedrockagentcore.BedrockAgentCoreClient;

import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Unit tests for {@link AgentCoreShortTermMemoryRepositoryAutoConfiguration}.
 *
 * @author Yuriy Bezsonov
 */
@ExtendWith(OutputCaptureExtension.class)
@DisplayName("AgentCore Short-Term Memory Auto-Configuration Tests")
class AgentCoreShortTermMemoryAutoConfigurationTests {

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(AgentCoreShortTermMemoryRepositoryAutoConfiguration.class));

	@Test
	@DisabledIfEnvironmentVariable(named = "AGENTCORE_MEMORY_MEMORY_ID", matches = ".+",
			disabledReason = "Env var AGENTCORE_MEMORY_MEMORY_ID is set")
	@DisplayName("Should not create beans when memory-id is not set")
	void shouldNotCreateBeansWhenMemoryIdNotSet() {
		this.contextRunner.run((context) -> {
			assertThat(context).doesNotHaveBean(AgentCoreShortTermMemoryRepository.class);
			assertThat(context).doesNotHaveBean(BedrockAgentCoreClient.class);
		});
	}

	@Test
	@DisplayName("Should create repository bean when memory-id is set")
	void shouldCreateRepositoryWhenMemoryIdSet() {
		this.contextRunner.withUserConfiguration(MockClientConfiguration.class)
			.withPropertyValues("agentcore.memory.memory-id=test-memory-123")
			.run((context) -> {
				assertThat(context).hasSingleBean(AgentCoreShortTermMemoryRepository.class);
				AgentCoreShortTermMemoryRepository repository = context
					.getBean(AgentCoreShortTermMemoryRepository.class);
				assertThat(repository).isNotNull();
			});
	}

	@Test
	@DisplayName("Should bind short-term namespaced configuration values without warnings")
	void shouldUseCustomConfigurationValues(CapturedOutput output) {
		this.contextRunner.withUserConfiguration(MockClientConfiguration.class)
			.withPropertyValues("agentcore.memory.memory-id=custom-memory",
					"agentcore.memory.short-term.total-events-limit=500",
					"agentcore.memory.short-term.default-session=my-session",
					"agentcore.memory.short-term.page-size=50")
			.run((context) -> {
				assertThat(context).hasSingleBean(AgentCoreMemoryProperties.class);
				assertThat(context).hasSingleBean(AgentCoreShortTermMemoryProperties.class);
				AgentCoreMemoryProperties memory = context.getBean(AgentCoreMemoryProperties.class);
				AgentCoreShortTermMemoryProperties stm = context.getBean(AgentCoreShortTermMemoryProperties.class);
				assertThat(memory.memoryId()).isEqualTo("custom-memory");
				assertThat(stm.totalEventsLimit()).isEqualTo(500);
				assertThat(stm.defaultSession()).isEqualTo("my-session");
				assertThat(stm.pageSize()).isEqualTo(50);
				assertThat(output.getOut()).doesNotContain("is deprecated");
			});
	}

	@Test
	@DisplayName("Should fall back to legacy STM properties at agentcore.memory.* and emit deprecation warnings (#49, #109)")
	void shouldFallBackToLegacyStmProperties(CapturedOutput output) {
		this.contextRunner.withUserConfiguration(MockClientConfiguration.class)
			.withPropertyValues("agentcore.memory.memory-id=legacy-memory", "agentcore.memory.total-events-limit=42",
					"agentcore.memory.default-session=legacy-session", "agentcore.memory.page-size=7",
					"agentcore.memory.ignore-unknown-roles=true")
			.run((context) -> {
				assertThat(context).hasSingleBean(AgentCoreShortTermMemoryRepository.class);
				AgentCoreMemoryProperties memory = context.getBean(AgentCoreMemoryProperties.class);
				AgentCoreShortTermMemoryProperties stm = context.getBean(AgentCoreShortTermMemoryProperties.class);
				// Legacy properties bound on the root record
				assertThat(memory.totalEventsLimit()).isEqualTo(42);
				assertThat(memory.defaultSession()).isEqualTo("legacy-session");
				assertThat(memory.pageSize()).isEqualTo(7);
				assertThat(memory.ignoreUnknownRoles()).isTrue();
				// New record stays unbound — fallback happens in the auto-config
				assertThat(stm.totalEventsLimit()).isNull();
				assertThat(stm.defaultSession()).isNull();
				assertThat(stm.pageSize()).isNull();
				assertThat(stm.ignoreUnknownRoles()).isNull();
				// Deprecation warnings: one per legacy STM key (#49 namespace
				// migration), plus the #109 warning because ignore-unknown-roles is
				// explicitly set.
				assertThat(output.getOut()).contains("Property 'agentcore.memory.total-events-limit' is deprecated",
						"Use 'agentcore.memory.short-term.total-events-limit' instead",
						"Property 'agentcore.memory.default-session' is deprecated",
						"Use 'agentcore.memory.short-term.default-session' instead",
						"Property 'agentcore.memory.page-size' is deprecated",
						"Use 'agentcore.memory.short-term.page-size' instead",
						"Property 'agentcore.memory.ignore-unknown-roles' is deprecated",
						"Use 'agentcore.memory.short-term.ignore-unknown-roles' instead",
						"Property 'ignore-unknown-roles' is deprecated",
						"https://github.com/spring-ai-community/spring-ai-agentcore/issues/109");
			});
	}

	@Test
	@DisplayName("Should prefer short-term properties over legacy STM ones")
	void shouldPreferNewPropertiesOverLegacy(CapturedOutput output) {
		this.contextRunner.withUserConfiguration(MockClientConfiguration.class)
			.withPropertyValues("agentcore.memory.memory-id=mixed-memory",
					// Legacy values
					"agentcore.memory.total-events-limit=1", "agentcore.memory.page-size=2",
					// New values — these must win, and silence the deprecation warning
					"agentcore.memory.short-term.total-events-limit=99", "agentcore.memory.short-term.page-size=50")
			.run((context) -> {
				AgentCoreShortTermMemoryProperties stm = context.getBean(AgentCoreShortTermMemoryProperties.class);
				assertThat(stm.totalEventsLimit()).isEqualTo(99);
				assertThat(stm.pageSize()).isEqualTo(50);
				// New keys override legacy ones, so no warning for those two properties
				assertThat(output.getOut()).doesNotContain(
						"Property 'agentcore.memory.total-events-limit' is deprecated",
						"Property 'agentcore.memory.page-size' is deprecated");
			});
	}

	@Test
	@DisplayName("Should default ignore-unknown-roles to true when neither namespace sets it")
	void shouldDefaultIgnoreUnknownRolesToTrue(CapturedOutput output) {
		this.contextRunner.withUserConfiguration(MockClientConfiguration.class)
			.withPropertyValues("agentcore.memory.memory-id=defaults-memory")
			.run((context) -> {
				AgentCoreShortTermMemoryRepository repository = context
					.getBean(AgentCoreShortTermMemoryRepository.class);
				// Neither agentcore.memory.short-term.ignore-unknown-roles
				// nor agentcore.memory.ignore-unknown-roles is set, so the auto-config
				// fallback (Boolean.TRUE) must apply.
				assertThat(readBooleanField(repository, "ignoreUnknownRoles")).isTrue();
				// And no deprecation warning, since no legacy property was set.
				assertThat(output.getOut()).doesNotContain("'agentcore.memory.ignore-unknown-roles' is deprecated");
				// And no #109 warning, since the property was not explicitly set.
				assertThat(output.getOut()).doesNotContain("'ignore-unknown-roles' is deprecated");
			});
	}

	@Test
	@DisplayName("Should use provided BedrockAgentCoreClient bean")
	void shouldUseProvidedClientBean() {
		this.contextRunner.withUserConfiguration(MockClientConfiguration.class)
			.withPropertyValues("agentcore.memory.memory-id=test-memory")
			.run((context) -> {
				assertThat(context).hasSingleBean(BedrockAgentCoreClient.class);
				assertThat(context).hasSingleBean(AgentCoreShortTermMemoryRepository.class);
			});
	}

	private static boolean readBooleanField(Object target, String name) {
		try {
			java.lang.reflect.Field field = target.getClass().getDeclaredField(name);
			field.setAccessible(true);
			return field.getBoolean(target);
		}
		catch (ReflectiveOperationException ex) {
			throw new AssertionError("Failed to read field '" + name + "' on " + target.getClass(), ex);
		}
	}

	@Configuration
	static class MockClientConfiguration {

		@Bean
		BedrockAgentCoreClient bedrockAgentCoreClient() {
			return mock(BedrockAgentCoreClient.class);
		}

	}

}
