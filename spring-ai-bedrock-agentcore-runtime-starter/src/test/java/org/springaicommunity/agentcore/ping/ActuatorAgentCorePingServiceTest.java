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

package org.springaicommunity.agentcore.ping;

import org.junit.jupiter.api.Test;
import org.springaicommunity.agentcore.model.PingStatus;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.http.HttpStatus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for ActuatorAgentCorePingService.
 */
class ActuatorAgentCorePingServiceTest {

	@Test
	void shouldReturnHealthyForUpStatus() {
		// Given
		var endpoint = mock(HealthEndpoint.class);
		when(endpoint.health()).thenReturn(Health.up().build());
		var requestCounter = mock(AgentCoreTaskTracker.class);

		var service = new ActuatorAgentCorePingService(endpoint, requestCounter);

		// When
		var response = service.getPingStatus();

		// Then
		assertEquals(PingStatus.HEALTHY, response.status());
		assertEquals(HttpStatus.OK, response.httpStatus());
		assertTrue(response.timeOfLastUpdate() > 0);
	}

	@Test
	void shouldReturnUnhealthyForDownStatus() {
		// Given
		var endpoint = mock(HealthEndpoint.class);
		when(endpoint.health()).thenReturn(Health.down().build());
		var requestCounter = mock(AgentCoreTaskTracker.class);

		var service = new ActuatorAgentCorePingService(endpoint, requestCounter);

		// When
		var response = service.getPingStatus();

		// Then
		assertEquals(PingStatus.UNHEALTHY, response.status());
		assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.httpStatus());
		assertTrue(response.timeOfLastUpdate() > 0);
	}

	@Test
	void shouldHandleExceptions() {
		// Given
		var endpoint = mock(HealthEndpoint.class);
		when(endpoint.health()).thenThrow(new RuntimeException("Test error"));
		var requestCounter = mock(AgentCoreTaskTracker.class);

		var service = new ActuatorAgentCorePingService(endpoint, requestCounter);

		// When
		var response = service.getPingStatus();

		// Then
		assertEquals(PingStatus.UNHEALTHY, response.status());
		assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.httpStatus());
		assertTrue(response.timeOfLastUpdate() > 0);
	}

	@Test
	void shouldReturnHealthyBusyWhenActiveRequests() {
		// Given
		var endpoint = mock(HealthEndpoint.class);
		when(endpoint.health()).thenReturn(Health.up().build());
		var requestCounter = mock(AgentCoreTaskTracker.class);
		when(requestCounter.getCount()).thenReturn(5L);

		var service = new ActuatorAgentCorePingService(endpoint, requestCounter);

		// When
		var response = service.getPingStatus();

		// Then
		assertEquals(PingStatus.HEALTHY_BUSY, response.status());
		assertEquals(HttpStatus.OK, response.httpStatus());
		assertTrue(response.timeOfLastUpdate() > 0);
	}

	@Test
	void shouldDelegateToHealthEndpoint() {
		// Given
		var endpoint = mock(HealthEndpoint.class);
		when(endpoint.health()).thenReturn(Health.up().build());
		var requestCounter = mock(AgentCoreTaskTracker.class);

		var service = new ActuatorAgentCorePingService(endpoint, requestCounter);

		// When
		service.getPingStatus();

		// Then
		verify(endpoint).health();
	}

}
