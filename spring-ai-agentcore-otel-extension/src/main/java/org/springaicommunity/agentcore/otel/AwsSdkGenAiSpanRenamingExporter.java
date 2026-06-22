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

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.common.InstrumentationScopeInfo;
import io.opentelemetry.sdk.trace.data.DelegatingSpanData;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;

/**
 * Span exporter wrapper that appends an {@code  (aws-sdk-2.2)} suffix to GenAI spans
 * emitted by the OpenTelemetry AWS SDK v2 instrumentation.
 *
 * <p>
 * Both the Spring AI {@code ChatModel} observation (bridged from Micrometer to OTel) and
 * the OTel {@code aws-sdk-2.2} Bedrock Runtime instrumentation follow the GenAI semantic
 * convention for span names — {@code <gen_ai.operation.name> <gen_ai.request.model>} —
 * which makes them appear identical (e.g. both named
 * {@code chat global.amazon.nova-2-lite-v1:0}) even though they sit at different layers
 * of the call stack and carry complementary attributes. This wrapper disambiguates the
 * AWS SDK side, leaving the Spring AI span untouched.
 *
 * <p>
 * Detection uses two signals taken at export time, when all attributes are guaranteed to
 * be present on the {@link SpanData}:
 * <ul>
 * <li><b>Instrumentation scope</b> — the span's {@link InstrumentationScopeInfo#getName()
 * scope name} starts with {@code io.opentelemetry.aws-sdk-}, identifying spans produced
 * by the OTel AWS SDK instrumentation (and not, for example, Spring AI's
 * Micrometer-bridged span).</li>
 * <li><b>GenAI semantic convention marker</b> — the span carries the
 * {@code gen_ai.system} attribute, which the OTel GenAI conventions require on every
 * GenAI client span and on no other kind of span. Using this attribute (instead of an
 * enumerated list of operation names) means the wrapper continues to behave correctly as
 * new GenAI operations are added to the spec or to AWS Bedrock.</li>
 * </ul>
 * Plain AWS SDK spans (e.g. {@code BedrockAgentCore.ListEvents}, {@code S3.GetObject})
 * carry the AWS SDK scope but no {@code gen_ai.system} attribute, and are therefore left
 * alone.
 *
 * <p>
 * Why an exporter wrapper rather than a {@code SpanProcessor.onStart}: the OTel AWS SDK
 * Bedrock instrumentation adds the {@code gen_ai.*} attributes after the request body is
 * marshalled, which is after {@code SpanBuilder.startSpan()} has returned. A processor
 * running at {@code onStart} would therefore see the span before those attributes are
 * set. Running at export time avoids the race: the {@link SpanData} passed to
 * {@link #export} carries every attribute the span will ever have.
 *
 * @author Andrei Shakirin
 */
class AwsSdkGenAiSpanRenamingExporter implements SpanExporter {

	private static final String AWS_SDK_SCOPE_PREFIX = "io.opentelemetry.aws-sdk-";

	private static final String SUFFIX = " (aws-sdk-2.2)";

	private static final AttributeKey<String> GEN_AI_SYSTEM = AttributeKey.stringKey("gen_ai.system");

	private final SpanExporter delegate;

	AwsSdkGenAiSpanRenamingExporter(SpanExporter delegate) {
		this.delegate = delegate;
	}

	@Override
	public CompletableResultCode export(Collection<SpanData> spans) {
		List<SpanData> rewritten = new ArrayList<>(spans.size());
		for (SpanData span : spans) {
			rewritten.add(maybeRename(span));
		}
		return this.delegate.export(rewritten);
	}

	@Override
	public CompletableResultCode flush() {
		return this.delegate.flush();
	}

	@Override
	public CompletableResultCode shutdown() {
		return this.delegate.shutdown();
	}

	private static SpanData maybeRename(SpanData span) {
		if (!isAwsSdkScope(span.getInstrumentationScopeInfo())) {
			return span;
		}
		if (span.getAttributes().get(GEN_AI_SYSTEM) == null) {
			return span;
		}
		String name = span.getName();
		if (name == null || name.isEmpty() || name.endsWith(SUFFIX)) {
			return span;
		}
		return new RenamedSpanData(span, name + SUFFIX);
	}

	private static boolean isAwsSdkScope(InstrumentationScopeInfo scope) {
		if (scope == null) {
			return false;
		}
		String name = scope.getName();
		return name != null && name.startsWith(AWS_SDK_SCOPE_PREFIX);
	}

	private static final class RenamedSpanData extends DelegatingSpanData {

		private final String name;

		private RenamedSpanData(SpanData delegate, String name) {
			super(delegate);
			this.name = name;
		}

		@Override
		public String getName() {
			return this.name;
		}

	}

}
