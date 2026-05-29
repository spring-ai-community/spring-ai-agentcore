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

import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeoutException;

import javax.naming.AuthenticationException;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.DoubleHistogramBuilder;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanBuilder;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import jakarta.servlet.http.HttpServletRequest;
import org.aspectj.lang.ProceedingJoinPoint;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import software.amazon.awssdk.core.exception.TestSdkServiceException;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.server.ServerWebExchange;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

class AgentCoreInvocationObservabilityAspectTests {

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
		this.tracer = mock(Tracer.class);
		this.spanBuilder = mock(SpanBuilder.class);
		this.span = mock(Span.class);
		this.scope = mock(Scope.class);
		this.meter = mock(Meter.class);
		this.histogramBuilder = mock(DoubleHistogramBuilder.class);
		this.histogram = mock(DoubleHistogram.class);

		given(this.tracer.spanBuilder(anyString())).willReturn(this.spanBuilder);
		given(this.spanBuilder.setSpanKind(any())).willReturn(this.spanBuilder);
		given(this.spanBuilder.startSpan()).willReturn(this.span);
		given(this.span.makeCurrent()).willReturn(this.scope);
		given(this.meter.histogramBuilder(anyString())).willReturn(this.histogramBuilder);
		given(this.histogramBuilder.setUnit(anyString())).willReturn(this.histogramBuilder);
		given(this.histogramBuilder.build()).willReturn(this.histogram);

