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

import io.opentelemetry.api.baggage.Baggage;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SessionBaggageSpanProcessorTests {

	private static final AttributeKey<String> SESSION_ID_ATTR = AttributeKey.stringKey("session.id");

	private InMemorySpanExporter exporter;

	private SdkTracerProvider tracerProvider;

	private Tracer tracer;

	@BeforeEach
	void setUp() {
		this.exporter = InMemorySpanExporter.create();
		this.tracerProvider = SdkTracerProvider.builder()
			.addSpanProcessor(new SessionBaggageSpanProcessor())
			.addSpanProcessor(SimpleSpanProcessor.create(this.exporter))
			.build();
		this.tracer = this.tracerProvider.get("test");
	}

	@AfterEach
	void tearDown() {
		this.tracerProvider.close();
	}

	@Test
	void sessionIdFromBaggagePropagatedToSpanAttribute() {
		Context context = Context.current().with(Baggage.builder().put("session.id", "sess-abc-123").build());

		try (Scope ignored = context.makeCurrent()) {
			Span span = this.tracer.spanBuilder("test-span").startSpan();
			span.end();
		}

		assertThat(this.exporter.getFinishedSpanItems()).hasSize(1);
		assertThat(this.exporter.getFinishedSpanItems().get(0).getAttributes().get(SESSION_ID_ATTR))
			.isEqualTo("sess-abc-123");
	}

	@Test
	void noAttributeWhenBaggageIsMissing() {
		Span span = this.tracer.spanBuilder("test-span").startSpan();
		span.end();

		assertThat(this.exporter.getFinishedSpanItems()).hasSize(1);
		assertThat(this.exporter.getFinishedSpanItems().get(0).getAttributes().get(SESSION_ID_ATTR)).isNull();
	}

	@Test
	void noAttributeWhenBaggageIsEmpty() {
		Context context = Context.current().with(Baggage.builder().put("session.id", "").build());

		try (Scope ignored = context.makeCurrent()) {
			Span span = this.tracer.spanBuilder("test-span").startSpan();
			span.end();
		}

		assertThat(this.exporter.getFinishedSpanItems()).hasSize(1);
		assertThat(this.exporter.getFinishedSpanItems().get(0).getAttributes().get(SESSION_ID_ATTR)).isNull();
	}

}
