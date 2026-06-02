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

import io.micrometer.observation.ObservationPredicate;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;

import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.observation.ServerRequestObservationContext;

/**
 * Auto-configuration for AgentCore observability. Bridges Micrometer tracing to the ADOT
 * Java agent's OpenTelemetry instance and filters health-check noise from traces.
 *
 * <p>
 * Activates only when both OpenTelemetry API and Micrometer Observation are on the
 * classpath.
 *
 * @author Maximilian Schellhorn
 */
@Configuration
@ConditionalOnClass({ OpenTelemetry.class, ObservationPredicate.class })
public class AgentCoreObservabilityAutoConfiguration {

	/**
	 * Routes the Micrometer tracing bridge through the OpenTelemetry instance installed
	 * by the ADOT Java agent. Without the agent this resolves to a no-op instance.
	 * @return the agent-installed {@link OpenTelemetry} instance
	 */
	@Bean
	@ConditionalOnMissingBean
	OpenTelemetry openTelemetry() {
		return GlobalOpenTelemetry.get();
	}

	/**
	 * Suppresses tracing of AgentCore health checks ({@code /ping}) and actuator
	 * endpoints, which the runtime polls continuously.
	 * @return an {@link ObservationPredicate} that filters health-check observations
	 */
	@Bean
	ObservationPredicate agentCoreHealthCheckFilter() {
		return (name, context) -> {
			if (context instanceof ServerRequestObservationContext serverContext) {
				String uri = serverContext.getCarrier().getRequestURI();
				return !uri.equals("/ping") && !uri.startsWith("/actuator");
			}
			return true;
		};
	}

}
