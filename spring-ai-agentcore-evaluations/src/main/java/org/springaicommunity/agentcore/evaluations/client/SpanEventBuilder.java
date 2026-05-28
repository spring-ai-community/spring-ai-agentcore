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

package org.springaicommunity.agentcore.evaluations.client;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Builds the ADOT-formatted span + log-record pair that the AgentCore Evaluate API
 * accepts as input.
 *
 * <p>
 * The payload is shaped to match the reference Python
 * {@code bedrock_agentcore.evaluation.span_to_adot_serializer}. Two documents are
 * produced for a single user/assistant turn: an agent-invocation span and a log record
 * carrying the conversation content. Both use the {@value #SCOPE_NAME} instrumentation
 * scope because that is the scope the Evaluate API's server-side parser currently
 * recognises for conversation extraction. This means we effectively impersonate the
 * Strands runtime on the wire — it is a pragmatic choice that may need to change if AWS
 * publishes a dedicated scope for non-Strands agents.
 *
 * <p>
 * The exact body shape (nested {@code {"content": {"content": "..."}}} for user and
 * {@code {"message": "...", "finish_reason": "..."}} for assistant) was
 * reverse-engineered from a real Strands agent run; small deviations cause the server to
 * reject the payload with {@code AgentSpanMappingException}.
 *
 * @author Andrei Shakirin
 */
public final class SpanEventBuilder {

	/**
	 * Instrumentation scope the Evaluate API recognises as a Strands agent source. We
	 * reuse this scope for Spring AI invocations because it is the only scope whose
	 * conversation-extraction rules currently produce a score.
	 */
	public static final String SCOPE_NAME = "strands.telemetry.tracer";

	private static final Map<String, Object> RESOURCE = Map.of("attributes",
			Map.of("telemetry.sdk.language", "java", "telemetry.sdk.name", "opentelemetry", "telemetry.sdk.version",
					"1.0.0", "service.name", "strands-evals", "service.version", "0.1.0"));

	private static final ObjectMapper JSON = new ObjectMapper();

	private final String traceId;

	private final String spanId;

	private final String sessionId;

	private final long startTimeNano;

	private long endTimeNano;

	private String userPrompt;

	private String assistantResponse;

	private String modelId = "unknown";

	private String finishReason = "end_turn";

	private Integer inputTokens;

	private Integer outputTokens;

	/**
	 * Optional list of additional messages (system messages, prior user/assistant turns,
	 * tool-response messages) to include in the body's {@code input.messages} array
	 * before the current user prompt. Kept optional so callers that don't wire it
	 * preserve the pre-existing single-message wire shape verified against the Evaluate
	 * API.
	 */
	private List<Map<String, Object>> history = List.of();

	private SpanEventBuilder(String traceId, String sessionId) {
		this.traceId = (traceId != null) ? traceId : generateTraceId();
		this.spanId = generateSpanId();
		this.sessionId = sessionId;
		this.startTimeNano = toNanos(Instant.now());
		this.endTimeNano = this.startTimeNano;
	}

	/**
	 * Create a new builder for an agent invocation span.
	 * @param traceId the trace ID (or null to generate one)
	 * @param sessionId the session ID
	 * @return a new builder
	 */
	public static SpanEventBuilder agentInvocation(String traceId, String sessionId) {
		return new SpanEventBuilder(traceId, sessionId);
	}

	/**
	 * Set the user's prompt.
	 * @param userPrompt the user's prompt text
	 * @return this builder
	 */
	public SpanEventBuilder promptEvent(String userPrompt) {
		this.userPrompt = userPrompt;
		return this;
	}

	/**
	 * Set the assistant's response.
	 * @param assistantResponse the assistant's response text
	 * @return this builder
	 */
	public SpanEventBuilder completionEvent(String assistantResponse) {
		this.endTimeNano = toNanos(Instant.now());
		this.assistantResponse = assistantResponse;
		return this;
	}

	/**
	 * Set the model id recorded on the agent span's {@code gen_ai.request.model}
	 * attribute.
	 * @param modelId the model identifier
	 * @return this builder
	 */
	public SpanEventBuilder modelId(String modelId) {
		if (modelId != null && !modelId.isBlank()) {
			this.modelId = modelId;
		}
		return this;
	}

	/**
	 * Set the finish reason emitted in the assistant message's body (e.g.
	 * {@code "end_turn"}, {@code "tool_use"}, {@code "max_tokens"}). Defaults to
	 * {@code "end_turn"} when not set or when the value is blank.
	 * @param finishReason the finish reason value
	 * @return this builder
	 */
	public SpanEventBuilder finishReason(String finishReason) {
		if (finishReason != null && !finishReason.isBlank()) {
			this.finishReason = finishReason;
		}
		return this;
	}

	/**
	 * Set the per-turn token usage to emit as OTel GenAI span attributes
	 * ({@code gen_ai.usage.input_tokens} and {@code gen_ai.usage.output_tokens}). Null
	 * values are skipped — the corresponding attribute is omitted rather than set to
	 * zero, since many providers return {@code null} when usage data is unavailable and a
	 * hard zero would be misleading.
	 * @param inputTokens prompt tokens consumed (may be {@code null})
	 * @param outputTokens completion tokens produced (may be {@code null})
	 * @return this builder
	 */
	public SpanEventBuilder tokenUsage(Integer inputTokens, Integer outputTokens) {
		this.inputTokens = inputTokens;
		this.outputTokens = outputTokens;
		return this;
	}

	/**
	 * Supply conversation messages to emit before the current user prompt in the span
	 * body's {@code input.messages} array. Each entry must already be in the ADOT message
	 * shape (keys {@code role} and {@code content}). Callers build this from Spring AI's
	 * {@code List<Message>} before passing in. Passing {@code null} or an empty list is a
	 * no-op and preserves the single-message baseline shape.
	 * @param history prior messages in ADOT body shape
	 * @return this builder
	 */
	public SpanEventBuilder history(List<Map<String, Object>> history) {
		this.history = (history != null) ? history : List.of();
		return this;
	}

	/**
	 * Build all session spans (span + events) as required by the Evaluate API.
	 * @return list of spans and events to pass to sessionSpans
	 */
	public List<Map<String, Object>> buildSessionSpans() {
		List<Map<String, Object>> sessionSpans = new ArrayList<>();

		// Add the main span
		sessionSpans.add(this.buildSpan());

		// Add the event with input/output
		sessionSpans.add(this.buildEvent());

		return sessionSpans;
	}

	private Map<String, Object> commonFields() {
		Map<String, Object> m = new HashMap<>();
		m.put("resource", RESOURCE);
		m.put("traceId", this.traceId);
		m.put("spanId", this.spanId);
		m.put("flags", 1);
		return m;
	}

	private Map<String, Object> buildSpan() {
		Map<String, Object> span = this.commonFields();
		span.put("scope", Map.of("name", SCOPE_NAME, "version", ""));
		span.put("parentSpanId", null);
		span.put("name", "invoke_agent Strands Agents");
		span.put("kind", "INTERNAL");
		span.put("startTimeUnixNano", this.startTimeNano);
		span.put("endTimeUnixNano", this.endTimeNano);
		span.put("durationNano", this.endTimeNano - this.startTimeNano);

		Map<String, Object> attributes = new HashMap<>();
		attributes.put("gen_ai.operation.name", "invoke_agent");
		attributes.put("gen_ai.agent.name", "Strands Agents");
		attributes.put("gen_ai.system", "strands-agents");
		attributes.put("gen_ai.request.model", this.modelId);
		if (this.sessionId != null) {
			attributes.put("session.id", this.sessionId);
		}
		if (this.inputTokens != null) {
			attributes.put("gen_ai.usage.input_tokens", this.inputTokens);
		}
		if (this.outputTokens != null) {
			attributes.put("gen_ai.usage.output_tokens", this.outputTokens);
		}
		span.put("attributes", attributes);
		span.put("status", Map.of("code", "OK"));
		return span;
	}

	private Map<String, Object> buildEvent() {
		Map<String, Object> event = this.commonFields();
		event.put("scope", Map.of("name", SCOPE_NAME));
		event.put("timeUnixNano", this.endTimeNano);
		event.put("observedTimeUnixNano", this.endTimeNano + 100_000L);
		event.put("severityNumber", 9);
		event.put("severityText", "");
		event.put("attributes", Map.of("event.name", SCOPE_NAME));
		event.put("body", this.buildBody());
		return event;
	}

	private Map<String, Object> buildBody() {
		Map<String, Object> body = new HashMap<>();
		if (this.userPrompt != null || !this.history.isEmpty()) {
			List<Map<String, Object>> messages = new ArrayList<>(this.history);
			if (this.userPrompt != null) {
				// Strands wraps user content as a JSON array of text parts.
				String wrapped = toJson(List.of(Map.of("text", this.userPrompt)));
				messages.add(Map.of("role", "user", "content", Map.of("content", wrapped)));
			}
			body.put("input", Map.of("messages", messages));
		}
		if (this.assistantResponse != null) {
			body.put("output", Map.of("messages", List.of(Map.of("role", "assistant", "content",
					Map.of("message", this.assistantResponse, "finish_reason", this.finishReason)))));
		}
		return body;
	}

	private static String toJson(Object value) {
		try {
			return JSON.writeValueAsString(value);
		}
		catch (JsonProcessingException ex) {
			throw new IllegalStateException("Failed to serialise value as JSON: " + value, ex);
		}
	}

	private static String generateTraceId() {
		byte[] bytes = new byte[16];
		ThreadLocalRandom.current().nextBytes(bytes);
		return bytesToHex(bytes);
	}

	private static String generateSpanId() {
		byte[] bytes = new byte[8];
		ThreadLocalRandom.current().nextBytes(bytes);
		return bytesToHex(bytes);
	}

	private static String bytesToHex(byte[] bytes) {
		StringBuilder sb = new StringBuilder();
		for (byte b : bytes) {
			sb.append(String.format("%02x", b));
		}
		return sb.toString();
	}

	private static long toNanos(Instant instant) {
		return instant.getEpochSecond() * 1_000_000_000L + instant.getNano();
	}

}
