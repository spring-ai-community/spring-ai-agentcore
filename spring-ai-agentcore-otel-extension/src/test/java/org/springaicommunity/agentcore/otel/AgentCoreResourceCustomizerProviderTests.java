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

import java.util.Map;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AgentCoreResourceCustomizerProviderTests {

	@Test
	void disabledWhenEnvironmentVariableIsNotSet() {
		assertThat(AgentCoreResourceCustomizerProvider.isAgentObservabilityEnabled()).isFalse();
	}

	@Test
	void defaultPropertiesConfigureObservabilityDefaults() {
		Map<String, String> props = new AgentCoreResourceCustomizerProvider().getDefaultProperties();

		assertThat(props).containsEntry("otel.traces.sampler", "parentbased_always_on")
			.containsEntry("otel.traces.exporter", "otlp")
			.containsEntry("otel.logs.exporter", "otlp")
			.containsEntry("otel.exporter.otlp.protocol", "http/protobuf")
			.containsEntry("otel.metrics.exporter", "awsemf")
			.containsEntry("otel.resource.providers.aws.enabled", "false")
			.containsEntry("otel.aws.application.signals.enabled", "false")
			.containsEntry("otel.instrumentation.http-url-connection.enabled", "false")
			.containsEntry("otel.instrumentation.tomcat.enabled", "false")
			.containsEntry("otel.instrumentation.servlet.enabled", "false");
	}

	@Test
	void defaultPropertiesOmitEndpointsWhenRegionNotSet() {
		Map<String, String> props = new AgentCoreResourceCustomizerProvider().getDefaultProperties();

		assertThat(props).doesNotContainKey("otel.exporter.otlp.traces.endpoint")
			.doesNotContainKey("otel.exporter.otlp.logs.endpoint");
	}

	@Test
	void resourceContainsGenAiAgentServiceType() {
		Resource base = Resource.getDefault();
		Resource merged = base
			.merge(Resource.create(Attributes.of(AttributeKey.stringKey("aws.service.type"), "gen_ai_agent")));

		InMemorySpanExporter exporter = InMemorySpanExporter.create();
		SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
			.setResource(merged)
			.addSpanProcessor(SimpleSpanProcessor.create(exporter))
			.build();

		Tracer tracer = tracerProvider.get("test");
		tracer.spanBuilder("test").startSpan().end();

		assertThat(exporter.getFinishedSpanItems()).hasSize(1);
		assertThat(exporter.getFinishedSpanItems()
			.get(0)
			.getResource()
			.getAttribute(AttributeKey.stringKey("aws.service.type"))).isEqualTo("gen_ai_agent");

		tracerProvider.close();
	}

}
