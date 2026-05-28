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

package org.springaicommunity.agentcore.evaluations;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Stream;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springaicommunity.agentcore.evaluations.client.AgentCoreEvaluationClient;
import org.springaicommunity.agentcore.evaluations.client.EvaluationEvent;
import org.springaicommunity.agentcore.evaluations.client.EvaluationResult;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

/**
 * Tests for {@link AgentCoreEvaluationAdvisor}.
 *
 * @author Andrei Shakirin
 */
@ExtendWith(MockitoExtension.class)
class AgentCoreEvaluationAdvisorTests {

	@Mock
	private AgentCoreEvaluationClient client;

	@Test
	void shouldBuildWithDefaults() {
		AgentCoreEvaluationAdvisor advisor = AgentCoreEvaluationAdvisor.builder(this.client).build();

		assertThat(advisor).isNotNull();
		assertThat(advisor.getName()).isEqualTo("AgentCoreEvaluationAdvisor");
		assertThat(advisor.getOrder()).isEqualTo(1000);
	}

	@Test
	void shouldBuildWithCustomOrder() {
		AgentCoreEvaluationAdvisor advisor = AgentCoreEvaluationAdvisor.builder(this.client).order(500).build();

		assertThat(advisor.getOrder()).isEqualTo(500);
	}

	@Test
	void shouldPublishResultsAndMetricsOnSuccess() {
		SimpleMeterRegistry registry = new SimpleMeterRegistry();
		AgentCoreEvaluationMetrics metrics = new AgentCoreEvaluationMetrics(registry);
		AtomicReference<EvaluationEvent> captured = new AtomicReference<>();

		EvaluationResult result = new EvaluationResult("Builtin.Helpfulness", 0.83, "Very Helpful", "ok", 100, 20,
				null);
		given(this.client.evaluateAll(anyList(), anyList(), any())).willReturn(List.of(result));

		AgentCoreEvaluationAdvisor advisor = AgentCoreEvaluationAdvisor.builder(this.client)
			.async(false)
			.metrics(metrics)
			.callback(captured::set)
			.build();

		ChatClientRequest request = request("What is the capital of France?");
		ChatClientResponse response = response("Paris.");
		CallAdvisorChain chain = mock(CallAdvisorChain.class);
		given(chain.nextCall(any())).willReturn(response);

		advisor.adviseCall(request, chain);

		List<EvaluationResult> stored = AgentCoreEvaluationAdvisor.resultsFrom(response);
		assertThat(stored).containsExactly(result);
		assertThat(captured.get()).isNotNull();
		assertThat(captured.get().results()).containsExactly(result);
		Counter count = registry.find("agentcore.evaluation.count")
			.tag("evaluator", "Builtin.Helpfulness")
			.tag("label", "Very Helpful")
			.counter();
		assertThat(count).isNotNull();
		assertThat(count.count()).isEqualTo(1.0);
	}

	@Test
	void shouldRecordErrorWhenResultHasErrorCode() {
		SimpleMeterRegistry registry = new SimpleMeterRegistry();
		AgentCoreEvaluationMetrics metrics = new AgentCoreEvaluationMetrics(registry);

		EvaluationResult errored = new EvaluationResult("Builtin.Helpfulness", null, null, null, null, null,
				"AgentSpanMappingException");
		given(this.client.evaluateAll(anyList(), anyList(), any())).willReturn(List.of(errored));

		AgentCoreEvaluationAdvisor advisor = AgentCoreEvaluationAdvisor.builder(this.client)
			.async(false)
			.metrics(metrics)
			.build();

		CallAdvisorChain chain = mock(CallAdvisorChain.class);
		given(chain.nextCall(any())).willReturn(response("hello"));
		advisor.adviseCall(request("hi"), chain);

		Counter errors = registry.find("agentcore.evaluation.errors")
			.tag("evaluator", "Builtin.Helpfulness")
			.tag("error_code", "AgentSpanMappingException")
			.counter();
		assertThat(errors).isNotNull();
		assertThat(errors.count()).isEqualTo(1.0);
	}

	@Test
	void shouldAggregateStreamingChunksForEvaluation() {
		AtomicReference<List<?>> capturedSpans = new AtomicReference<>();
		given(this.client.evaluateAll(anyList(), anyList(), any())).willAnswer((inv) -> {
			capturedSpans.set(inv.getArgument(1));
			return List.of();
		});

		AgentCoreEvaluationAdvisor advisor = AgentCoreEvaluationAdvisor.builder(this.client).async(false).build();

		StreamAdvisorChain chain = mock(StreamAdvisorChain.class);
		given(chain.nextStream(any())).willReturn(Flux.just(response("Hello, "), response("world"), response("!")));

		List<ChatClientResponse> out = advisor.adviseStream(request("hi"), chain).collectList().block();

		assertThat(out).hasSize(3);
		assertThat(capturedSpans.get().toString()).contains("Hello, world!");
	}

