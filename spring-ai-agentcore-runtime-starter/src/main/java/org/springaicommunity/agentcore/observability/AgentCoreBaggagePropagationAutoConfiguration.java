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

package org.springaicommunity.agentcore.observability;

import io.opentelemetry.api.baggage.Baggage;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;

/**
 * Auto-configuration that registers the {@link AgentCoreSessionBaggageFilter} when
 * OpenTelemetry is on the classpath.
 *
 * <p>
 * The filter reads {@code X-Amzn-Bedrock-AgentCore-Runtime-Session-Id} from inbound
 * requests and injects it into OTel Baggage as {@code session.id}, enabling downstream
 * propagation via W3C baggage header and span enrichment via
 * {@code SessionBaggageSpanProcessor}.
 *
 * <p>
 * Disable with {@code spring.ai.agentcore.baggage.enabled=false}.
 *
 * @author Vaquar Khan
 */
@AutoConfiguration
@ConditionalOnClass(Baggage.class)
@ConditionalOnProperty(name = "spring.ai.agentcore.baggage.enabled", havingValue = "true", matchIfMissing = true)
public class AgentCoreBaggagePropagationAutoConfiguration {

	private static final int FILTER_ORDER = Ordered.HIGHEST_PRECEDENCE + 10;

	/**
	 * Registers the session baggage filter for the {@code /invocations} endpoint.
	 * @return the filter registration bean
	 */
	@Bean
	public FilterRegistrationBean<AgentCoreSessionBaggageFilter> agentCoreSessionBaggageFilter() {
		FilterRegistrationBean<AgentCoreSessionBaggageFilter> registration = new FilterRegistrationBean<>();
		registration.setFilter(new AgentCoreSessionBaggageFilter());
		registration.addUrlPatterns("/invocations");
		registration.setOrder(FILTER_ORDER);
		return registration;
	}

}
