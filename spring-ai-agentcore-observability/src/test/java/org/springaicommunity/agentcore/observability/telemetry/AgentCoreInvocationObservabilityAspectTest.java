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

package org.springaicommunity.agentcore.observability.telemetry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.servlet.http.HttpServletRequest;

import javax.naming.AuthenticationException;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeoutException;

import org.aspectj.lang.ProceedingJoinPoint;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.server.ServerWebExchange;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.DoubleHistogramBuilder;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanBuilder;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class AgentCoreInvocationObservabilityAspectTest {

	private Tracer tracer;

	private SpanBuilder spanBuilder;

	private Span span;

	private Scope scope;

	private Meter meter;

	private DoubleHistogramBuilder histogramBuilder;

	private DoubleHistogram histogram;

	private AgentCoreInvocationObservabilityAspect aspect;

	@BeforeEach
	void setUp() {
		tracer = mock(Tracer.class);
		spanBuilder = mock(SpanBuilder.class);
		span = mock(Span.class);
		scope = mock(Scope.class);
		meter = mock(Meter.class);
		histogramBuilder = mock(DoubleHistogramBuilder.class);
		histogram = mock(DoubleHistogram.class);

		when(tracer.spanBuilder(anyString())).thenReturn(spanBuilder);
		when(spanBuilder.setSpanKind(any())).thenReturn(spanBuilder);
		when(spanBuilder.startSpan()).thenReturn(span);
		when(span.makeCurrent()).thenReturn(scope);
		when(meter.histogramBuilder(anyString())).thenReturn(histogramBuilder);
		when(histogramBuilder.setUnit(anyString())).thenReturn(histogramBuilder);
		when(histogramBuilder.build()).thenReturn(histogram);

		aspect = new AgentCoreInvocationObservabilityAspect(tracer, meter);
	}

	@Test
	void recordsOkWhenResultIsNotChatResponse() throws Throwable {
		ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
		when(pjp.proceed()).thenReturn("plain");

		Object out = aspect.aroundAgentCoreController(pjp);

		assertThat(out).isEqualTo("plain");
		verify(span).setStatus(StatusCode.OK);
		verify(histogram, never()).record(anyDouble(), any(Attributes.class));
	}

	@Test
	void recordsErrorAndMapsTimeout() throws Throwable {
		ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
		when(pjp.proceed()).thenThrow(new TimeoutException("t"));

		assertThatThrownBy(() -> aspect.aroundAgentCoreController(pjp)).isInstanceOf(TimeoutException.class);

		ArgumentCaptor<String> err = ArgumentCaptor.forClass(String.class);
		verify(span).setAttribute(eq(GenAiTelemetrySupport.ERROR_TYPE), err.capture());
		assertThat(err.getValue()).isEqualTo("timeout");
	}

	@Test
	void mapsSecurityKeywordInExceptionName() throws Throwable {
		ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
		class SslSecurityException extends RuntimeException {

		}
		when(pjp.proceed()).thenThrow(new SslSecurityException());

		assertThatThrownBy(() -> aspect.aroundAgentCoreController(pjp)).isInstanceOf(SslSecurityException.class);

		ArgumentCaptor<String> err = ArgumentCaptor.forClass(String.class);
		verify(span).setAttribute(eq(GenAiTelemetrySupport.ERROR_TYPE), err.capture());
		assertThat(err.getValue()).isEqualTo("authentication_failure");
	}

	@Test
	void mapsAuthenticationFailure() throws Throwable {
		ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
		when(pjp.proceed()).thenThrow(new AuthenticationException());

		assertThatThrownBy(() -> aspect.aroundAgentCoreController(pjp)).isInstanceOf(AuthenticationException.class);

		ArgumentCaptor<String> err = ArgumentCaptor.forClass(String.class);
		verify(span).setAttribute(eq(GenAiTelemetrySupport.ERROR_TYPE), err.capture());
		assertThat(err.getValue()).isEqualTo("authentication_failure");
	}

	@Test
	void mapsGenericErrorType() throws Throwable {
		ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
		when(pjp.proceed()).thenThrow(new IllegalStateException("x"));

		assertThatThrownBy(() -> aspect.aroundAgentCoreController(pjp)).isInstanceOf(IllegalStateException.class);

		ArgumentCaptor<String> err = ArgumentCaptor.forClass(String.class);
		verify(span).setAttribute(eq(GenAiTelemetrySupport.ERROR_TYPE), err.capture());
		assertThat(err.getValue()).isEqualTo("server_error");
	}

	@Test
	void applyGenAiUsesToolOperationWhenToolCallsPresent() throws Throwable {
		ChatResponse response = mock(ChatResponse.class);
		when(response.hasToolCalls()).thenReturn(true);
		when(response.getMetadata()).thenReturn(null);
		when(response.getResults()).thenReturn(Collections.emptyList());

		ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
		when(pjp.proceed()).thenReturn(response);

		aspect.aroundAgentCoreController(pjp);

		verify(span).updateName(GenAiTelemetrySupport.OP_EXECUTE_TOOL);
	}

	@Test
	void treatsNullCompletionTokensAsZero() throws Throwable {
		Usage usage = mock(Usage.class);
		when(usage.getPromptTokens()).thenReturn(3);
		when(usage.getCompletionTokens()).thenReturn(null);

		ChatResponse response = ChatResponse.builder()
			.metadata(ChatResponseMetadata.builder().model("m").usage(usage).build())
			.generations(List.of(new Generation(new AssistantMessage("x"),
					ChatGenerationMetadata.builder().finishReason("stop").build())))
			.build();

		ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
		when(pjp.proceed()).thenReturn(response);

		aspect.aroundAgentCoreController(pjp);

		verify(span).setAttribute(GenAiTelemetrySupport.GEN_AI_USAGE_OUTPUT_TOKENS, 0L);
	}

	@Test
	void skipsFinishReasonWhenGenerationMetadataMissing() throws Throwable {
		ChatResponse response = ChatResponse.builder()
			.metadata(ChatResponseMetadata.builder().model("m").usage(new DefaultUsage(1, 1)).build())
			.generations(List.of(new Generation(new AssistantMessage("x"), null),
					new Generation(new AssistantMessage("y"),
							ChatGenerationMetadata.builder().finishReason("stop").build())))
			.build();

		ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
		when(pjp.proceed()).thenReturn(response);

		aspect.aroundAgentCoreController(pjp);

		verify(span).setAttribute(GenAiTelemetrySupport.GEN_AI_RESPONSE_FINISH_REASONS, "stop");
	}

	@Test
	void modelMetricsUseUnknownWhenModelGetterReturnsNull() throws Throwable {
		ChatResponseMetadata meta = mock(ChatResponseMetadata.class);
		when(meta.getUsage()).thenReturn(new DefaultUsage(1, 1));
		when(meta.getModel()).thenReturn(null);

		ChatResponse response = ChatResponse.builder()
			.metadata(meta)
			.generations(List.of(new Generation(new AssistantMessage("x"),
					ChatGenerationMetadata.builder().finishReason("stop").build())))
			.build();

		ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
		when(pjp.proceed()).thenReturn(response);

		aspect.aroundAgentCoreController(pjp);

		verify(histogram, times(2)).record(anyDouble(), any(Attributes.class));
	}

	@Test
	void skipsModelSpanAttributesWhenMetadataModelIsBlank() throws Throwable {
		ChatResponseMetadata meta = mock(ChatResponseMetadata.class);
		when(meta.getUsage()).thenReturn(new DefaultUsage(1, 1));
		when(meta.getModel()).thenReturn("");

		ChatResponse response = ChatResponse.builder()
			.metadata(meta)
			.generations(List.of(new Generation(new AssistantMessage("x"),
					ChatGenerationMetadata.builder().finishReason("stop").build())))
			.build();

		ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
		when(pjp.proceed()).thenReturn(response);

		aspect.aroundAgentCoreController(pjp);

		verify(span, never()).setAttribute(eq(GenAiTelemetrySupport.GEN_AI_REQUEST_MODEL), anyString());
		verify(span, never()).setAttribute(eq(GenAiTelemetrySupport.GEN_AI_RESPONSE_MODEL), anyString());
	}

	@Test
	void usesUnknownModelWhenMetadataMissingModel() throws Throwable {
		ChatResponse response = ChatResponse.builder()
			.metadata(ChatResponseMetadata.builder().usage(new DefaultUsage(1, 1)).build())
			.generations(Collections.emptyList())
			.build();

		ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
		when(pjp.proceed()).thenReturn(response);

		aspect.aroundAgentCoreController(pjp);

		verify(histogram, times(2)).record(anyDouble(), any(Attributes.class));
	}

	@Test
	void baseInputTokensNullPromptTokens() throws Throwable {
		Usage usage = mock(Usage.class);
		when(usage.getPromptTokens()).thenReturn(null);
		when(usage.getCompletionTokens()).thenReturn(2);

		ChatResponse response = ChatResponse.builder()
			.metadata(ChatResponseMetadata.builder().model("m").usage(usage).build())
			.generations(Collections.emptyList())
			.build();

		ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
		when(pjp.proceed()).thenReturn(response);

		aspect.aroundAgentCoreController(pjp);

		verify(span).setAttribute(GenAiTelemetrySupport.GEN_AI_USAGE_INPUT_TOKENS, 0L);
	}

	@Test
	void appliesTokenHistogramsAndHandlesNullUsage() throws Throwable {
		ChatResponse response = mock(ChatResponse.class);
		when(response.hasToolCalls()).thenReturn(false);
		when(response.getMetadata()).thenReturn(null);
		when(response.getResults()).thenReturn(Collections.emptyList());

		ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
		when(pjp.proceed()).thenReturn(response);

		aspect.aroundAgentCoreController(pjp);

		verify(histogram, times(2)).record(anyDouble(), any(Attributes.class));
	}

	@Test
	void addsCacheReadTokensFromMetadata() throws Throwable {
		ChatResponse response = ChatResponse.builder()
			.metadata(ChatResponseMetadata.builder()
				.model("m")
				.usage(new DefaultUsage(10, 2))
				.keyValue("cacheReadInputTokens", 5L)
				.build())
			.generations(List.of(new Generation(new AssistantMessage("out"),
					ChatGenerationMetadata.builder().finishReason("stop").build())))
			.build();

		ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
		when(pjp.proceed()).thenReturn(response);

		aspect.aroundAgentCoreController(pjp);

		verify(span).setAttribute(GenAiTelemetrySupport.GEN_AI_USAGE_INPUT_TOKENS, 15L);
	}

	@Test
	void ignoresNonNumericCacheReadTokens() throws Throwable {
		ChatResponse response = ChatResponse.builder()
			.metadata(ChatResponseMetadata.builder()
				.model("m")
				.usage(new DefaultUsage(10, 2))
				.keyValue("cacheReadInputTokens", "nope")
				.build())
			.generations(Collections.emptyList())
			.build();

		ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
		when(pjp.proceed()).thenReturn(response);

		aspect.aroundAgentCoreController(pjp);

		verify(span).setAttribute(GenAiTelemetrySupport.GEN_AI_USAGE_INPUT_TOKENS, 10L);
	}

	@Test
	void appliesSessionIdFromHttpServletRequest() throws Throwable {
		HttpServletRequest req = mock(HttpServletRequest.class);
		when(req.getHeader(GenAiTelemetrySupport.HTTP_HEADER_AGENTCORE_SESSION_ID)).thenReturn("sess-1");
		ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
		when(pjp.getArgs()).thenReturn(new Object[] { req });
		when(pjp.proceed()).thenReturn("ok");

		Object out = aspect.aroundAgentCoreController(pjp);

		assertThat(out).isEqualTo("ok");
		verify(span).setAttribute(GenAiTelemetrySupport.AWS_BEDROCK_AGENTCORE_SESSION_ID, "sess-1");
	}

	@Test
	void instrumentsMonoChatResponse() throws Throwable {
		ChatResponse response = ChatResponse.builder()
			.metadata(ChatResponseMetadata.builder().model("m").usage(new DefaultUsage(1, 1)).build())
			.generations(List.of(new Generation(new AssistantMessage("hi"),
					ChatGenerationMetadata.builder().finishReason("stop").build())))
			.build();
		ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
		when(pjp.proceed()).thenReturn(Mono.just(response));

		Object out = aspect.aroundAgentCoreController(pjp);

		assertThat(out).isInstanceOf(Mono.class);
		@SuppressWarnings("unchecked")
		Mono<ChatResponse> mono = (Mono<ChatResponse>) out;
		StepVerifier.create(mono).expectNext(response).verifyComplete();
		verify(span).end();
	}

	@Test
	void instrumentsFluxChatResponse() throws Throwable {
		ChatResponse response = ChatResponse.builder()
			.metadata(ChatResponseMetadata.builder().model("m").usage(new DefaultUsage(1, 1)).build())
			.generations(List.of(new Generation(new AssistantMessage("hi"),
					ChatGenerationMetadata.builder().finishReason("stop").build())))
			.build();
		ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
		when(pjp.proceed()).thenReturn(Flux.just(response));

		Object out = aspect.aroundAgentCoreController(pjp);

		assertThat(out).isInstanceOf(Flux.class);
		@SuppressWarnings("unchecked")
		Flux<ChatResponse> flux = (Flux<ChatResponse>) out;
		StepVerifier.create(flux).expectNext(response).verifyComplete();
		verify(span).end();
	}

	@Test
	void fluxPipelineErrorRecordsOnSpan() throws Throwable {
		ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
		when(pjp.proceed()).thenReturn(Flux.error(new IllegalStateException("stream failed")));

		Object out = aspect.aroundAgentCoreController(pjp);

		@SuppressWarnings("unchecked")
		Flux<?> flux = (Flux<?>) out;
		StepVerifier.create(flux).expectError(IllegalStateException.class).verify();

		verify(span).setStatus(StatusCode.ERROR);
		verify(span).recordException(any(IllegalStateException.class));
		verify(span).setAttribute(eq(GenAiTelemetrySupport.ERROR_TYPE), eq("server_error"));
	}

	@Test
	void fluxWithMultipleChatResponsesRecordsTokenMetricsOnceFromLastChunk() throws Throwable {
		ChatResponse first = ChatResponse.builder()
			.metadata(ChatResponseMetadata.builder().model("m").usage(new DefaultUsage(99, 1)).build())
			.generations(Collections.emptyList())
			.build();
		ChatResponse last = ChatResponse.builder()
			.metadata(ChatResponseMetadata.builder().model("m").usage(new DefaultUsage(1, 2)).build())
			.generations(List.of(new Generation(new AssistantMessage("final"),
					ChatGenerationMetadata.builder().finishReason("stop").build())))
			.build();
		ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
		when(pjp.proceed()).thenReturn(Flux.just(first, last));

		Object out = aspect.aroundAgentCoreController(pjp);

		@SuppressWarnings("unchecked")
		Flux<ChatResponse> flux = (Flux<ChatResponse>) out;
		StepVerifier.create(flux).expectNext(first, last).verifyComplete();

		verify(histogram, times(2)).record(anyDouble(), any(Attributes.class));
	}

	@Test
	void mapsThrottlingExceptionToRateLimit() throws Throwable {
		class ThrottlingException extends RuntimeException {

		}
		ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
		when(pjp.proceed()).thenThrow(new ThrottlingException());

		assertThatThrownBy(() -> aspect.aroundAgentCoreController(pjp)).isInstanceOf(ThrottlingException.class);

		verify(span).setAttribute(eq(GenAiTelemetrySupport.ERROR_TYPE), eq("rate_limit"));
	}

	@Test
	void mapsIllegalArgumentToInvalidRequest() throws Throwable {
		ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
		when(pjp.proceed()).thenThrow(new IllegalArgumentException("bad"));

		assertThatThrownBy(() -> aspect.aroundAgentCoreController(pjp)).isInstanceOf(IllegalArgumentException.class);

		verify(span).setAttribute(eq(GenAiTelemetrySupport.ERROR_TYPE), eq("invalid_request"));
	}

	@Test
	void appliesSessionFromServerWebExchange() throws Throwable {
		ServerWebExchange exchange = mock(ServerWebExchange.class);
		ServerHttpRequest req = mock(ServerHttpRequest.class);
		HttpHeaders headers = new HttpHeaders();
		headers.add(GenAiTelemetrySupport.HTTP_HEADER_AGENTCORE_SESSION_ID, "sess-wx");
		when(exchange.getRequest()).thenReturn(req);
		when(req.getHeaders()).thenReturn(headers);
		ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
		when(pjp.getArgs()).thenReturn(new Object[] { exchange });
		when(pjp.proceed()).thenReturn("ok");

		aspect.aroundAgentCoreController(pjp);

		verify(span).setAttribute(GenAiTelemetrySupport.AWS_BEDROCK_AGENTCORE_SESSION_ID, "sess-wx");
	}

	@Test
	void appliesAwsRequestIdHeader() throws Throwable {
		HttpServletRequest req = mock(HttpServletRequest.class);
		when(req.getHeader(GenAiTelemetrySupport.HTTP_HEADER_AMZN_REQUEST_ID)).thenReturn("rid-9");
		ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
		when(pjp.getArgs()).thenReturn(new Object[] { req });
		when(pjp.proceed()).thenReturn("ok");

		aspect.aroundAgentCoreController(pjp);

		verify(span).setAttribute(GenAiTelemetrySupport.AWS_REQUEST_ID, "rid-9");
	}

	@Test
	void appliesLegacyUndashedAwsRequestIdWhenStandardHeaderAbsent() throws Throwable {
		HttpServletRequest req = mock(HttpServletRequest.class);
		when(req.getHeader("x-amzn-request-id")).thenReturn(null);
		when(req.getHeader("x-amzn-requestid")).thenReturn("rid-legacy");
		ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
		when(pjp.getArgs()).thenReturn(new Object[] { req });
		when(pjp.proceed()).thenReturn("ok");

		aspect.aroundAgentCoreController(pjp);

		verify(span).setAttribute(GenAiTelemetrySupport.AWS_REQUEST_ID, "rid-legacy");
	}

	@Test
	void monoErrorRecordsServerErrorType() throws Throwable {
		ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
		when(pjp.proceed()).thenReturn(Mono.error(new IllegalStateException("b")));

		Object out = aspect.aroundAgentCoreController(pjp);
		@SuppressWarnings("unchecked")
		Mono<?> mono = (Mono<?>) out;
		StepVerifier.create(mono).expectError(IllegalStateException.class).verify();
		verify(span).setAttribute(eq(GenAiTelemetrySupport.ERROR_TYPE), eq("server_error"));
	}

	@Test
	void monoWithNonChatResponseSkipsGenAiAttributes() throws Throwable {
		ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
		when(pjp.proceed()).thenReturn(Mono.just("plain"));

		Object out = aspect.aroundAgentCoreController(pjp);
		@SuppressWarnings("unchecked")
		Mono<String> mono = (Mono<String>) out;
		StepVerifier.create(mono).expectNext("plain").verifyComplete();
		verify(histogram, never()).record(anyDouble(), any(Attributes.class));
	}

}
