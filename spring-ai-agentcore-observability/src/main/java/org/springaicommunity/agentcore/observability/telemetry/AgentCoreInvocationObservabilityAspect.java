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

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

/**
 * Wraps the AgentCore HTTP controller entry points (not the user
 * {@code @AgentCoreInvocation} bean) so Spring AOP does not proxy agent implementations.
 * AgentCore's method scanner reflects on each bean type; CGLIB proxies for advised
 * services may not retain annotations on overridden methods, which would prevent
 * registration of invocation handlers.
 *
 * @author Vaquar Khan
 */
@Aspect
public class AgentCoreInvocationObservabilityAspect {

	private final Tracer tracer;

	private final DoubleHistogram tokenUsageHistogram;

	public AgentCoreInvocationObservabilityAspect(Tracer tracer, Meter meter) {
		this.tracer = tracer;
		this.tokenUsageHistogram = meter.histogramBuilder(GenAiTelemetrySupport.METRIC_GEN_AI_CLIENT_TOKEN_USAGE)
			.setUnit("{token}")
			.build();
	}

	@Around("execution(* org.springaicommunity.agentcore.controller"
			+ ".AgentCoreInvocationsController.handleJsonInvocation(..))"
			+ " || execution(* org.springaicommunity.agentcore.controller"
			+ ".AgentCoreInvocationsController.handleTextInvocation(..))")
	public Object aroundAgentCoreController(ProceedingJoinPoint joinPoint) throws Throwable {
		Span span = this.tracer.spanBuilder(GenAiTelemetrySupport.OP_CHAT).setSpanKind(SpanKind.CLIENT).startSpan();
		AtomicBoolean deferredEnd = new AtomicBoolean(false);
		try (Scope scope = span.makeCurrent()) {
			this.applyAgentCoreRequestAttributes(span, joinPoint);
			Object result = joinPoint.proceed();
			if (result instanceof Mono<?>) {
				deferredEnd.set(true);
				return this.instrumentMono((Mono<?>) result, span);
			}
			if (result instanceof Flux<?>) {
				deferredEnd.set(true);
				return this.instrumentFlux((Flux<?>) result, span);
			}
			if (result instanceof ChatResponse response) {
				this.applyGenAiAttributes(span, response);
			}
			span.setStatus(StatusCode.OK);
			return result;
		}
		catch (Throwable ex) {
			span.setStatus(StatusCode.ERROR);
			span.recordException(ex);
			span.setAttribute(GenAiTelemetrySupport.ERROR_TYPE, errorType(ex));
			throw ex;
		}
		finally {
			if (!deferredEnd.get()) {
				span.end();
			}
		}
	}

	private void applyAgentCoreRequestAttributes(Span span, ProceedingJoinPoint joinPoint) {
		String session = firstHeader(joinPoint, GenAiTelemetrySupport.HTTP_HEADER_AGENTCORE_SESSION_ID);
		if (session != null && !session.isEmpty()) {
			span.setAttribute(GenAiTelemetrySupport.AWS_BEDROCK_AGENTCORE_SESSION_ID, session);
		}
		String requestId = firstHeaderMatching(joinPoint, GenAiTelemetrySupport.HTTP_HEADER_AMZN_REQUEST_ID_ALIASES);
		if (requestId != null && !requestId.isEmpty()) {
			span.setAttribute(GenAiTelemetrySupport.AWS_REQUEST_ID, requestId);
		}
	}

	private static String firstHeaderMatching(ProceedingJoinPoint joinPoint, String... names) {
		for (String name : names) {
			String v = firstHeader(joinPoint, name);
			if (v != null && !v.isEmpty()) {
				return v;
			}
		}
		return null;
	}

	private static String firstHeader(ProceedingJoinPoint joinPoint, String name) {
		return AgentCoreInvocationHeaderSupport.firstHeader(joinPoint, name);
	}

	private <T> Mono<T> instrumentMono(Mono<T> mono, Span span) {
		return mono.doOnNext((r) -> {
			if (r instanceof ChatResponse response) {
				this.applyGenAiAttributes(span, response);
			}
		}).doOnSuccess((v) -> span.setStatus(StatusCode.OK)).doOnError((t) -> {
			span.setStatus(StatusCode.ERROR);
			span.recordException(t);
			span.setAttribute(GenAiTelemetrySupport.ERROR_TYPE, errorType(t));
		}).doFinally((sig) -> span.end());
	}

	/**
	 * Streaming {@link ChatResponse} sequences: token usage and metrics are only
	 * meaningful on the final chunk in typical providers, so we record GenAI attributes
	 * once from the last emission.
	 * @param <T> the element type of the Flux
	 * @param flux the reactive stream to instrument
	 * @param span the OpenTelemetry span to enrich
	 * @return the instrumented Flux with telemetry callbacks
	 */
	private <T> Flux<T> instrumentFlux(Flux<T> flux, Span span) {
		AtomicReference<ChatResponse> lastResponse = new AtomicReference<>();
		return flux.doOnNext((r) -> {
			if (r instanceof ChatResponse response) {
				lastResponse.set(response);
			}
		}).doOnComplete(() -> {
			ChatResponse last = lastResponse.get();
			if (last != null) {
				this.applyGenAiAttributes(span, last);
			}
			span.setStatus(StatusCode.OK);
		}).doOnError((t) -> {
			span.setStatus(StatusCode.ERROR);
			span.recordException(t);
			span.setAttribute(GenAiTelemetrySupport.ERROR_TYPE, errorType(t));
		}).doFinally((sig) -> span.end());
	}

