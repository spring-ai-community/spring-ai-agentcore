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

import java.util.concurrent.atomic.AtomicReference;

import org.springaicommunity.agentcore.model.AgentCorePingResponse;
import org.springaicommunity.agentcore.model.PingStatus;

import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Actuator-based implementation of AgentCorePingService.
 */
@Service
public class ActuatorAgentCorePingService implements AgentCorePingService {

	private final HealthEndpoint healthEndpoint;

	private final AtomicReference<AgentCorePingResponse> cachedResponse = new AtomicReference<>();

	private final AgentCoreTaskTracker agentCoreTaskTracker;

	public ActuatorAgentCorePingService(HealthEndpoint healthEndpoint, AgentCoreTaskTracker agentCoreTaskTracker) {
		this.healthEndpoint = healthEndpoint;
		this.agentCoreTaskTracker = agentCoreTaskTracker;
	}

	@Override
	public AgentCorePingResponse getPingStatus() {
		try {
			var health = healthEndpoint.health();
			var mapping = mapActuatorStatus(health.getStatus().getCode());
			return updateCachedResponse(mapping.status(), mapping.httpStatus());
		}
		catch (Exception e) {
			return updateCachedResponse(PingStatus.UNHEALTHY, HttpStatus.INTERNAL_SERVER_ERROR);
		}
	}

	private AgentCorePingResponse updateCachedResponse(PingStatus status, HttpStatus httpStatus) {
		return cachedResponse.updateAndGet(current -> {
			if (current == null || !current.status().equals(status)) {
				return new AgentCorePingResponse(status, httpStatus, System.currentTimeMillis() / 1000);
			}
			return current;
		});
	}

	private StatusMapping mapActuatorStatus(String statusCode) {
		return switch (statusCode) {
			case "UP" ->
				(agentCoreTaskTracker.getCount() > 0) ? new StatusMapping(PingStatus.HEALTHY_BUSY, HttpStatus.OK)
						: new StatusMapping(PingStatus.HEALTHY, HttpStatus.OK);
			default -> new StatusMapping(PingStatus.UNHEALTHY, HttpStatus.SERVICE_UNAVAILABLE);
		};
	}

	private record StatusMapping(PingStatus status, HttpStatus httpStatus) {
	}

}
