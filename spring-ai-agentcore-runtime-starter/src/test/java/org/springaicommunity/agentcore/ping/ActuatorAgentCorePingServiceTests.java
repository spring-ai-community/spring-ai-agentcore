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

import org.springframework.boot.health.actuate.endpoint.HealthDescriptor;
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;
import org.springframework.boot.health.contributor.Status;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;

/**
 * Unit tests for ActuatorAgentCorePingService.
 */
class ActuatorAgentCorePingServiceTests {

	private static HealthDescriptor descriptor(Status status) {
		return TestHealthDescriptors.of(status);
	}

	@Test
	void shouldReturnHealthyForUpStatus() {
		// Given
		var endpoint = mock(HealthEndpoint.class);
		given(endpoint.health()).willReturn(descriptor(Status.UP));
		var requestCounter = mock(AgentCoreTaskTracker.class);

		var service = new ActuatorAgentCorePingService(endpoint, requestCounter);

		// When
		var response = service.getPingStatus();

		// Then
		assertThat(response.status()).isEqualTo(PingStatus.HEALTHY);
		assertThat(response.httpStatus()).isEqualTo(HttpStatus.OK);
		assertThat(response.timeOfLastUpdate() > 0).isTrue();
	}

	@Test
	void shouldReturnUnhealthyForDownStatus() {
		// Given
		var endpoint = mock(HealthEndpoint.class);
		given(endpoint.health()).willReturn(descriptor(Status.DOWN));
		var requestCounter = mock(AgentCoreTaskTracker.class);

		var service = new ActuatorAgentCorePingService(endpoint, requestCounter);

		// When
		var response = service.getPingStatus();

		// Then
		assertThat(response.status()).isEqualTo(PingStatus.UNHEALTHY);
		assertThat(response.httpStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
		assertThat(response.timeOfLastUpdate() > 0).isTrue();
	}

	@Test
	void shouldHandleExceptions() {
		// Given
		var endpoint = mock(HealthEndpoint.class);
		given(endpoint.health()).willThrow(new RuntimeException("Test error"));
		var requestCounter = mock(AgentCoreTaskTracker.class);

		var service = new ActuatorAgentCorePingService(endpoint, requestCounter);

		// When
		var response = service.getPingStatus();

		// Then
		assertThat(response.status()).isEqualTo(PingStatus.UNHEALTHY);
		assertThat(response.httpStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
		assertThat(response.timeOfLastUpdate() > 0).isTrue();
	}

	@Test
	void shouldReturnHealthyBusyWhenActiveRequests() {
		// Given
		var endpoint = mock(HealthEndpoint.class);
		given(endpoint.health()).willReturn(descriptor(Status.UP));
		var requestCounter = mock(AgentCoreTaskTracker.class);
		given(requestCounter.getCount()).willReturn(5L);

		var service = new ActuatorAgentCorePingService(endpoint, requestCounter);

		// When
		var response = service.getPingStatus();

		// Then
		assertThat(response.status()).isEqualTo(PingStatus.HEALTHY_BUSY);
		assertThat(response.httpStatus()).isEqualTo(HttpStatus.OK);
		assertThat(response.timeOfLastUpdate() > 0).isTrue();
	}

	@Test
	void shouldDelegateToHealthEndpoint() {
		// Given
		var endpoint = mock(HealthEndpoint.class);
		given(endpoint.health()).willReturn(descriptor(Status.UP));
		var requestCounter = mock(AgentCoreTaskTracker.class);

		var service = new ActuatorAgentCorePingService(endpoint, requestCounter);

		// When
		service.getPingStatus();

		// Then
		then(endpoint).should().health();
	}

}
