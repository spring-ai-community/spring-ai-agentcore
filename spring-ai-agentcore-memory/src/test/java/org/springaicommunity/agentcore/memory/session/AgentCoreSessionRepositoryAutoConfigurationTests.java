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

package org.springaicommunity.agentcore.memory.session;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springaicommunity.agentcore.memory.shortterm.AgentCoreShortTermMemoryRepositoryAutoConfiguration;
import software.amazon.awssdk.services.bedrockagentcore.BedrockAgentCoreClient;

import org.springframework.ai.session.DefaultSessionService;
import org.springframework.ai.session.SessionRepository;
import org.springframework.ai.session.SessionService;
import org.springframework.ai.session.advisor.SessionMemoryAdvisor;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Unit tests for {@link AgentCoreSessionRepositoryAutoConfiguration} and
 * {@link AgentCoreSessionMissingDepDiagnostics}.
 */
@ExtendWith(OutputCaptureExtension.class)
class AgentCoreSessionRepositoryAutoConfigurationTests {

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(AgentCoreShortTermMemoryRepositoryAutoConfiguration.class,
				AgentCoreSessionRepositoryAutoConfiguration.class, AgentCoreSessionMissingDepDiagnostics.class));

	@Test
	@DisabledIfEnvironmentVariable(named = "AGENTCORE_MEMORY_MEMORY_ID", matches = ".+",
			disabledReason = "Env var AGENTCORE_MEMORY_MEMORY_ID is set")
	void noSessionBeansWhenEnabledUnset() {
		this.contextRunner.withUserConfiguration(MockClientConfiguration.class)
			.withPropertyValues("agentcore.memory.memory-id=test-memory")
			.run((context) -> {
				assertThat(context).doesNotHaveBean(AgentCoreSessionRepository.class);
				assertThat(context).doesNotHaveBean(SessionService.class);
				assertThat(context).doesNotHaveBean(SessionMemoryAdvisor.class);
			});
	}

	@Test
	@DisabledIfEnvironmentVariable(named = "AGENTCORE_MEMORY_MEMORY_ID", matches = ".+",
			disabledReason = "Env var AGENTCORE_MEMORY_MEMORY_ID is set")
	void noSessionBeansWhenMemoryIdUnset() {
		this.contextRunner.withUserConfiguration(MockClientConfiguration.class)
			.withPropertyValues("agentcore.memory.session.enabled=true")
			.run((context) -> assertThat(context).doesNotHaveBean(AgentCoreSessionRepository.class));
	}

	@Test
	void allSessionBeansPresentWhenEnabledAndMemoryId() {
		this.contextRunner.withUserConfiguration(MockClientConfiguration.class)
			.withPropertyValues("agentcore.memory.memory-id=test-memory", "agentcore.memory.session.enabled=true")
			.run((context) -> {
				assertThat(context).hasSingleBean(AgentCoreSessionRepository.class);
				assertThat(context).hasSingleBean(DefaultSessionService.class);
				assertThat(context).hasSingleBean(SessionMemoryAdvisor.class);
				assertThat(context).hasSingleBean(AgentCoreSessionMemory.class);
			});
	}

	@Test
	void customSessionServiceOverridesDefault() {
		SessionService customService = mock(SessionService.class);
		this.contextRunner.withUserConfiguration(MockClientConfiguration.class)
			.withBean(SessionService.class, () -> customService)
			.withPropertyValues("agentcore.memory.memory-id=test-memory", "agentcore.memory.session.enabled=true")
			.run((context) -> {
				assertThat(context).hasSingleBean(SessionService.class);
				assertThat(context.getBean(SessionService.class)).isSameAs(customService);
				assertThat(context).doesNotHaveBean(DefaultSessionService.class);
			});
	}

	@Test
	void customSessionMemoryAdvisorOverridesDefault() {
		SessionMemoryAdvisor customAdvisor = mock(SessionMemoryAdvisor.class);
		this.contextRunner.withUserConfiguration(MockClientConfiguration.class)
			.withBean(SessionMemoryAdvisor.class, () -> customAdvisor)
			.withPropertyValues("agentcore.memory.memory-id=test-memory", "agentcore.memory.session.enabled=true")
			.run((context) -> {
				assertThat(context).hasSingleBean(SessionMemoryAdvisor.class);
				assertThat(context.getBean(SessionMemoryAdvisor.class)).isSameAs(customAdvisor);
			});
	}

	@Test
	void defaultUserIdBindsToBuilder() {
		this.contextRunner.withUserConfiguration(MockClientConfiguration.class)
			.withPropertyValues("agentcore.memory.memory-id=test-memory", "agentcore.memory.session.enabled=true",
					"agentcore.memory.session.default-user-id=alice")
			.run((context) -> {
				AgentCoreSessionProperties props = context.getBean(AgentCoreSessionProperties.class);
				assertThat(props.defaultUserId()).isEqualTo("alice");
				assertThat(context).hasSingleBean(SessionMemoryAdvisor.class);
			});
	}

	@Test
	void legacyDeprecationWarningsPreservedUnderSessionEnabled(CapturedOutput output) {
		// When agentcore.memory.session.enabled=true AND legacy STM properties are set,
		// the shared ShortTermPropertyResolver still emits the same deprecation
		// warnings.
		this.contextRunner.withUserConfiguration(MockClientConfiguration.class)
			.withPropertyValues("agentcore.memory.memory-id=test-memory", "agentcore.memory.session.enabled=true",
					"agentcore.memory.page-size=7")
			.run((context) -> {
				assertThat(context).hasSingleBean(AgentCoreSessionRepository.class);
				assertThat(output.getOut()).contains("Property 'agentcore.memory.page-size' is deprecated",
						"Use 'agentcore.memory.short-term.page-size' instead");
			});
	}

	@Test
	void sessionScopedPropertyWinsOverShortTermWithoutLegacyDeprecationWarning(CapturedOutput output) {
		// D6c: session-first resolution. Setting agentcore.memory.session.page-size (the
		// session adopter's front door) plus the short-term namespace must not trip the
		// legacy agentcore.memory.* deprecation warning, since neither legacy prop is
		// set.
		this.contextRunner.withUserConfiguration(MockClientConfiguration.class)
			.withPropertyValues("agentcore.memory.memory-id=test-memory", "agentcore.memory.session.enabled=true",
					"agentcore.memory.session.page-size=11", "agentcore.memory.short-term.page-size=22")
			.run((context) -> {
				AgentCoreSessionProperties props = context.getBean(AgentCoreSessionProperties.class);
				assertThat(props.pageSize()).isEqualTo(11);
				assertThat(context).hasSingleBean(AgentCoreSessionRepository.class);
				assertThat(output.getOut()).doesNotContain("is deprecated");
			});
	}

	@Test
	void shortTermPropertyUsedWhenSessionUnsetNoLegacyWarning(CapturedOutput output) {
		// D6c: session value null -> defer to the short-term namespace; the short-term
		// namespace is not deprecated, so no warning is emitted.
		this.contextRunner.withUserConfiguration(MockClientConfiguration.class)
			.withPropertyValues("agentcore.memory.memory-id=test-memory", "agentcore.memory.session.enabled=true",
					"agentcore.memory.short-term.page-size=22")
			.run((context) -> {
				AgentCoreSessionProperties props = context.getBean(AgentCoreSessionProperties.class);
				assertThat(props.pageSize()).isNull();
				assertThat(context).hasSingleBean(AgentCoreSessionRepository.class);
				assertThat(output.getOut()).doesNotContain("is deprecated");
			});
	}

	@Test
	void branchSwapPropertiesBindFromSessionNamespace() {
		this.contextRunner.withUserConfiguration(MockClientConfiguration.class)
			.withPropertyValues("agentcore.memory.memory-id=test-memory", "agentcore.memory.session.enabled=true",
					"agentcore.memory.session.branch-swap-enabled=true",
					"agentcore.memory.session.delete-superseded-branch=true",
					"agentcore.memory.session.branch-cache-enabled=true",
					"agentcore.memory.session.branch-cache-ttl=30s")
			.run((context) -> {
				AgentCoreSessionProperties props = context.getBean(AgentCoreSessionProperties.class);
				assertThat(props.branchSwapEnabled()).isTrue();
				assertThat(props.deleteSupersededBranch()).isTrue();
				assertThat(props.branchCacheEnabled()).isTrue();
				assertThat(props.branchCacheTtl()).hasSeconds(30);
				assertThat(context).hasSingleBean(AgentCoreSessionRepository.class);
			});
	}

	@Test
	void branchSwapDefaultsOffWhenUnset() {
		this.contextRunner.withUserConfiguration(MockClientConfiguration.class)
			.withPropertyValues("agentcore.memory.memory-id=test-memory", "agentcore.memory.session.enabled=true")
			.run((context) -> {
				AgentCoreSessionProperties props = context.getBean(AgentCoreSessionProperties.class);
				assertThat(props.branchSwapEnabled()).isNull();
				assertThat(context).hasSingleBean(AgentCoreSessionRepository.class);
			});
	}

	@Test
	void autoConfigOrderedAfterShortTerm() {
		this.contextRunner.withUserConfiguration(MockClientConfiguration.class)
			.withPropertyValues("agentcore.memory.memory-id=test-memory", "agentcore.memory.session.enabled=true")
			.run((context) -> {
				assertThat(context).hasSingleBean(BedrockAgentCoreClient.class);
				assertThat(context).hasSingleBean(AgentCoreSessionRepository.class);
			});
	}

	@Test
	void filteredClassLoaderHidesSessionRepositoryNoSessionBeans() {
		this.contextRunner.withUserConfiguration(MockClientConfiguration.class)
			.withClassLoader(new FilteredClassLoader(SessionRepository.class))
			.withPropertyValues("agentcore.memory.memory-id=test-memory", "agentcore.memory.session.enabled=true")
			.run((context) -> assertThat(context).doesNotHaveBean(AgentCoreSessionRepository.class));
	}

	@Test
	void filteredClassLoaderHidesSessionMemoryAdvisorNoSessionBeans() {
		this.contextRunner.withUserConfiguration(MockClientConfiguration.class)
			.withClassLoader(new FilteredClassLoader(SessionMemoryAdvisor.class))
			.withPropertyValues("agentcore.memory.memory-id=test-memory", "agentcore.memory.session.enabled=true")
			.run((context) -> assertThat(context).doesNotHaveBean(AgentCoreSessionRepository.class));
	}

	@Test
	void filteredClassLoaderMissingDepDiagnosticLogsWarning(CapturedOutput output) {
		this.contextRunner.withUserConfiguration(MockClientConfiguration.class)
			.withClassLoader(new FilteredClassLoader(SessionRepository.class))
			.withPropertyValues("agentcore.memory.memory-id=test-memory", "agentcore.memory.session.enabled=true")
			.run((context) -> {
				assertThat(context).hasNotFailed();
				assertThat(output.getOut())
					.contains("'org.springaicommunity:spring-ai-session-management' is not on the classpath");
			});
	}

	@Test
	void stmAutoConfigExcludedNoSessionBeansNoStartupFailure() {
		// Session auto-config only: no BedrockAgentCoreClient -> @ConditionalOnBean
		// fails -> no session beans, no startup failure.
		new ApplicationContextRunner()
			.withConfiguration(AutoConfigurations.of(AgentCoreSessionRepositoryAutoConfiguration.class))
			.withPropertyValues("agentcore.memory.memory-id=test-memory", "agentcore.memory.session.enabled=true")
			.run((context) -> {
				assertThat(context).hasNotFailed();
				assertThat(context).doesNotHaveBean(AgentCoreSessionRepository.class);
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
