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

package org.springaicommunity.agentcore.otel;

import java.util.HashMap;
import java.util.Map;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizer;
import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizerProvider;
import io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProviderBuilder;

/**
 * OTel Java agent extension for Amazon Bedrock AgentCore Runtime. Activated when
 * {@code AGENT_OBSERVABILITY_ENABLED=true} (injected by AgentCore into every container).
 *
 * <p>
 * Mirrors what the Python ADOT auto-configurator does:
 * <ul>
 * <li>Adds {@code aws.service.type=gen_ai_agent} resource attribute (required for the
 * GenAI Observability "Bedrock AgentCore" dashboard tab)</li>
 * <li>Propagates {@code session.id} from baggage to span attributes (groups traces by
 * session in the dashboard)</li>
 * <li>Sets {@code parentbased_always_on} sampler (100% span capture for agent
 * observability)</li>
 * <li>Disables AWS cloud resource detectors (prevents {@code cloud.platform=aws_ec2} from
 * overriding AgentCore's injected {@code cloud.platform=aws_bedrock_agentcore})</li>
 * </ul>
 *
 * <p>
 * Usage: set
 * {@code OTEL_JAVAAGENT_EXTENSIONS=/path/to/spring-ai-agentcore-otel-extension.jar}
 *
 * @author Maximilian Schellhorn
 */
public class AgentCoreResourceCustomizerProvider implements AutoConfigurationCustomizerProvider {

	private static final AttributeKey<String> AWS_SERVICE_TYPE = AttributeKey.stringKey("aws.service.type");

	private static final String AGENT_OBSERVABILITY_ENABLED = "AGENT_OBSERVABILITY_ENABLED";

	@Override
	public void customize(AutoConfigurationCustomizer autoConfiguration) {
		if (!isAgentObservabilityEnabled()) {
			return;
		}

		autoConfiguration.addResourceCustomizer(
				(resource, config) -> resource.merge(Resource.create(Attributes.of(AWS_SERVICE_TYPE, "gen_ai_agent"))));

		autoConfiguration.addTracerProviderCustomizer(this::addSessionBaggageProcessor);

		autoConfiguration.addPropertiesSupplier(this::getDefaultProperties);
	}

	private SdkTracerProviderBuilder addSessionBaggageProcessor(SdkTracerProviderBuilder builder,
			ConfigProperties config) {
		return builder.addSpanProcessor(new SessionBaggageSpanProcessor());
	}

	private Map<String, String> getDefaultProperties() {
		Map<String, String> properties = new HashMap<>();
		// 100% span capture for agent observability
		properties.put("otel.traces.sampler", "parentbased_always_on");
		// Disable AWS resource detectors so they don't override AgentCore's injected
		// cloud.platform=aws_bedrock_agentcore with cloud.platform=aws_ec2
		properties.put("otel.resource.providers.aws.enabled", "false");
		// Disable Application Signals to avoid duplicate cost with Transaction Search
		properties.put("otel.aws.application.signals.enabled", "false");
		// Disable http-url-connection instrumentation to suppress IMDS credential
		// fetching noise (169.254.169.254) — equivalent to Python disabling
		// urllib3/requests/http
		properties.put("otel.instrumentation.http-url-connection.enabled", "false");
		// Disable Tomcat/Servlet server spans — Spring MVC observation provides the
		// server span at a higher level. Equivalent to Python disabling low-level http
		// server instrumentation to avoid duplicates with framework spans.
		properties.put("otel.instrumentation.tomcat.enabled", "false");
		properties.put("otel.instrumentation.servlet.enabled", "false");
		return properties;
	}

	private static boolean isAgentObservabilityEnabled() {
		String value = System.getenv(AGENT_OBSERVABILITY_ENABLED);
		return "true".equalsIgnoreCase(value);
	}

}
