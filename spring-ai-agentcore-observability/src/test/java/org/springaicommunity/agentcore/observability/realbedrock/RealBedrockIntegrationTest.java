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

package org.springaicommunity.agentcore.observability.realbedrock;

import static org.assertj.core.api.Assertions.assertThat;

import io.opentelemetry.sdk.trace.data.SpanData;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springaicommunity.agentcore.observability.RealBedrockTestApplication;
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

/**
 * Optional live AWS Bedrock tests. Skipped unless {@code RUN_REAL_BEDROCK_TESTS=true} and
 * AWS credentials are available ({@link RealBedrockConditions}). Set
 * {@code SKIP_REAL_BEDROCK_TESTS=true} to force-disable.
 */
@SpringBootTest(classes = RealBedrockTestApplication.class)
@AutoConfigureMockMvc
@Import(OtelInMemorySpanExporterTestConfig.class)
@TestPropertySource(properties = { "otel.traces.exporter=logging", "otel.metrics.exporter=none",
		"otel.logs.exporter=none", "spring.ai.bedrock.aws.region=us-east-1",
		"spring.ai.bedrock.converse.chat.options.model=anthropic.claude-3-haiku-20240307-v1:0" })
@EnabledIf("org.springaicommunity.agentcore.observability.realbedrock.RealBedrockConditions#liveAwsConfigured")
class RealBedrockIntegrationTest {

	@Autowired
	private MockMvc mockMvc;

	@AfterEach
	void resetExporter() {
		OtelInMemorySpanExporterTestConfig.SPAN_EXPORTER.reset();
	}

	@Test
	void realBedrockCallProducesGenAiSpanWithTokenCounts() throws Exception {
		mockMvc
			.perform(MockMvcRequestBuilders.post("/invocations")
				.contentType(MediaType.TEXT_PLAIN)
				.content("What is 2 plus 2? Answer with a single digit only."))
			.andExpect(MockMvcResultMatchers.status().isOk());

		List<SpanData> spans = OtelInMemorySpanExporterTestConfig.SPAN_EXPORTER.getFinishedSpanItems();
		Optional<SpanData> genAi = spans.stream()
			.filter(s -> s.getAttributes().get(GenAiTelemetrySupport.GEN_AI_PROVIDER_NAME) != null)
			.findFirst();

		assertThat(genAi).isPresent();
		SpanData span = genAi.orElseThrow();
		assertThat(span.getAttributes().get(GenAiTelemetrySupport.GEN_AI_PROVIDER_NAME))
			.isEqualTo(GenAiTelemetrySupport.PROVIDER_AWS_BEDROCK);
		assertThat(span.getAttributes().get(GenAiTelemetrySupport.GEN_AI_REQUEST_MODEL))
			.isEqualTo("anthropic.claude-3-haiku-20240307-v1:0");
		assertThat(span.getAttributes().get(GenAiTelemetrySupport.GEN_AI_RESPONSE_MODEL))
			.isEqualTo("anthropic.claude-3-haiku-20240307-v1:0");

		Long inTok = span.getAttributes().get(GenAiTelemetrySupport.GEN_AI_USAGE_INPUT_TOKENS);
		Long outTok = span.getAttributes().get(GenAiTelemetrySupport.GEN_AI_USAGE_OUTPUT_TOKENS);
		assertThat(inTok).isNotNull();
		assertThat(outTok).isNotNull();
		assertThat(inTok).isGreaterThan(0L);
		assertThat(outTok).isGreaterThan(0L);
	}

	@Test
	void realBedrockCallDoesNotEmitGenAiContentEventsFromObservabilityModule() throws Exception {
		String body = "Hello — reply with OK only.";
		mockMvc.perform(MockMvcRequestBuilders.post("/invocations").contentType(MediaType.TEXT_PLAIN).content(body))
			.andExpect(MockMvcResultMatchers.status().isOk());

		List<SpanData> spans = OtelInMemorySpanExporterTestConfig.SPAN_EXPORTER.getFinishedSpanItems();
		Optional<SpanData> genAi = spans.stream()
			.filter(s -> s.getAttributes().get(GenAiTelemetrySupport.GEN_AI_PROVIDER_NAME) != null)
			.findFirst();

		assertThat(genAi).isPresent();
		SpanData span = genAi.orElseThrow();
		boolean anyContentEvent = span.getEvents().stream().anyMatch(e -> e.getName().startsWith("gen_ai.content."));
		assertThat(anyContentEvent).isFalse();
	}

}
