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

package org.springaicommunity.agentcore.integration;

import org.junit.jupiter.api.Test;
import org.springaicommunity.agentcore.ping.ActuatorAgentCorePingService;
import org.springaicommunity.agentcore.ping.AgentCorePingService;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that with Actuator on the classpath /ping reflects the application health
 * (gh-237).
 */
@AutoConfigureTestRestTemplate
@SpringBootTest(classes = EndToEndActuatorPingIntegrationTests.TestApp.class,
		webEnvironment = WebEnvironment.RANDOM_PORT)
class EndToEndActuatorPingIntegrationTests {

	@LocalServerPort
	private int port;

	@Autowired
	private TestRestTemplate restTemplate;

	@Autowired
	private AgentCorePingService pingService;

	@Test
	void shouldUseActuatorPingService() {
		assertThat(this.pingService).isInstanceOf(ActuatorAgentCorePingService.class);
	}

	@Test
	void shouldReportUnhealthyWhenActuatorHealthIsDown() {
		var response = this.restTemplate.getForEntity("http://localhost:" + this.port + "/ping", String.class);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
		assertThat(response.getBody()).contains("\"status\":\"Unhealthy\"");
	}

	@SpringBootApplication(scanBasePackages = "org.springaicommunity.agentcore.autoconfigure")
	static class TestApp {

		@Bean
		HealthIndicator forceDown() {
			return () -> Health.down().build();
		}

	}

}
