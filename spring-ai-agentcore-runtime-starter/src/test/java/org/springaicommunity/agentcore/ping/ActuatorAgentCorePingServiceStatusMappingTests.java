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

package org.springaicommunity.agentcore.ping;

import org.junit.jupiter.api.Test;
import org.springaicommunity.agentcore.model.PingStatus;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.boot.actuate.health.Status;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/**
 * Unit tests for ActuatorAgentCorePingService status mapping logic.
 */
class ActuatorAgentCorePingServiceStatusMappingTests {

	@Test
	void shouldMapUpStatusToHealthy() {
		// Given
		var endpoint = mock(HealthEndpoint.class);
		given(endpoint.health()).willReturn(Health.up().build());
		var requestCounter = mock(AgentCoreTaskTracker.class);

		var service = new ActuatorAgentCorePingService(endpoint, requestCounter);

		// When
		var response = service.getPingStatus();

		// Then
		assertThat(response.status()).isEqualTo(PingStatus.HEALTHY);
		assertThat(response.httpStatus()).isEqualTo(HttpStatus.OK);
	}

	@Test
	void shouldMapDownStatusToUnhealthy() {
		// Given
		var endpoint = mock(HealthEndpoint.class);
		given(endpoint.health()).willReturn(Health.down().build());
		var requestCounter = mock(AgentCoreTaskTracker.class);

		var service = new ActuatorAgentCorePingService(endpoint, requestCounter);

		// When
		var response = service.getPingStatus();

		// Then
		assertThat(response.status()).isEqualTo(PingStatus.UNHEALTHY);
		assertThat(response.httpStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
	}

	@Test
	void shouldMapUnknownStatusToUnhealthy() {
		// Given
		var endpoint = mock(HealthEndpoint.class);
		given(endpoint.health()).willReturn(Health.status(Status.UNKNOWN).build());
		var requestCounter = mock(AgentCoreTaskTracker.class);

		var service = new ActuatorAgentCorePingService(endpoint, requestCounter);

		// When
		var response = service.getPingStatus();

		// Then
		assertThat(response.status()).isEqualTo(PingStatus.UNHEALTHY);
		assertThat(response.httpStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
	}

	@Test
	void shouldMapOutOfServiceStatusToUnhealthy() {
		// Given
		var endpoint = mock(HealthEndpoint.class);
		given(endpoint.health()).willReturn(Health.status(Status.OUT_OF_SERVICE).build());
		var requestCounter = mock(AgentCoreTaskTracker.class);

		var service = new ActuatorAgentCorePingService(endpoint, requestCounter);

		// When
		var response = service.getPingStatus();

		// Then
		assertThat(response.status()).isEqualTo(PingStatus.UNHEALTHY);
		assertThat(response.httpStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
	}

	@Test
	void shouldMapCustomStatusToUnhealthy() {
		// Given
		var endpoint = mock(HealthEndpoint.class);
		given(endpoint.health()).willReturn(Health.status("CUSTOM_STATUS").build());
		var requestCounter = mock(AgentCoreTaskTracker.class);

		var service = new ActuatorAgentCorePingService(endpoint, requestCounter);

		// When
		var response = service.getPingStatus();

		// Then
		assertThat(response.status()).isEqualTo(PingStatus.UNHEALTHY);
		assertThat(response.httpStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
	}

	@Test
	void shouldHandleExceptionsWithInternalServerError() {
		// Given
		var endpoint = mock(HealthEndpoint.class);
		given(endpoint.health()).willThrow(new RuntimeException("Health check failed"));
		var requestCounter = mock(AgentCoreTaskTracker.class);

		var service = new ActuatorAgentCorePingService(endpoint, requestCounter);

		// When
		var response = service.getPingStatus();

		// Then
		assertThat(response.status()).isEqualTo(PingStatus.UNHEALTHY);
		assertThat(response.httpStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
	}

	@Test
	void shouldReturnCurrentTimestamp() {
		// Given
		var endpoint = mock(HealthEndpoint.class);
		given(endpoint.health()).willReturn(Health.up().build());
		var requestCounter = mock(AgentCoreTaskTracker.class);

		var service = new ActuatorAgentCorePingService(endpoint, requestCounter);
		var beforeCall = System.currentTimeMillis() / 1000;

		// When
		var response = service.getPingStatus();
		var afterCall = System.currentTimeMillis() / 1000;

		// Then
		assertThat(response.timeOfLastUpdate() >= beforeCall).isTrue();
		assertThat(response.timeOfLastUpdate() <= afterCall).isTrue();
	}

}
