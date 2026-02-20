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

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingClass;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Static implementation of AgentCorePingService that provides fallback behavior when
 * Spring Boot Actuator is not present on the classpath.
 *
 * <p>
 * This service always returns a "Healthy" status with HTTP 200, maintaining backward
 * compatibility with the original AgentCore ping behavior.
 * </p>
 *
 * @since 1.0.0
 */
@Service
@ConditionalOnMissingClass("org.springframework.boot.actuate.health.HealthEndpoint")
public class StaticAgentCorePingService implements AgentCorePingService {

	private final AgentCoreTaskTracker agentCoreTaskTracker;

	private final AtomicReference<AgentCorePingResponse> cachedResponse = new AtomicReference<>();

	public StaticAgentCorePingService(AgentCoreTaskTracker agentCoreTaskTracker) {
		this.agentCoreTaskTracker = agentCoreTaskTracker;
	}

	@Override
	public AgentCorePingResponse getPingStatus() {
		try {
			if (agentCoreTaskTracker.getCount() > 0) {
				return updateCachedResponse(PingStatus.HEALTHY_BUSY, HttpStatus.OK);
			}
			else {
				return updateCachedResponse(PingStatus.HEALTHY, HttpStatus.OK);
			}
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

}
