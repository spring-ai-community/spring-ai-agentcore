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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.core.document.Document;
import software.amazon.awssdk.services.bedrockagentcore.BedrockAgentCoreClient;
import software.amazon.awssdk.services.bedrockagentcore.model.EvaluateRequest;
import software.amazon.awssdk.services.bedrockagentcore.model.EvaluateResponse;
import software.amazon.awssdk.services.bedrockagentcore.model.EvaluationResultContent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;

/**
 * Tests for {@link AgentCoreEvaluationClient}. Focuses on contract-level behaviour: span
 * serialisation preserves nested structure and null handling, and API errors are carried
 * through to the result model.
 */
class AgentCoreEvaluationClientTests {

	@Test
	void convertsNestedSpansAndNullsToDocumentFaithfully() {
		BedrockAgentCoreClient sdk = mock(BedrockAgentCoreClient.class);
		given(sdk.evaluate(any(EvaluateRequest.class))).willReturn(EvaluateResponse.builder().build());

		Map<String, Object> span = new HashMap<>();
		span.put("traceId", "t1");
		span.put("flags", 1);
		span.put("parentSpanId", null);
		span.put("attributes", Map.of("list", List.of(1, "two", 3.5)));

		new AgentCoreEvaluationClient(sdk).evaluate("Builtin.Helpfulness", List.of(span));

		ArgumentCaptor<EvaluateRequest> captor = ArgumentCaptor.forClass(EvaluateRequest.class);
		then(sdk).should().evaluate(captor.capture());
		List<Document> sent = captor.getValue().evaluationInput().sessionSpans();

		assertThat(sent).hasSize(1);
		Map<String, Document> root = sent.get(0).asMap();
		assertThat(root.get("traceId").asString()).isEqualTo("t1");
		assertThat(root.get("flags").asNumber().intValue()).isEqualTo(1);
		assertThat(root.get("parentSpanId").isNull()).isTrue();
		List<Document> list = root.get("attributes").asMap().get("list").asList();
		assertThat(list.get(0).asNumber().intValue()).isEqualTo(1);
		assertThat(list.get(1).asString()).isEqualTo("two");
		assertThat(list.get(2).asNumber().doubleValue()).isEqualTo(3.5);
	}

	@Test
	void propagatesErrorCodeAndErrorMessageFromSdk() {
		BedrockAgentCoreClient sdk = mock(BedrockAgentCoreClient.class);
		EvaluationResultContent errored = EvaluationResultContent.builder()
			.evaluatorId("Builtin.Helpfulness")
			.errorCode("AgentSpanMappingException")
			.errorMessage("bad span")
			.build();
		given(sdk.evaluate(any(EvaluateRequest.class)))
			.willReturn(EvaluateResponse.builder().evaluationResults(errored).build());

		List<EvaluationResult> results = new AgentCoreEvaluationClient(sdk).evaluate("Builtin.Helpfulness",
				List.of(Map.of("k", "v")));

		assertThat(results).hasSize(1);
		EvaluationResult r = results.get(0);
		assertThat(r.isError()).isTrue();
		assertThat(r.errorCode()).isEqualTo("AgentSpanMappingException");
		assertThat(r.errorMessage()).isEqualTo("bad span");
	}

	@Test
	void evaluateAllIsolatesPerEvaluatorExceptions() {
		BedrockAgentCoreClient sdk = mock(BedrockAgentCoreClient.class);
		given(sdk.evaluate(any(EvaluateRequest.class))).willAnswer((inv) -> {
			String id = inv.getArgument(0, EvaluateRequest.class).evaluatorId();
			if ("Builtin.Correctness".equals(id)) {
				throw new IllegalStateException("boom");
			}
			return EvaluateResponse.builder()
				.evaluationResults(EvaluationResultContent.builder().evaluatorId(id).value(0.9).build())
				.build();
		});

		ExecutorService executor = Executors.newFixedThreadPool(2);
		try {
			List<EvaluationResult> results = new AgentCoreEvaluationClient(sdk).evaluateAll(
					List.of("Builtin.Helpfulness", "Builtin.Correctness"), List.of(Map.of("k", "v")), executor);

			assertThat(results).hasSize(2);
			assertThat(results.get(0).evaluatorId()).isEqualTo("Builtin.Helpfulness");
			assertThat(results.get(0).isError()).isFalse();
			assertThat(results.get(1).evaluatorId()).isEqualTo("Builtin.Correctness");
			assertThat(results.get(1).isError()).isTrue();
			assertThat(results.get(1).errorCode()).isEqualTo("ClientException:IllegalStateException");
		}
		finally {
			executor.shutdown();
		}
	}

	@Test
	void evaluateAllRunsEvaluatorsConcurrentlyAndPreservesOrder() {
		// Three evaluators each block until the latch counts to zero. With a serial
		// implementation this would deadlock (the latch never reaches zero from a
		// single worker). Concurrent execution releases all three at once. The same
		// run also asserts that results come back in evaluatorIds order — ordering is
		// a property of every successful parallel call, not a separate scenario, so
		// folding it in here keeps one test instead of two with the same setup.
		int evaluatorCount = 3;
		CountDownLatch latch = new CountDownLatch(evaluatorCount);
		AtomicInteger maxConcurrent = new AtomicInteger();
		AtomicInteger inFlight = new AtomicInteger();

		BedrockAgentCoreClient sdk = mock(BedrockAgentCoreClient.class);
		given(sdk.evaluate(any(EvaluateRequest.class))).willAnswer((inv) -> {
			int n = inFlight.incrementAndGet();
			maxConcurrent.accumulateAndGet(n, Math::max);
			latch.countDown();
			boolean ok = latch.await(5, TimeUnit.SECONDS);
			inFlight.decrementAndGet();
			if (!ok) {
				throw new IllegalStateException("Latch never released — evaluators ran serially");
			}
			return EvaluateResponse.builder()
				.evaluationResults(EvaluationResultContent.builder()
					.evaluatorId(inv.getArgument(0, EvaluateRequest.class).evaluatorId())
					.value(1.0)
					.build())
				.build();
		});

		ExecutorService executor = Executors.newFixedThreadPool(evaluatorCount);
		try {
			List<EvaluationResult> results = new AgentCoreEvaluationClient(sdk).evaluateAll(
					List.of("Builtin.Helpfulness", "Builtin.Correctness", "Builtin.Coherence"),
					List.of(Map.of("k", "v")), executor);

			assertThat(maxConcurrent.get()).isEqualTo(evaluatorCount);
			assertThat(results).extracting(EvaluationResult::evaluatorId)
				.containsExactly("Builtin.Helpfulness", "Builtin.Correctness", "Builtin.Coherence");
			assertThat(results).allMatch((r) -> !r.isError());
		}
		finally {
			executor.shutdown();
		}
	}

}