	@Test
	void streamingShouldEmitChunksBeforeSourceCompletes() {
		given(this.client.evaluateAll(anyList(), anyList(), any())).willReturn(List.of());

		AgentCoreEvaluationAdvisor advisor = AgentCoreEvaluationAdvisor.builder(this.client).async(false).build();

		Sinks.Many<ChatClientResponse> source = Sinks.many().unicast().onBackpressureBuffer();
		StreamAdvisorChain chain = mock(StreamAdvisorChain.class);
		given(chain.nextStream(any())).willReturn(source.asFlux());

		List<ChatClientResponse> received = new ArrayList<>();
		Disposable sub = advisor.adviseStream(request("hi"), chain).subscribe(received::add);

		source.tryEmitNext(response("one"));
		source.tryEmitNext(response("two"));
		// Source has NOT completed yet; downstream must already have both chunks.
		assertThat(received).hasSize(2);
		// Evaluation must not have fired yet.
		then(this.client).should(never()).evaluateAll(anyList(), anyList(), any());

		source.tryEmitComplete();
		sub.dispose();
		then(this.client).should(times(1)).evaluateAll(anyList(), anyList(), any());
	}

	@Test
	void streamingShouldNotEvaluateWhenSampleRateIsZero() {
		AgentCoreEvaluationAdvisor advisor = AgentCoreEvaluationAdvisor.builder(this.client)
			.async(false)
			.sampleRate(0.0)
			.build();

		StreamAdvisorChain chain = mock(StreamAdvisorChain.class);
		given(chain.nextStream(any())).willReturn(Flux.just(response("a"), response("b"), response("c")));

		advisor.adviseStream(request("hi"), chain).collectList().block();

		then(this.client).should(never()).evaluateAll(anyList(), anyList(), any());
	}

	@Test
	void streamingShouldSkipEvaluationOnCancellation() {
		AgentCoreEvaluationAdvisor advisor = AgentCoreEvaluationAdvisor.builder(this.client).async(false).build();

		Sinks.Many<ChatClientResponse> source = Sinks.many().unicast().onBackpressureBuffer();
		StreamAdvisorChain chain = mock(StreamAdvisorChain.class);
		given(chain.nextStream(any())).willReturn(source.asFlux());

		Disposable sub = advisor.adviseStream(request("hi"), chain).subscribe();
		source.tryEmitNext(response("partial"));
		sub.dispose();

		then(this.client).should(never()).evaluateAll(anyList(), anyList(), any());
	}

	@Test
	void asyncModeShouldRecordMetricsAfterHandlerReturns() {
		SimpleMeterRegistry registry = new SimpleMeterRegistry();
		AgentCoreEvaluationMetrics metrics = new AgentCoreEvaluationMetrics(registry);
		EvaluationResult result = new EvaluationResult("Builtin.Helpfulness", 0.83, "Very Helpful", "ok", 100, 20,
				null);
		given(this.client.evaluateAll(anyList(), anyList(), any())).willReturn(List.of(result));

		AgentCoreEvaluationAdvisor advisor = AgentCoreEvaluationAdvisor.builder(this.client)
			.async(true)
			.metrics(metrics)
			.build();

		CallAdvisorChain chain = mock(CallAdvisorChain.class);
		given(chain.nextCall(any())).willReturn(response("Paris."));

		// Handler returns immediately; the metric is populated on another thread.
		advisor.adviseCall(request("hi"), chain);

		Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
			Counter counter = registry.find("agentcore.evaluation.count")
				.tag("evaluator", "Builtin.Helpfulness")
				.tag("label", "Very Helpful")
				.counter();
			assertThat(counter).isNotNull();
			assertThat(counter.count()).isEqualTo(1.0);
		});
	}

	private static final EvaluationResult VALID_RESULT = new EvaluationResult("Builtin.Helpfulness", 0.9, "Good", null,
			null, null, null);

	private static final List<Object> MIXED_RESULT_ENTRIES = List.of(VALID_RESULT, "stray", 42);

	/**
	 * Cases for {@link #resultsFromIsTypeSafe(String, Consumer, List)}.
	 * <p>
	 * Each case names the scenario, supplies a {@link Consumer} that prepares the
	 * response context, and declares the results the accessor must return. Bundling setup
	 * and expectation per row keeps the parameterized contract visible at a glance and
	 * scales to new edge cases by adding a row rather than another method.
	 */
	static Stream<Arguments> resultsFromCases() {
		return Stream.of(Arguments.of("context key absent", (Consumer<ChatClientResponse>) (response) -> {
		}, List.of()),
				Arguments.of("value is not a list",
						(Consumer<ChatClientResponse>) (response) -> response.context()
							.put(AgentCoreEvaluationAdvisor.EVALUATION_RESULTS_KEY, "not-a-list"),
						List.of()),
				Arguments.of("list contains mixed entry types",
						(Consumer<ChatClientResponse>) (response) -> response.context()
							.put(AgentCoreEvaluationAdvisor.EVALUATION_RESULTS_KEY, MIXED_RESULT_ENTRIES),
						List.of(VALID_RESULT)));
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("resultsFromCases")
	void resultsFromIsTypeSafe(String name, Consumer<ChatClientResponse> setup, List<EvaluationResult> expected) {
		ChatClientResponse response = response("hi");
		setup.accept(response);

		assertThat(AgentCoreEvaluationAdvisor.resultsFrom(response)).containsExactlyElementsOf(expected);
	}

	private static ChatClientRequest request(String userText) {
		return ChatClientRequest.builder()
			.prompt(new Prompt(new UserMessage(userText)))
			.context(new HashMap<>())
			.build();
	}

	private static ChatClientResponse response(String assistantText) {
		ChatResponse chat = new ChatResponse(List.of(new Generation(new AssistantMessage(assistantText))));
		return ChatClientResponse.builder().chatResponse(chat).context(new HashMap<>()).build();
	}

}
