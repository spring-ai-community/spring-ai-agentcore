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

package org.springaicommunity.agentcore.evaluations;

import java.time.Duration;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springaicommunity.agentcore.evaluations.client.EvaluationResult;

/**
 * Micrometer metrics for AgentCore Evaluations.
 *
 * <p>
 * Publishes the following metrics:
 * <ul>
 * <li>{@code agentcore.evaluation.score} - Distribution summary of evaluation scores</li>
 * <li>{@code agentcore.evaluation.count} - Counter of evaluations by label</li>
 * <li>{@code agentcore.evaluation.latency} - Timer for evaluation API call duration</li>
 * <li>{@code agentcore.evaluation.errors} - Counter of failed evaluations</li>
 * </ul>
 *
 * @author Andrei Shakirin
 */
public class AgentCoreEvaluationMetrics {

	private static final String METRIC_PREFIX = "agentcore.evaluation";

	private final MeterRegistry registry;

	public AgentCoreEvaluationMetrics(MeterRegistry registry) {
		this.registry = registry;
	}

	/**
	 * Record an evaluation result.
	 * @param evaluatorId the evaluator that produced the result
	 * @param result the evaluation result
	 * @param latency how long the evaluation took
	 */
	public void record(String evaluatorId, EvaluationResult result, Duration latency) {
		// Record score distribution
		if (result.score() != null) {
			DistributionSummary.builder(METRIC_PREFIX + ".score")
				.tag("evaluator", evaluatorId)
				.description("Evaluation score distribution")
				.register(this.registry)
				.record(result.score());
		}

		// Record count by label
		String label = (result.label() != null) ? result.label() : "unknown";
		Counter.builder(METRIC_PREFIX + ".count")
			.tag("evaluator", evaluatorId)
			.tag("label", label)
			.description("Count of evaluations by label")
			.register(this.registry)
			.increment();

		// Record latency
		Timer.builder(METRIC_PREFIX + ".latency")
			.tag("evaluator", evaluatorId)
			.description("Evaluation API call duration")
			.register(this.registry)
			.record(latency);
	}

	/**
	 * Record an evaluation error.
	 * @param evaluatorId the evaluator that failed
	 * @param errorCode the error code or exception type
	 */
	public void recordError(String evaluatorId, String errorCode) {
		Counter.builder(METRIC_PREFIX + ".errors")
			.tag("evaluator", evaluatorId)
			.tag("error_code", errorCode)
			.description("Count of failed evaluations")
			.register(this.registry)
			.increment();
	}

}
