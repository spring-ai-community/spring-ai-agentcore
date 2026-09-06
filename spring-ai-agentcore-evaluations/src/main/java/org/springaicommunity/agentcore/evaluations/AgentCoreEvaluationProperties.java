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

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for AgentCore Evaluations.
 *
 * @param enabled whether evaluation is enabled (default: false, opt-in)
 * @param region AWS region for the Evaluate API
 * @param evaluatorIds list of evaluator IDs to run (e.g., "Builtin.Helpfulness")
 * @param async whether to run evaluations asynchronously (default: true). Boxed so that
 * {@code null} means "not set" and we apply the intended default; a primitive
 * {@code boolean} would silently default to {@code false} when the property is omitted
 * from the environment.
 * @param metricsEnabled whether to publish Micrometer metrics (default: true)
 * @param sampleRate sampling rate for evaluations (0.0-1.0, default: 1.0 = evaluate all).
 * Boxed so that {@code null} means "not set" and we apply the intended default; a
 * primitive {@code double} would silently default to {@code 0.0} (skip everything).
 * @param includeHistory when {@code true}, the advisor includes prior messages from
 * {@code request.prompt().getInstructions()} (system messages, previous user/assistant
 * turns, any tool-response messages visible to the advisor) in the span body's
 * {@code input.messages} array, alongside the current user prompt. Default: {@code false}
 * — preserves the single-message baseline wire shape verified against the Evaluate API.
 * Opt-in because the multi-message payload grows with conversation length; long sessions
 * at high sample rates produce O(n²) bytes on the wire.
 * @param executorPoolSize number of threads in the fixed pool used to run evaluations
 * asynchronously and to fan out per-evaluator calls (default: 10). Boxed so that
 * {@code null} means "not set" and we apply the intended default; a primitive {@code int}
 * would silently default to {@code 0} (an illegal pool size). Sized for blocking Bedrock
 * I/O rather than CPU work.
 * @author Andrei Shakirin
 */
@ConfigurationProperties(AgentCoreEvaluationProperties.CONFIG_PREFIX)
public record AgentCoreEvaluationProperties(boolean enabled, String region, List<String> evaluatorIds, Boolean async,
		Boolean metricsEnabled, Double sampleRate, Boolean includeHistory, Integer executorPoolSize) {

	/**
	 * configuration prefix for evaluation properties.
	 */
	public static final String CONFIG_PREFIX = "spring.ai.agentcore.evaluations";

	/**
	 * default evaluator IDs used when none are configured.
	 */
	public static final List<String> DEFAULT_EVALUATOR_IDS = List.of("Builtin.Helpfulness");

	/**
	 * default size of the evaluation executor thread pool.
	 */
	public static final int DEFAULT_EXECUTOR_POOL_SIZE = 10;

	public AgentCoreEvaluationProperties {
		if (evaluatorIds == null || evaluatorIds.isEmpty()) {
			evaluatorIds = DEFAULT_EVALUATOR_IDS;
		}
		if (async == null) {
			async = Boolean.TRUE;
		}
		if (metricsEnabled == null) {
			metricsEnabled = Boolean.TRUE;
		}
		if (sampleRate == null || sampleRate < 0.0 || sampleRate > 1.0) {
			sampleRate = 1.0;
		}
		if (includeHistory == null) {
			includeHistory = Boolean.FALSE;
		}
		if (executorPoolSize == null || executorPoolSize < 1) {
			executorPoolSize = DEFAULT_EXECUTOR_POOL_SIZE;
		}
	}

}