		this.aspect = new AgentCoreInvocationObservabilityAspect(this.tracer, this.meter);
	}

	@Test
	void usesClientSpanKind() throws Throwable {
		ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
		given(pjp.proceed()).willReturn("ok");

		this.aspect.aroundAgentCoreController(pjp);

		then(this.spanBuilder).should().setSpanKind(SpanKind.CLIENT);
	}

	@Test
	void mapsAuthenticationTimeoutExceptionToTimeout() throws Throwable {
		class AuthenticationTimeoutException extends RuntimeException {

		}
		ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
		given(pjp.proceed()).willThrow(new AuthenticationTimeoutException());

		assertThatThrownBy(() -> this.aspect.aroundAgentCoreController(pjp))
			.isInstanceOf(AuthenticationTimeoutException.class);

		then(this.span).should().setAttribute(eq(GenAiTelemetrySupport.ERROR_TYPE), eq("timeout"));
	}

	@Test
	void recordsOkWhenResultIsNotChatResponse() throws Throwable {
		ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
		given(pjp.proceed()).willReturn("plain");

		Object out = this.aspect.aroundAgentCoreController(pjp);

		assertThat(out).isEqualTo("plain");
		then(this.span).should().setStatus(StatusCode.OK);
		then(this.histogram).should(never()).record(anyDouble(), any(Attributes.class));
	}

	@Test
	void recordsErrorAndMapsTimeout() throws Throwable {
		ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
		given(pjp.proceed()).willThrow(new TimeoutException("t"));

		assertThatThrownBy(() -> this.aspect.aroundAgentCoreController(pjp)).isInstanceOf(TimeoutException.class);

		ArgumentCaptor<String> err = ArgumentCaptor.forClass(String.class);
		then(this.span).should().setAttribute(eq(GenAiTelemetrySupport.ERROR_TYPE), err.capture());
		assertThat(err.getValue()).isEqualTo("timeout");
	}

	@Test
	void mapsSecurityKeywordInExceptionName() throws Throwable {
		ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
		class SslSecurityException extends RuntimeException {

		}
		given(pjp.proceed()).willThrow(new SslSecurityException());

		assertThatThrownBy(() -> this.aspect.aroundAgentCoreController(pjp)).isInstanceOf(SslSecurityException.class);

		ArgumentCaptor<String> err = ArgumentCaptor.forClass(String.class);
		then(this.span).should().setAttribute(eq(GenAiTelemetrySupport.ERROR_TYPE), err.capture());
		assertThat(err.getValue()).isEqualTo("authentication_failure");
	}

	@Test
	void mapsAuthenticationFailure() throws Throwable {
		ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
		given(pjp.proceed()).willThrow(new AuthenticationException());

		assertThatThrownBy(() -> this.aspect.aroundAgentCoreController(pjp))
			.isInstanceOf(AuthenticationException.class);

		ArgumentCaptor<String> err = ArgumentCaptor.forClass(String.class);
		then(this.span).should().setAttribute(eq(GenAiTelemetrySupport.ERROR_TYPE), err.capture());
		assertThat(err.getValue()).isEqualTo("authentication_failure");
	}

	@Test
	void mapsGenericErrorType() throws Throwable {
		ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
		given(pjp.proceed()).willThrow(new IllegalStateException("x"));

		assertThatThrownBy(() -> this.aspect.aroundAgentCoreController(pjp)).isInstanceOf(IllegalStateException.class);

		ArgumentCaptor<String> err = ArgumentCaptor.forClass(String.class);
		then(this.span).should().setAttribute(eq(GenAiTelemetrySupport.ERROR_TYPE), err.capture());
		assertThat(err.getValue()).isEqualTo("server_error");
	}

	@Test
	void applyGenAiUsesToolOperationWhenToolCallsPresent() throws Throwable {
		ChatResponse response = mock(ChatResponse.class);
		given(response.hasToolCalls()).willReturn(true);
		given(response.getMetadata()).willReturn(null);
		given(response.getResults()).willReturn(Collections.emptyList());

		ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
		given(pjp.proceed()).willReturn(response);

		this.aspect.aroundAgentCoreController(pjp);

		then(this.span).should().updateName(GenAiTelemetrySupport.OP_EXECUTE_TOOL);
	}

	@Test
	void treatsNullCompletionTokensAsZero() throws Throwable {
		Usage usage = mock(Usage.class);
		given(usage.getPromptTokens()).willReturn(3);
		given(usage.getCompletionTokens()).willReturn(null);

		ChatResponse response = ChatResponse.builder()
			.metadata(ChatResponseMetadata.builder().model("m").usage(usage).build())
			.generations(List.of(new Generation(new AssistantMessage("x"),
					ChatGenerationMetadata.builder().finishReason("stop").build())))
			.build();

		ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
		given(pjp.proceed()).willReturn(response);

		this.aspect.aroundAgentCoreController(pjp);

		then(this.span).should().setAttribute(GenAiTelemetrySupport.GEN_AI_USAGE_OUTPUT_TOKENS, 0L);
	}

	@Test
	void deduplicatesFinishReasonsAsStringArray() throws Throwable {
		ChatResponse response = ChatResponse.builder()
			.metadata(ChatResponseMetadata.builder().model("m").usage(new DefaultUsage(1, 1)).build())
			.generations(List.of(
					new Generation(new AssistantMessage("a"),
							ChatGenerationMetadata.builder().finishReason("stop").build()),
					new Generation(new AssistantMessage("b"),
							ChatGenerationMetadata.builder().finishReason("stop").build()),
					new Generation(new AssistantMessage("c"),
							ChatGenerationMetadata.builder().finishReason("tool_use").build())))
			.build();

		ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
		given(pjp.proceed()).willReturn(response);

		this.aspect.aroundAgentCoreController(pjp);

		then(this.span).should()
			.setAttribute(GenAiTelemetrySupport.GEN_AI_RESPONSE_FINISH_REASONS, List.of("stop", "tool_use"));
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
		given(pjp.proceed()).willReturn(response);

		this.aspect.aroundAgentCoreController(pjp);

		then(this.span).should().setAttribute(GenAiTelemetrySupport.GEN_AI_RESPONSE_FINISH_REASONS, List.of("stop"));
	}

	@Test
	void modelMetricsUseUnknownWhenModelGetterReturnsNull() throws Throwable {
		ChatResponseMetadata meta = mock(ChatResponseMetadata.class);
		given(meta.getUsage()).willReturn(new DefaultUsage(1, 1));
		given(meta.getModel()).willReturn(null);

		ChatResponse response = ChatResponse.builder()
			.metadata(meta)
			.generations(List.of(new Generation(new AssistantMessage("x"),
					ChatGenerationMetadata.builder().finishReason("stop").build())))
			.build();

		ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
		given(pjp.proceed()).willReturn(response);

		this.aspect.aroundAgentCoreController(pjp);

		then(this.histogram).should(times(2)).record(anyDouble(), any(Attributes.class));
	}

	@Test
	void skipsModelSpanAttributesWhenMetadataModelIsBlank() throws Throwable {
		ChatResponseMetadata meta = mock(ChatResponseMetadata.class);
		given(meta.getUsage()).willReturn(new DefaultUsage(1, 1));
		given(meta.getModel()).willReturn("");

		ChatResponse response = ChatResponse.builder()
			.metadata(meta)
			.generations(List.of(new Generation(new AssistantMessage("x"),
					ChatGenerationMetadata.builder().finishReason("stop").build())))
			.build();

		ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
		given(pjp.proceed()).willReturn(response);

		this.aspect.aroundAgentCoreController(pjp);

		then(this.span).should(never()).setAttribute(eq(GenAiTelemetrySupport.GEN_AI_REQUEST_MODEL), anyString());
		then(this.span).should(never()).setAttribute(eq(GenAiTelemetrySupport.GEN_AI_RESPONSE_MODEL), anyString());
	}

	@Test
	void usesUnknownModelWhenMetadataMissingModel() throws Throwable {
		ChatResponse response = ChatResponse.builder()
			.metadata(ChatResponseMetadata.builder().usage(new DefaultUsage(1, 1)).build())
			.generations(Collections.emptyList())
			.build();

		ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
		given(pjp.proceed()).willReturn(response);

		this.aspect.aroundAgentCoreController(pjp);

		then(this.histogram).should(times(2)).record(anyDouble(), any(Attributes.class));
	}

	@Test
	void baseInputTokensNullPromptTokens() throws Throwable {
		Usage usage = mock(Usage.class);
		given(usage.getPromptTokens()).willReturn(null);
		given(usage.getCompletionTokens()).willReturn(2);

		ChatResponse response = ChatResponse.builder()
			.metadata(ChatResponseMetadata.builder().model("m").usage(usage).build())
			.generations(Collections.emptyList())
			.build();

		ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
		given(pjp.proceed()).willReturn(response);

		this.aspect.aroundAgentCoreController(pjp);

		then(this.span).should().setAttribute(GenAiTelemetrySupport.GEN_AI_USAGE_INPUT_TOKENS, 0L);
	}

	@Test
	void appliesTokenHistogramsAndHandlesNullUsage() throws Throwable {
		ChatResponse response = mock(ChatResponse.class);
		given(response.hasToolCalls()).willReturn(false);
		given(response.getMetadata()).willReturn(null);
		given(response.getResults()).willReturn(Collections.emptyList());

		ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
		given(pjp.proceed()).willReturn(response);

		this.aspect.aroundAgentCoreController(pjp);

		then(this.histogram).should(times(2)).record(anyDouble(), any(Attributes.class));
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
		given(pjp.proceed()).willReturn(response);

		this.aspect.aroundAgentCoreController(pjp);

		then(this.span).should().setAttribute(GenAiTelemetrySupport.GEN_AI_USAGE_INPUT_TOKENS, 15L);
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
		given(pjp.proceed()).willReturn(response);

		this.aspect.aroundAgentCoreController(pjp);

		then(this.span).should().setAttribute(GenAiTelemetrySupport.GEN_AI_USAGE_INPUT_TOKENS, 10L);
	}

	@Test
	void appliesSessionIdFromHttpServletRequest() throws Throwable {
		HttpServletRequest req = mock(HttpServletRequest.class);
		given(req.getHeader(GenAiTelemetrySupport.HTTP_HEADER_AGENTCORE_SESSION_ID)).willReturn("sess-1");
		ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
		given(pjp.getArgs()).willReturn(new Object[] { req });
		given(pjp.proceed()).willReturn("ok");

		Object out = this.aspect.aroundAgentCoreController(pjp);

		assertThat(out).isEqualTo("ok");
		then(this.span).should().setAttribute(GenAiTelemetrySupport.AWS_BEDROCK_AGENTCORE_SESSION_ID, "sess-1");
	}

	@Test
	void instrumentsMonoChatResponse() throws Throwable {
		ChatResponse response = ChatResponse.builder()
			.metadata(ChatResponseMetadata.builder().model("m").usage(new DefaultUsage(1, 1)).build())
			.generations(List.of(new Generation(new AssistantMessage("hi"),
					ChatGenerationMetadata.builder().finishReason("stop").build())))
			.build();
		ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
		given(pjp.proceed()).willReturn(Mono.just(response));

		Object out = this.aspect.aroundAgentCoreController(pjp);

		assertThat(out).isInstanceOf(Mono.class);
		@SuppressWarnings("unchecked")
		Mono<ChatResponse> mono = (Mono<ChatResponse>) out;
		StepVerifier.create(mono).expectNext(response).verifyComplete();
		then(this.span).should().end();
	}

	@Test
	void instrumentsFluxChatResponse() throws Throwable {
		ChatResponse response = ChatResponse.builder()
			.metadata(ChatResponseMetadata.builder().model("m").usage(new DefaultUsage(1, 1)).build())
			.generations(List.of(new Generation(new AssistantMessage("hi"),
					ChatGenerationMetadata.builder().finishReason("stop").build())))
			.build();
		ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
		given(pjp.proceed()).willReturn(Flux.just(response));

		Object out = this.aspect.aroundAgentCoreController(pjp);

		assertThat(out).isInstanceOf(Flux.class);
		@SuppressWarnings("unchecked")
		Flux<ChatResponse> flux = (Flux<ChatResponse>) out;
		StepVerifier.create(flux).expectNext(response).verifyComplete();
		then(this.span).should().end();
	}

	@Test
	void fluxPipelineErrorRecordsOnSpan() throws Throwable {
		ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
		given(pjp.proceed()).willReturn(Flux.error(new IllegalStateException("stream failed")));

		Object out = this.aspect.aroundAgentCoreController(pjp);

		@SuppressWarnings("unchecked")
		Flux<?> flux = (Flux<?>) out;
		StepVerifier.create(flux).expectError(IllegalStateException.class).verify();

		then(this.span).should().setStatus(StatusCode.ERROR);
		then(this.span).should().recordException(any(IllegalStateException.class));
		then(this.span).should().setAttribute(eq(GenAiTelemetrySupport.ERROR_TYPE), eq("server_error"));
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
		given(pjp.proceed()).willReturn(Flux.just(first, last));

		Object out = this.aspect.aroundAgentCoreController(pjp);

		@SuppressWarnings("unchecked")
		Flux<ChatResponse> flux = (Flux<ChatResponse>) out;
		StepVerifier.create(flux).expectNext(first, last).verifyComplete();

		then(this.histogram).should(times(2)).record(anyDouble(), any(Attributes.class));
	}

	@Test
	void mapsAwsSdkServiceExceptionToServerError() throws Throwable {
		ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
		given(pjp.proceed()).willThrow(new TestSdkServiceException());

		assertThatThrownBy(() -> this.aspect.aroundAgentCoreController(pjp))
			.isInstanceOf(TestSdkServiceException.class);

		then(this.span).should().setAttribute(eq(GenAiTelemetrySupport.ERROR_TYPE), eq("server_error"));
	}

	@Test
	void monoCancelStillEndsSpan() throws Throwable {
		ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
		given(pjp.proceed()).willReturn(Mono.never());

		Object out = this.aspect.aroundAgentCoreController(pjp);
		@SuppressWarnings("unchecked")
		Mono<?> mono = (Mono<?>) out;
		StepVerifier.create(mono).thenCancel().verify();

		then(this.span).should().end();
	}

	@Test
	void mapsAccessDeniedToAuthenticationFailure() throws Throwable {
		class AccessDeniedException extends RuntimeException {

		}
		ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
		given(pjp.proceed()).willThrow(new AccessDeniedException());

		assertThatThrownBy(() -> this.aspect.aroundAgentCoreController(pjp)).isInstanceOf(AccessDeniedException.class);

		then(this.span).should().setAttribute(eq(GenAiTelemetrySupport.ERROR_TYPE), eq("authentication_failure"));
	}

	@Test
	void fluxCancelStillEndsSpan() throws Throwable {
		ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
		given(pjp.proceed()).willReturn(Flux.never());

		Object out = this.aspect.aroundAgentCoreController(pjp);
		@SuppressWarnings("unchecked")
		Flux<?> flux = (Flux<?>) out;
		StepVerifier.create(flux).thenCancel().verify();

		then(this.span).should().end();
	}

	@Test
	void mapsThrottlingExceptionToRateLimit() throws Throwable {
		class ThrottlingException extends RuntimeException {

		}
		ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
		given(pjp.proceed()).willThrow(new ThrottlingException());

		assertThatThrownBy(() -> this.aspect.aroundAgentCoreController(pjp)).isInstanceOf(ThrottlingException.class);

		then(this.span).should().setAttribute(eq(GenAiTelemetrySupport.ERROR_TYPE), eq("rate_limit"));
	}

	@Test
	void mapsValidationExceptionToInvalidRequest() throws Throwable {
		class ValidationException extends RuntimeException {

		}
		ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
		given(pjp.proceed()).willThrow(new ValidationException());

		assertThatThrownBy(() -> this.aspect.aroundAgentCoreController(pjp)).isInstanceOf(ValidationException.class);

		then(this.span).should().setAttribute(eq(GenAiTelemetrySupport.ERROR_TYPE), eq("invalid_request"));
	}

	@Test
	void mapsIllegalArgumentToInvalidRequest() throws Throwable {
		ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
		given(pjp.proceed()).willThrow(new IllegalArgumentException("bad"));

		assertThatThrownBy(() -> this.aspect.aroundAgentCoreController(pjp))
			.isInstanceOf(IllegalArgumentException.class);

		then(this.span).should().setAttribute(eq(GenAiTelemetrySupport.ERROR_TYPE), eq("invalid_request"));
	}

	@Test
	void appliesSessionFromServerWebExchange() throws Throwable {
		ServerWebExchange exchange = mock(ServerWebExchange.class);
		ServerHttpRequest req = mock(ServerHttpRequest.class);
		HttpHeaders headers = new HttpHeaders();
		headers.add(GenAiTelemetrySupport.HTTP_HEADER_AGENTCORE_SESSION_ID, "sess-wx");
		given(exchange.getRequest()).willReturn(req);
		given(req.getHeaders()).willReturn(headers);
		ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
		given(pjp.getArgs()).willReturn(new Object[] { exchange });
		given(pjp.proceed()).willReturn("ok");

		this.aspect.aroundAgentCoreController(pjp);

		then(this.span).should().setAttribute(GenAiTelemetrySupport.AWS_BEDROCK_AGENTCORE_SESSION_ID, "sess-wx");
	}

	@Test
	void appliesAwsRequestIdHeader() throws Throwable {
		HttpServletRequest req = mock(HttpServletRequest.class);
		given(req.getHeader(GenAiTelemetrySupport.HTTP_HEADER_AMZN_REQUEST_ID)).willReturn("rid-9");
		ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
		given(pjp.getArgs()).willReturn(new Object[] { req });
		given(pjp.proceed()).willReturn("ok");

		this.aspect.aroundAgentCoreController(pjp);

		then(this.span).should().setAttribute(GenAiTelemetrySupport.AWS_REQUEST_ID, "rid-9");
	}

	@Test
	void appliesLegacyUndashedAwsRequestIdWhenStandardHeaderAbsent() throws Throwable {
		HttpServletRequest req = mock(HttpServletRequest.class);
		given(req.getHeader("x-amzn-request-id")).willReturn(null);
		given(req.getHeader("x-amzn-requestid")).willReturn("rid-legacy");
		ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
		given(pjp.getArgs()).willReturn(new Object[] { req });
		given(pjp.proceed()).willReturn("ok");

		this.aspect.aroundAgentCoreController(pjp);

		then(this.span).should().setAttribute(GenAiTelemetrySupport.AWS_REQUEST_ID, "rid-legacy");
	}

	@Test
	void monoErrorRecordsServerErrorType() throws Throwable {
		ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
		given(pjp.proceed()).willReturn(Mono.error(new IllegalStateException("b")));

		Object out = this.aspect.aroundAgentCoreController(pjp);
		@SuppressWarnings("unchecked")
		Mono<?> mono = (Mono<?>) out;
		StepVerifier.create(mono).expectError(IllegalStateException.class).verify();
		then(this.span).should().setAttribute(eq(GenAiTelemetrySupport.ERROR_TYPE), eq("server_error"));
	}

	@Test
	void monoWithNonChatResponseSkipsGenAiAttributes() throws Throwable {
		ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
		given(pjp.proceed()).willReturn(Mono.just("plain"));

		Object out = this.aspect.aroundAgentCoreController(pjp);
		@SuppressWarnings("unchecked")
		Mono<String> mono = (Mono<String>) out;
		StepVerifier.create(mono).expectNext("plain").verifyComplete();
		then(this.histogram).should(never()).record(anyDouble(), any(Attributes.class));
	}

}