	private void applyGenAiAttributes(Span span, ChatResponse response) {
		String operation = (response.hasToolCalls()) ? GenAiTelemetrySupport.OP_EXECUTE_TOOL
				: GenAiTelemetrySupport.OP_CHAT;
		span.updateName(operation);

		span.setAttribute(GenAiTelemetrySupport.GEN_AI_OPERATION_NAME, operation);
		span.setAttribute(GenAiTelemetrySupport.GEN_AI_PROVIDER_NAME, GenAiTelemetrySupport.PROVIDER_AWS_BEDROCK);
		span.setAttribute(GenAiTelemetrySupport.GEN_AI_SYSTEM, GenAiTelemetrySupport.PROVIDER_AWS_BEDROCK);

		ChatResponseMetadata metadata = response.getMetadata();
		String model = (metadata != null) ? metadata.getModel() : null;
		// Model name comes from ChatResponseMetadata. If the provider omitted it
		// (unusual for Bedrock), leave it unset rather than reach into Environment.
		if (model != null && !model.isEmpty()) {
			span.setAttribute(GenAiTelemetrySupport.GEN_AI_REQUEST_MODEL, model);
			span.setAttribute(GenAiTelemetrySupport.GEN_AI_RESPONSE_MODEL, model);
		}

		Usage usage = (metadata != null) ? metadata.getUsage() : null;
		long inputTokens = baseInputTokens(usage) + cacheReadTokens(metadata);
		long outputTokens = (usage != null && usage.getCompletionTokens() != null)
				? usage.getCompletionTokens().longValue() : 0L;

		span.setAttribute(GenAiTelemetrySupport.GEN_AI_USAGE_INPUT_TOKENS, inputTokens);
		span.setAttribute(GenAiTelemetrySupport.GEN_AI_USAGE_OUTPUT_TOKENS, outputTokens);

		List<String> finish = new ArrayList<>();
		for (Generation g : response.getResults()) {
			if (g.getMetadata() != null && g.getMetadata().getFinishReason() != null) {
				finish.add(g.getMetadata().getFinishReason());
			}
		}
		if (!finish.isEmpty()) {
			span.setAttribute(GenAiTelemetrySupport.GEN_AI_RESPONSE_FINISH_REASONS,
					finish.stream().distinct().toList());
		}

		String modelAttr = (model != null && !model.isEmpty()) ? model : "unknown";
		Attributes inputMetricAttrs = Attributes.builder()
			.put(GenAiTelemetrySupport.GEN_AI_TOKEN_TYPE, "input")
			.put(GenAiTelemetrySupport.GEN_AI_REQUEST_MODEL, modelAttr)
			.put(GenAiTelemetrySupport.GEN_AI_PROVIDER_NAME, GenAiTelemetrySupport.PROVIDER_AWS_BEDROCK)
			.build();
		Attributes outputMetricAttrs = Attributes.builder()
			.put(GenAiTelemetrySupport.GEN_AI_TOKEN_TYPE, "output")
			.put(GenAiTelemetrySupport.GEN_AI_REQUEST_MODEL, modelAttr)
			.put(GenAiTelemetrySupport.GEN_AI_PROVIDER_NAME, GenAiTelemetrySupport.PROVIDER_AWS_BEDROCK)
			.build();
		this.tokenUsageHistogram.record((double) inputTokens, inputMetricAttrs);
		this.tokenUsageHistogram.record((double) outputTokens, outputMetricAttrs);
	}

	private static long baseInputTokens(Usage usage) {
		if (usage == null || usage.getPromptTokens() == null) {
			return 0L;
		}
		return usage.getPromptTokens().longValue();
	}

	private static long cacheReadTokens(ChatResponseMetadata metadata) {
		if (metadata == null) {
			return 0L;
		}
		Object v = metadata.get("cacheReadInputTokens");
		if (v instanceof Number n) {
			return n.longValue();
		}
		return 0L;
	}

	private static String errorType(Throwable t) {
		String cn = t.getClass().getName();
		String simpleName = t.getClass().getSimpleName().toLowerCase(Locale.ROOT);
		if (cn.contains("Throttling")) {
			return "rate_limit";
		}
		if (cn.contains("ValidationException") || cn.contains("InvalidParameter") || cn.contains("IllegalArgument")) {
			return "invalid_request";
		}
		if (cn.contains("Timeout") || cn.contains("RequestTimeout") || cn.contains("ReadTimeout")
				|| simpleName.contains("timeout")) {
			return "timeout";
		}
		if (cn.contains("Auth") || cn.contains("Security") || cn.endsWith("AuthenticationException")
				|| cn.contains("AccessDenied") || simpleName.contains("auth") || simpleName.contains("security")) {
			return "authentication_failure";
		}
		if (cn.contains("amazon.awssdk") && cn.contains("ServiceException")) {
			return "server_error";
		}
		return "server_error";
	}

}
