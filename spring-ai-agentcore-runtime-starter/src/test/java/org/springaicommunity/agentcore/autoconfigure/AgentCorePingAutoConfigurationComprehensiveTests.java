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

package org.springaicommunity.agentcore.autoconfigure;

import org.junit.jupiter.api.Test;
import org.springaicommunity.agentcore.model.AgentCorePingResponse;
import org.springaicommunity.agentcore.model.PingStatus;
import org.springaicommunity.agentcore.ping.ActuatorAgentCorePingService;
import org.springaicommunity.agentcore.ping.AgentCorePingService;
import org.springaicommunity.agentcore.ping.StaticAgentCorePingService;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/**
 * Comprehensive tests for AgentCorePingAutoConfiguration.
 */
class AgentCorePingAutoConfigurationComprehensiveTests {

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner().withConfiguration(
			AutoConfigurations.of(AgentCorePingAutoConfiguration.class, AgentCoreActuatorAutoConfiguration.class));

	@Test
	void shouldAutoConfigureStaticPingServiceWhenActuatorNotPresent() {
		this.contextRunner.run((context) -> {
			assertThat(context).hasSingleBean(AgentCorePingService.class);
			assertThat(context).getBean(AgentCorePingService.class).isInstanceOf(StaticAgentCorePingService.class);
		});
	}

	@Test
	void shouldAutoConfigureActuatorPingServiceWhenActuatorPresent() {
		this.contextRunner.withUserConfiguration(ActuatorConfiguration.class).run((context) -> {
			assertThat(context).hasSingleBean(AgentCorePingService.class);
			assertThat(context).getBean(AgentCorePingService.class).isInstanceOf(ActuatorAgentCorePingService.class);
		});
	}

	@Test
	void shouldNotOverrideCustomPingService() {
		this.contextRunner.withUserConfiguration(CustomPingServiceConfiguration.class).run((context) -> {
			assertThat(context).hasSingleBean(AgentCorePingService.class);
			assertThat(context).getBean(AgentCorePingService.class).isInstanceOf(CustomPingService.class);
		});
	}

	@Test
	void shouldReturnHealthyResponseFromStaticService() {
		this.contextRunner.run((context) -> {
			var pingService = context.getBean(AgentCorePingService.class);
			var response = pingService.getPingStatus();

			assertThat(response.status()).isEqualTo(PingStatus.HEALTHY);
			assertThat(response.timeOfLastUpdate()).isPositive();
		});
	}

	@Test
	void shouldReturnHealthyResponseFromActuatorService() {
		this.contextRunner.withUserConfiguration(ActuatorConfiguration.class).run((context) -> {
			var pingService = context.getBean(AgentCorePingService.class);
			var response = pingService.getPingStatus();

			assertThat(response.status()).isEqualTo(PingStatus.HEALTHY);
			assertThat(response.timeOfLastUpdate()).isPositive();
		});
	}

	@Test
	void shouldHaveCorrectBeanNames() {
		this.contextRunner.run((context) -> {
			assertThat(context).hasBean("staticAgentCorePingService");
			assertThat(context).doesNotHaveBean("actuatorAgentCorePingService");
		});
	}

	@Test
	void shouldHaveCorrectBeanNamesWithActuator() {
		this.contextRunner.withUserConfiguration(ActuatorConfiguration.class).run((context) -> {
			assertThat(context).hasBean("actuatorAgentCorePingService");
			assertThat(context).doesNotHaveBean("staticAgentCorePingService");
		});
	}

	// Test configurations
	@Configuration
	static class ActuatorConfiguration {

		@Bean
		HealthEndpoint healthEndpoint() {
			var endpoint = mock(HealthEndpoint.class);
			given(endpoint.health()).willReturn(Health.up().build());
			return endpoint;
		}

	}

	@Configuration
	static class CustomPingServiceConfiguration {

		@Bean
		AgentCorePingService customPingService() {
			return new CustomPingService();
		}

	}

	// Custom implementation for testing
	static class CustomPingService implements AgentCorePingService {

		@Override
		public AgentCorePingResponse getPingStatus() {
			return new AgentCorePingResponse(PingStatus.HEALTHY, HttpStatus.OK, 999L);
		}

	}

}
