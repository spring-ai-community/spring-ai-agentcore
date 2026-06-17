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

package org.springaicommunity.agentcore.observability;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.core.Ordered;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link AgentCoreBaggagePropagationAutoConfiguration}.
 *
 * @author Vaquar Khan
 */
class AgentCoreBaggagePropagationAutoConfigurationTests {

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(AgentCoreBaggagePropagationAutoConfiguration.class));

	@Test
	@DisplayName("Should register filter when OTel Baggage is on classpath")
	void shouldRegisterFilterWhenOtelPresent() {
		this.contextRunner.run((context) -> {
			assertThat(context).hasBean("agentCoreSessionBaggageFilter");
			assertThat(context).hasSingleBean(FilterRegistrationBean.class);
		});
	}

	@Test
	@DisplayName("Should not register filter when explicitly disabled")
	void shouldNotRegisterFilterWhenDisabled() {
		this.contextRunner.withPropertyValues("spring.ai.agentcore.baggage.enabled=false")
			.run((context) -> assertThat(context).doesNotHaveBean(FilterRegistrationBean.class));
	}

	@Test
	@DisplayName("Should register filter when property is explicitly true")
	void shouldRegisterFilterWhenExplicitlyEnabled() {
		this.contextRunner.withPropertyValues("spring.ai.agentcore.baggage.enabled=true")
			.run((context) -> assertThat(context).hasSingleBean(FilterRegistrationBean.class));
	}

	@Test
	@DisplayName("Filter registration should target /invocations URL pattern")
	@SuppressWarnings("unchecked")
	void shouldTargetInvocationsUrlPattern() {
		this.contextRunner.run((context) -> {
			FilterRegistrationBean<AgentCoreSessionBaggageFilter> registration = context
				.getBean("agentCoreSessionBaggageFilter", FilterRegistrationBean.class);
			assertThat(registration.getUrlPatterns()).containsExactly("/invocations");
		});
	}

	@Test
	@DisplayName("Filter should have high precedence order")
	@SuppressWarnings("unchecked")
	void shouldHaveHighPrecedenceOrder() {
		this.contextRunner.run((context) -> {
			FilterRegistrationBean<AgentCoreSessionBaggageFilter> registration = context
				.getBean("agentCoreSessionBaggageFilter", FilterRegistrationBean.class);
			assertThat(registration.getOrder()).isEqualTo(Ordered.HIGHEST_PRECEDENCE + 10);
		});
	}

}
