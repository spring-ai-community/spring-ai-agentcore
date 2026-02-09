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

import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for StaticAgentCorePingService.
 */
class StaticAgentCorePingServiceTest {

	@Test
	void shouldReturnHealthyStatus() {
		// Given
		var agentCoreTaskTracker = mock(AgentCoreTaskTracker.class);
		var service = new StaticAgentCorePingService(agentCoreTaskTracker);

		// When
		var response = service.getPingStatus();

		// Then
		assertThat(response.status()).isEqualTo(PingStatus.HEALTHY);
		assertThat(response.httpStatus()).isEqualTo(HttpStatus.OK);
		assertThat(response.timeOfLastUpdate()).isGreaterThan(0);
	}

	@Test
	void shouldReturnCurrentTimestamp() {
		// Given
		var agentCoreTaskTracker = mock(AgentCoreTaskTracker.class);
		var service = new StaticAgentCorePingService(agentCoreTaskTracker);
		var beforeCall = System.currentTimeMillis() / 1000;

		// When
		var response = service.getPingStatus();
		var afterCall = System.currentTimeMillis() / 1000;

		// Then
		assertThat(response.timeOfLastUpdate()).isGreaterThanOrEqualTo(beforeCall);
		assertThat(response.timeOfLastUpdate()).isLessThanOrEqualTo(afterCall);
	}

	@Test
	void shouldReturnConsistentFormat() {
		// Given
		var agentCoreTaskTracker = mock(AgentCoreTaskTracker.class);
		var service = new StaticAgentCorePingService(agentCoreTaskTracker);

		// When
		var response1 = service.getPingStatus();
		var response2 = service.getPingStatus();

		// Then
		assertThat(response1.status()).isEqualTo(response2.status());
		assertThat(response1.httpStatus()).isEqualTo(response2.httpStatus());
		// Timestamps may differ slightly
		assertThat(response2.timeOfLastUpdate()).isGreaterThanOrEqualTo(response1.timeOfLastUpdate());
	}

	@Test
	void shouldReturnHealthyBusy() {
		// Given
		var agentCoreTaskTracker = mock(AgentCoreTaskTracker.class);
		when(agentCoreTaskTracker.getCount()).thenReturn(1L);
		var service = new StaticAgentCorePingService(agentCoreTaskTracker);

		// When
		var response1 = service.getPingStatus();

		// Then
		assertThat(response1.status()).isEqualTo(PingStatus.HEALTHY_BUSY);
	}

}
