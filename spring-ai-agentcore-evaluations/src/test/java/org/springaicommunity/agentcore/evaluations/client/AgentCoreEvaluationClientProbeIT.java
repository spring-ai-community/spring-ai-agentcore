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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.bedrockagentcore.BedrockAgentCoreClient;

/**
 * Live probe for the AgentCore Evaluate API. Enabled via
 * {@code AGENTCORE_EVAL_PROBE=true}.
 */
@Tag("integration")
@EnabledIfEnvironmentVariable(named = "AGENTCORE_EVAL_PROBE", matches = "true")
class AgentCoreEvaluationClientProbeIT {

	private static final String USER = "What is the capital of France?";

	private static final String ASSISTANT = "The capital of France is Paris.";

	private static final String EVALUATOR_ID = System.getenv().getOrDefault("AGENTCORE_EVAL_ID", "Builtin.Helpfulness");

	private static final String REGION = System.getenv().getOrDefault("AGENTCORE_EVAL_REGION", "us-east-1");

	private final BedrockAgentCoreClient sdk = BedrockAgentCoreClient.builder().region(Region.of(REGION)).build();

	private final AgentCoreEvaluationClient client = new AgentCoreEvaluationClient(this.sdk);

	@Test
	void probe() {
		this.probe("B1-nested-content", this.body("content"));
		this.probe("B2-content-string", this.body("content-string"));
		this.probe("B3-content-text", this.body("content-text"));
		this.probe("B4-messages-flat", this.body("flat"));
		this.probe("B5-messages-content-parts-array", this.body("parts-array"));
		this.probe("B6-content-list-text-objs", this.body("list-text-objs"));
		this.probe("B7-input-output-as-strings", this.body("io-strings"));
		this.probe("B8-single-messages-list", this.body("single-messages-list"));
	}

	private void probe(String name, Map<String, Object> body) {
		String traceId = hex(16);
		String spanId = hex(8);
		long t = System.currentTimeMillis() * 1_000_000L;
		Map<String, Object> span = buildSpan(traceId, spanId, t);
		Map<String, Object> log = buildLog(traceId, spanId, t);
		log.put("body", body);
		try {
			List<EvaluationResult> r = this.client.evaluate(EVALUATOR_ID, List.of(span, log));
			EvaluationResult res = (r.isEmpty()) ? null : r.get(0);
			System.out.printf("[%s] size=%d score=%s err=%s%n", name, r.size(), (res != null) ? res.score() : null,
					(res != null) ? res.errorCode() : null);
		}
		catch (Exception ex) {
			System.out.printf("[%s] %s: %s%n", name, ex.getClass().getSimpleName(), ex.getMessage());
		}
	}

	private Map<String, Object> body(String variant) {
		return switch (variant) {
			case "content-string" ->
				Map.of("input", Map.of("messages", List.of(Map.of("role", "user", "content", USER))), "output",
						Map.of("messages", List.of(Map.of("role", "assistant", "content", ASSISTANT))));
			case "content" -> Map.of("input",
					Map.of("messages", List.of(Map.of("role", "user", "content", Map.of("content", USER)))), "output",
					Map.of("messages", List.of(Map.of("role", "assistant", "content", Map.of("content", ASSISTANT)))));
			case "content-text" -> Map.of("input",
					Map.of("messages", List.of(Map.of("role", "user", "content", Map.of("text", USER)))), "output",
					Map.of("messages", List.of(Map.of("role", "assistant", "content", Map.of("text", ASSISTANT)))));
			case "flat" -> Map.of("messages", List.of(Map.of("role", "user", "content", USER),
					Map.of("role", "assistant", "content", ASSISTANT)));
			case "parts-array" -> Map.of("input", Map.of("messages", List.of(partsMessage("user", USER))), "output",
					Map.of("messages", List.of(partsMessage("assistant", ASSISTANT))));
			case "list-text-objs" -> Map.of("input", Map.of("messages", List.of(textObjMessage("user", USER))),
					"output", Map.of("messages", List.of(textObjMessage("assistant", ASSISTANT))));
			case "io-strings" -> Map.of("input", USER, "output", ASSISTANT);
			case "single-messages-list" -> Map.of("input", List.of(Map.of("role", "user", "content", USER)), "output",
					List.of(Map.of("role", "assistant", "content", ASSISTANT)));
			default -> throw new IllegalArgumentException(variant);
		};
	}

	private static Map<String, Object> partsMessage(String role, String text) {
		return Map.of("role", role, "content", List.of(Map.of("type", "text", "text", text)));
	}

	private static Map<String, Object> textObjMessage(String role, String text) {
		return Map.of("role", role, "content", List.of(Map.of("text", text)));
	}

	private static Map<String, Object> buildSpan(String traceId, String spanId, long t) {
		Map<String, Object> s = new HashMap<>();
		s.put("resource", Map.of("attributes", Map.of()));
		s.put("scope", Map.of("name", "strands.telemetry.tracer", "version", ""));
		s.put("traceId", traceId);
		s.put("spanId", spanId);
		s.put("parentSpanId", null);
		s.put("flags", 1);
		s.put("name", "invoke_agent Strands Agents");
		s.put("kind", "INTERNAL");
		s.put("startTimeUnixNano", t);
		s.put("endTimeUnixNano", t + 1);
		s.put("durationNano", 1);
		Map<String, Object> attrs = new HashMap<>();
		attrs.put("gen_ai.operation.name", "invoke_agent");
		attrs.put("gen_ai.agent.name", "Strands Agents");
		attrs.put("gen_ai.system", "strands-agents");
		attrs.put("session.id", "probe-session");
		s.put("attributes", attrs);
		s.put("status", Map.of("code", "OK"));
		return s;
	}

	private static Map<String, Object> buildLog(String traceId, String spanId, long t) {
		Map<String, Object> l = new HashMap<>();
		l.put("resource", Map.of("attributes", Map.of()));
		l.put("scope", Map.of("name", "strands.telemetry.tracer"));
		l.put("traceId", traceId);
		l.put("spanId", spanId);
		l.put("flags", 1);
		l.put("timeUnixNano", t);
		l.put("observedTimeUnixNano", t + 100_000L);
		l.put("severityNumber", 9);
		l.put("severityText", "");
		l.put("attributes", Map.of("event.name", "strands.telemetry.tracer"));
		l.put("body", Map.of("input",
				Map.of("messages", List.of(Map.of("role", "user", "content", Map.of("content", USER)))), "output",
				Map.of("messages", List.of(Map.of("role", "assistant", "content", Map.of("content", ASSISTANT))))));
		return l;
	}

	private static String hex(int bytes) {
		byte[] b = new byte[bytes];
		new Random().nextBytes(b);
		StringBuilder sb = new StringBuilder();
		for (byte x : b) {
			sb.append(String.format("%02x", x));
		}
		return sb.toString();
	}

}
