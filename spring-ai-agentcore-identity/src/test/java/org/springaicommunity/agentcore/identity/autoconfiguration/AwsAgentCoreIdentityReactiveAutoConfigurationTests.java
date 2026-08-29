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

package org.springaicommunity.agentcore.identity.autoconfiguration;

import io.micrometer.context.ContextRegistry;
import io.micrometer.context.ContextSnapshot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springaicommunity.agentcore.identity.core.WorkloadAccessTokenAccessor;
import org.springaicommunity.agentcore.identity.core.WorkloadAccessTokenCallback;
import org.springaicommunity.agentcore.identity.core.WorkloadAccessTokenHolder;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Hooks;
import software.amazon.awssdk.services.bedrockagentcore.BedrockAgentCoreClient;

import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class AwsAgentCoreIdentityReactiveAutoConfigurationTests {

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(AwsAgentCoreIdentityAutoConfiguration.class))
		.withBean(BedrockAgentCoreClient.class, () -> mock(BedrockAgentCoreClient.class));

	@BeforeEach
	void resetGlobalState() {
		ContextRegistry.getInstance().removeThreadLocalAccessor(WorkloadAccessTokenAccessor.KEY);
		Hooks.disableAutomaticContextPropagation();
	}

	@AfterEach
	void restoreGlobalState() {
		ContextRegistry.getInstance().removeThreadLocalAccessor(WorkloadAccessTokenAccessor.KEY);
		Hooks.disableAutomaticContextPropagation();
	}

	@Test
	void configuresScopedRuntimeTokenAndReactivePropagation() {
		this.contextRunner.run((context) -> {
			assertThat(context).hasSingleBean(WorkloadAccessTokenHolder.class);
			assertThat(context).hasSingleBean(WorkloadAccessTokenCallback.class);
			assertThat(context).hasSingleBean(WorkloadAccessTokenAccessor.class);
			assertThat(context).hasSingleBean(WorkloadAccessTokenContextPropagationCallback.class);
			assertThat(ContextRegistry.getInstance().getThreadLocalAccessors()).extracting((accessor) -> accessor.key())
				.doesNotContain(WorkloadAccessTokenAccessor.KEY);
			assertThat(Hooks.isAutomaticContextPropagationEnabled()).isFalse();
		});
	}

	@Test
	void doesNotChangePreEnabledGlobalReactorHook() {
		Hooks.enableAutomaticContextPropagation();

		this.contextRunner.run((context) -> assertThat(Hooks.isAutomaticContextPropagationEnabled()).isTrue());

		assertThat(Hooks.isAutomaticContextPropagationEnabled()).isTrue();
	}

	@Test
	void isolatesHolderStateAcrossOverlappingApplicationContexts() {
		this.contextRunner.run((firstContext) -> this.contextRunner.run((secondContext) -> {
			WorkloadAccessTokenHolder first = firstContext.getBean(WorkloadAccessTokenHolder.class);
			WorkloadAccessTokenHolder second = secondContext.getBean(WorkloadAccessTokenHolder.class);

			first.set("first");
			assertThat(first.get()).isEqualTo("first");
			assertThat(second.get()).isNull();

			second.set("second");
			assertThat(first.get()).isEqualTo("first");
			assertThat(second.get()).isEqualTo("second");
		}));
	}

	@Test
	void omitsAllContextPropagationWhenMicrometerContextIsAbsent() {
		this.contextRunner.withClassLoader(new FilteredClassLoader("io.micrometer.context")).run((context) -> {
			assertThat(context).hasSingleBean(WorkloadAccessTokenHolder.class);
			assertThat(context).hasSingleBean(WorkloadAccessTokenCallback.class);
			assertThat(context).doesNotHaveBean(WorkloadAccessTokenAccessor.class);
			assertThat(context).doesNotHaveBean(WorkloadAccessTokenContextPropagationCallback.class);
		});
	}

	@Test
	void omitsReactivePropagationWhenOptionalClassesAreAbsent() {
		this.contextRunner.withClassLoader(new FilteredClassLoader(Flux.class, ContextSnapshot.class))
			.run((context) -> {
				assertThat(context).hasSingleBean(WorkloadAccessTokenHolder.class);
				assertThat(context).hasSingleBean(WorkloadAccessTokenCallback.class);
				assertThat(context).doesNotHaveBean(WorkloadAccessTokenContextPropagationCallback.class);
			});
	}

}
