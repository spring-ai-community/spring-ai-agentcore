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

import java.util.List;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.sdk.common.InstrumentationScopeInfo;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.testing.trace.TestSpanData;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.data.StatusData;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the renamer's decision logic: append the suffix only when the span comes from
 * the OTel AWS SDK instrumentation and follows the GenAI semantic convention.
 */
class AwsSdkGenAiSpanRenamingExporterTests {

	@Test
	void appendsSuffixToAwsSdkGenAiSpan() {
		assertThat(exportName("io.opentelemetry.aws-sdk-2.2", "chat foo", true)).isEqualTo("chat foo (aws-sdk-2.2)");
	}

	@Test
	void leavesAwsSdkNonGenAiSpanUnchanged() {
		assertThat(exportName("io.opentelemetry.aws-sdk-2.2", "BedrockAgentCore.ListEvents", false))
			.isEqualTo("BedrockAgentCore.ListEvents");
	}

	@Test
	void leavesNonAwsSdkSpanUnchanged() {
		// Spring AI's ChatModel observation produces an identically-named span at a
		// different scope; it must not get the AWS SDK suffix.
		assertThat(exportName("org.springframework.boot", "chat foo", true)).isEqualTo("chat foo");
	}

	private static String exportName(String scopeName, String spanName, boolean genAi) {
		InMemorySpanExporter delegate = InMemorySpanExporter.create();
		Attributes attrs = genAi ? Attributes.of(AttributeKey.stringKey("gen_ai.system"), "aws.bedrock")
				: Attributes.empty();
		SpanData span = TestSpanData.builder()
			.setName(spanName)
			.setKind(SpanKind.CLIENT)
			.setStatus(StatusData.unset())
			.setStartEpochNanos(0L)
			.setEndEpochNanos(1L)
			.setHasEnded(true)
			.setInstrumentationScopeInfo(InstrumentationScopeInfo.create(scopeName))
			.setAttributes(attrs)
			.build();

		new AwsSdkGenAiSpanRenamingExporter(delegate).export(List.of(span));

		return delegate.getFinishedSpanItems().get(0).getName();
	}

}
