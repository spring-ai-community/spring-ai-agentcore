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

import java.util.List;
import java.util.Optional;

import io.opentelemetry.sdk.trace.data.SpanData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springaicommunity.agentcore.observability.sample.AgentCoreObservabilitySampleApplication;
import org.springaicommunity.agentcore.observability.telemetry.GenAiTelemetrySupport;
import org.springaicommunity.agentcore.observability.testsupport.OtelInMemorySpanExporterTestConfig;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = AgentCoreObservabilitySampleApplication.class)
@AutoConfigureMockMvc
@Import(OtelInMemorySpanExporterTestConfig.class)
@TestPropertySource(
		properties = { "otel.traces.exporter=logging", "otel.metrics.exporter=none", "otel.logs.exporter=none" })
class AgentCoreObservabilityIntegrationTests {

	@Autowired
	private MockMvc mockMvc;

	@AfterEach
	void resetExporter() {
		OtelInMemorySpanExporterTestConfig.SPAN_EXPORTER.reset();
	}

	@Test
	void invocationsEmitsGenAiAttributesAndHeadersWithoutContentEvents() throws Exception {
		String body = "Hello from integration test";

		this.mockMvc
			.perform(MockMvcRequestBuilders.post("/invocations")
				.contentType(MediaType.TEXT_PLAIN)
				.header(GenAiTelemetrySupport.HTTP_HEADER_AGENTCORE_SESSION_ID, "sess-int-1")
				.header(GenAiTelemetrySupport.HTTP_HEADER_AMZN_REQUEST_ID, "req-int-1")
				.content(body))
			.andExpect(MockMvcResultMatchers.status().isOk());

		List<SpanData> spans = OtelInMemorySpanExporterTestConfig.SPAN_EXPORTER.getFinishedSpanItems();
		Optional<SpanData> genAi = spans.stream()
			.filter((s) -> s.getAttributes().get(GenAiTelemetrySupport.GEN_AI_PROVIDER_NAME) != null)
			.findFirst();

		assertThat(genAi).isPresent();
		SpanData span = genAi.orElseThrow();
		assertThat(span.getAttributes().get(GenAiTelemetrySupport.GEN_AI_PROVIDER_NAME))
			.isEqualTo(GenAiTelemetrySupport.PROVIDER_AWS_BEDROCK);
		assertThat(span.getAttributes().get(GenAiTelemetrySupport.AWS_BEDROCK_AGENTCORE_SESSION_ID))
			.isEqualTo("sess-int-1");
		assertThat(span.getAttributes().get(GenAiTelemetrySupport.AWS_REQUEST_ID)).isEqualTo("req-int-1");
		assertThat(span.getAttributes().get(GenAiTelemetrySupport.GEN_AI_USAGE_INPUT_TOKENS)).isEqualTo(45L);
		assertThat(span.getAttributes().get(GenAiTelemetrySupport.GEN_AI_USAGE_OUTPUT_TOKENS)).isEqualTo(7L);

		boolean anyContentEvent = span.getEvents().stream().anyMatch((e) -> e.getName().startsWith("gen_ai.content."));
		assertThat(anyContentEvent).isFalse();
	}

}
