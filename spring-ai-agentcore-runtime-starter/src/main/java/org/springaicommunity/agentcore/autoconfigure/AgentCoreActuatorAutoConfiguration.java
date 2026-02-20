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

package org.springaicommunity.agentcore.autoconfigure;

import org.springaicommunity.agentcore.ping.ActuatorAgentCorePingService;
import org.springaicommunity.agentcore.ping.AgentCorePingService;
import org.springaicommunity.agentcore.ping.AgentCoreTaskTracker;

import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Auto-configuration for AgentCore Actuator integration. Only loaded when Spring Boot
 * Actuator is on the classpath.
 */
@AutoConfiguration
@ConditionalOnClass(HealthEndpoint.class)
public class AgentCoreActuatorAutoConfiguration {

	/**
	 * Provides RequestCounter bean when not already available.
	 */
	@Bean
	@ConditionalOnMissingBean
	public AgentCoreTaskTracker agentCoreTaskTracker() {
		return new AgentCoreTaskTracker();
	}

	/**
	 * Provides Actuator-based ping service when Spring Boot Actuator is available.
	 */
	@Bean
	@ConditionalOnBean(HealthEndpoint.class)
	@ConditionalOnMissingBean(AgentCorePingService.class)
	public AgentCorePingService actuatorAgentCorePingService(HealthEndpoint healthEndpoint,
			AgentCoreTaskTracker agentCoreTaskTracker) {
		return new ActuatorAgentCorePingService(healthEndpoint, agentCoreTaskTracker);
	}

}
