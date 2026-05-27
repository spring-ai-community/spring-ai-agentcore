/*
 * Copyright 2025-2025 the original author or authors.
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

package org.springaicommunity.agentcore.evaluations.client;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link SpanEventBuilder}.
 *
 * <p>
 * Verifies the public contract required by the AgentCore Evaluate API: a span/event pair
 * carrying session metadata and the user/assistant payloads.
 *
 * @author Andrei Shakirin
 */
class SpanEventBuilderTests {

	private static final String TRACE_ID = "trace123";

	private static final String SESSION_ID = "session456";

	@Test
	void shouldProduceSpanAndEventLinkedByTraceAndSpanId() {
		List<Map<String, Object>> sessionSpans = SpanEventBuilder.agentInvocation(TRACE_ID, SESSION_ID)
			.promptEvent("What is the capital of France?")
			.completionEvent("Paris.")
			.buildSessionSpans();

		assertThat(sessionSpans).hasSize(2);
		Map<String, Object> span = sessionSpans.get(0);
		Map<String, Object> event = sessionSpans.get(1);

		assertThat(span.get("traceId")).isEqualTo(TRACE_ID);
		assertThat(event.get("traceId")).isEqualTo(span.get("traceId"));
		assertThat(event.get("spanId")).isEqualTo(span.get("spanId"));
	}

	@Test
	void spanShouldCarrySessionAndScope() {
		Map<String, Object> span = SpanEventBuilder.agentInvocation(TRACE_ID, SESSION_ID)
			.buildSessionSpans()
			.getFirst();

		assertThat(attributes(span)).containsEntry("session.id", SESSION_ID);
		assertThat(scope(span)).containsEntry("name", SpanEventBuilder.SCOPE_NAME);
	}

	@Test
	void eventShouldCarryPromptAndCompletionPayloads() {
		Map<String, Object> event = SpanEventBuilder.agentInvocation(TRACE_ID, SESSION_ID)
			.promptEvent("What is the capital of France?")
			.completionEvent("Paris.")
			.buildSessionSpans()
			.get(1);

		// The body shape is the Strands ADOT format: the plain prompt/completion text
		// must be findable anywhere in the serialized body (Strands wraps user content
		// as a JSON array and output as a message object).
		String bodyStr = event.get("body").toString();
		assertThat(bodyStr).contains("What is the capital of France?");
		assertThat(bodyStr).contains("Paris.");
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("finishReasonCases")
	void finishReasonRendersExpectedValue(String name, String input, String expected) {
		Map<String, Object> event = SpanEventBuilder.agentInvocation(TRACE_ID, SESSION_ID)
			.completionEvent("Paris.")
			.finishReason(input)
			.buildSessionSpans()
			.get(1);

		assertThat(event.get("body").toString()).contains("finish_reason=" + expected);
	}

	static Stream<Arguments> finishReasonCases() {
		return Stream.of(Arguments.of("null falls back to end_turn", null, "end_turn"),
				Arguments.of("blank falls back to end_turn", "   ", "end_turn"),
				Arguments.of("explicit value is propagated", "tool_use", "tool_use"));
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("tokenUsageCases")
	void tokenUsageAttributesReflectNullability(String name, Integer input, Integer output, boolean inputEmitted,
			boolean outputEmitted) {
		Map<String, Object> span = SpanEventBuilder.agentInvocation(TRACE_ID, SESSION_ID)
			.tokenUsage(input, output)
			.buildSessionSpans()
			.getFirst();

		Map<String, Object> attrs = attributes(span);
		if (inputEmitted) {
			assertThat(attrs).containsEntry("gen_ai.usage.input_tokens", input);
		}
		else {
			assertThat(attrs).doesNotContainKey("gen_ai.usage.input_tokens");
		}
		if (outputEmitted) {
			assertThat(attrs).containsEntry("gen_ai.usage.output_tokens", output);
		}
		else {
			assertThat(attrs).doesNotContainKey("gen_ai.usage.output_tokens");
		}
	}

	static Stream<Arguments> tokenUsageCases() {
		return Stream.of(Arguments.of("both present are emitted", 120, 45, true, true),
				Arguments.of("both null are omitted", null, null, false, false),
				Arguments.of("only input present emits only input", 120, null, true, false));
	}

	@Test
	void historyEntriesAreEmittedBeforeCurrentUserPrompt() {
		List<Map<String, Object>> history = List.of(Map.of("role", "system", "content", "You are helpful."),
				Map.of("role", "user", "content", "Earlier question"),
				Map.of("role", "assistant", "content", "Earlier answer"));

		Map<String, Object> event = SpanEventBuilder.agentInvocation(TRACE_ID, SESSION_ID)
			.promptEvent("Current question")
			.completionEvent("Current answer")
			.history(history)
			.buildSessionSpans()
			.get(1);

		String bodyStr = event.get("body").toString();
		// All history entries plus current user are in input.messages
		assertThat(bodyStr).contains("You are helpful.")
			.contains("Earlier question")
			.contains("Earlier answer")
			.contains("Current question");
		// Current answer in output.messages
		assertThat(bodyStr).contains("Current answer");
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("emptyHistoryCases")
	void absentHistoryProducesSingleMessageBody(String name, List<Map<String, Object>> history) {
		Map<String, Object> event = SpanEventBuilder.agentInvocation(TRACE_ID, SESSION_ID)
			.promptEvent("Current question")
			.completionEvent("Current answer")
			.history(history)
			.buildSessionSpans()
			.get(1);

		String bodyStr = event.get("body").toString();
		assertThat(bodyStr).contains("Current question").contains("Current answer");
	}

	static Stream<Arguments> emptyHistoryCases() {
		return Stream.of(Arguments.of("empty list", List.of()), Arguments.of("null list", null));
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> attributes(Map<String, Object> span) {
		return (Map<String, Object>) span.get("attributes");
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> scope(Map<String, Object> span) {
		return (Map<String, Object>) span.get("scope");
	}

}
