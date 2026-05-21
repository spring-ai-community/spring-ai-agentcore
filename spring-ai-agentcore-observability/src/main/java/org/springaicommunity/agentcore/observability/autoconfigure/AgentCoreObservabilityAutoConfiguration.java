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

package org.springaicommunity.agentcore.observability.autoconfigure;

import io.opentelemetry.api.OpenTelemetry;
import org.springaicommunity.agentcore.annotation.AgentCoreInvocation;
import org.springaicommunity.agentcore.observability.telemetry.AgentCoreInvocationObservabilityAspect;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Registers the AgentCore GenAI enrichment aspect. Activates only when the AgentCore
 * runtime-starter is on the classpath (via {@link AgentCoreInvocation}).
 */
@AutoConfiguration
@EnableConfigurationProperties(AgentCoreObservabilityProperties.class)
@ConditionalOnClass(AgentCoreInvocation.class)
@ConditionalOnProperty(prefix = "spring.ai.agentcore.observability", name = "enabled", havingValue = "true",
		matchIfMissing = true)
public class AgentCoreObservabilityAutoConfiguration {

	private static final String INSTRUMENTATION_SCOPE = "org.springaicommunity.agentcore.observability";

	/**
	 * Scoped OpenTelemetry instruments are created from {@link OpenTelemetry} here rather
	 * than as separate {@code Tracer}/{@code Meter} beans so
	 * {@code @ConditionalOnMissingBean} does not suppress registration when the
	 * OpenTelemetry starter already defines global instruments. Replace this aspect bean
	 * (or supply a custom {@link OpenTelemetry} bean) to override instrumentation.
	 */
	@Bean
	@ConditionalOnMissingBean
	public AgentCoreInvocationObservabilityAspect agentCoreInvocationObservabilityAspect(OpenTelemetry openTelemetry) {
		return new AgentCoreInvocationObservabilityAspect(openTelemetry.getTracer(INSTRUMENTATION_SCOPE),
				openTelemetry.getMeter(INSTRUMENTATION_SCOPE));
	}

}
