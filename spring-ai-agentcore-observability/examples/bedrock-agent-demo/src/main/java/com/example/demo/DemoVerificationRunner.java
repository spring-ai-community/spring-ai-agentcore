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

package com.example.demo;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springaicommunity.agentcore.observability.telemetry.GenAiTelemetrySupport;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.stereotype.Component;

import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.OpenTelemetrySdk;

/**
 * Sends one AgentCore invocation and validates exported spans locally.
 */
@Component
public class DemoVerificationRunner implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(DemoVerificationRunner.class);

	private final ApplicationContext applicationContext;

	public DemoVerificationRunner(ApplicationContext applicationContext) {
		this.applicationContext = applicationContext;
	}

	@Override
	public void run(ApplicationArguments args) {
		if (!(applicationContext instanceof ServletWebServerApplicationContext webApp)) {
			log.error("Expected a servlet web server application context");
			SpringApplication.exit(applicationContext, () -> 1);
			return;
		}
		int port = webApp.getWebServer().getPort();
		DemoOpenTelemetryExporterConfig.SPAN_EXPORTER.reset();

		String prompt = "Say hello back in one short sentence.";
		org.springframework.web.client.RestClient.create("http://127.0.0.1:" + port)
			.post()
			.uri("/invocations")
			.header(GenAiTelemetrySupport.HTTP_HEADER_AGENTCORE_SESSION_ID, "demo-session-1")
			.header(GenAiTelemetrySupport.HTTP_HEADER_AMZN_REQUEST_ID, "demo-request-1")
			.contentType(MediaType.TEXT_PLAIN)
			.body(prompt)
			.retrieve()
			.toBodilessEntity();

		forceFlushTracerIfAvailable();

		int code = runAssertions(port) ? 0 : 1;
		SpringApplication.exit(applicationContext, () -> code);
	}

	boolean runAssertions(@SuppressWarnings("unused") int port) {
		List<SpanData> spans = DemoOpenTelemetryExporterConfig.SPAN_EXPORTER.getFinishedSpanItems();
		Optional<SpanData> genAi = spans.stream()
			.filter(s -> s.getAttributes().get(GenAiTelemetrySupport.GEN_AI_PROVIDER_NAME) != null)
			.findFirst();

		boolean pass = assertBool("span with gen_ai.provider.name exists", genAi.isPresent())
				&& assertBool("provider is aws.bedrock",
						genAi.isPresent()
								&& GenAiTelemetrySupport.PROVIDER_AWS_BEDROCK
									.equals(genAi.get().getAttributes().get(GenAiTelemetrySupport.GEN_AI_PROVIDER_NAME)))
				&& assertBool(
						"aws.bedrock.agentcore.session_id captured",
						genAi.isPresent() && "demo-session-1".equals(genAi.get()
							.getAttributes()
							.get(GenAiTelemetrySupport.AWS_BEDROCK_AGENTCORE_SESSION_ID)));

		if (!genAi.isPresent()) {
			return false;
		}
		String requestIdActual = genAi.get().getAttributes().get(GenAiTelemetrySupport.AWS_REQUEST_ID);

		pass = assertBool(
				"aws.request_id captured (canonical or alternate header propagated as attribute)",
				"demo-request-1".equals(requestIdActual)) && pass;

		pass = assertBool(
				"usage attributes present when Bedrock succeeds",
				genAi.get().getAttributes().get(GenAiTelemetrySupport.GEN_AI_USAGE_INPUT_TOKENS) != null
						&& genAi.get().getAttributes().get(GenAiTelemetrySupport.GEN_AI_USAGE_OUTPUT_TOKENS) != null)
				&& pass;

		// Confirm the module does NOT emit prompt/completion content events.
		boolean anyContentEvent = genAi.get()
			.getEvents()
			.stream()
			.anyMatch(e -> e.getName().startsWith("gen_ai.content."));
		pass = assertBool("no gen_ai.content.* events emitted by this module", !anyContentEvent) && pass;

		return pass;
	}

	private boolean assertBool(String label, boolean ok) {
		if (ok) {
			log.info("[PASS] {}", label);
		}
		else {
			log.warn("[FAIL] {}", label);
		}
		return ok;
	}

	private void forceFlushTracerIfAvailable() {
		applicationContext.getBeansOfType(OpenTelemetrySdk.class)
			.values()
			.stream()
			.findFirst()
			.ifPresent(otel -> otel.getSdkTracerProvider().forceFlush().join(5, TimeUnit.SECONDS));
	}

}
