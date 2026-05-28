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

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Event fired when evaluation completes, containing all results and metadata.
 *
 * @param sessionId the session ID that was evaluated
 * @param traceId the trace ID used for the evaluation
 * @param results list of evaluation results from all evaluators
 * @param timestamp when the evaluation completed
 * @param latency how long the evaluation took
 * @author Maximilian Schellhorn
 */
public record EvaluationEvent(String sessionId, String traceId, List<EvaluationResult> results, Instant timestamp,
		Duration latency) {

	/**
	 * Returns true if all evaluations passed (score >= 0.5).
	 */
	public boolean allPassing() {
		return this.results != null && this.results.stream().allMatch(EvaluationResult::isPassing);
	}

	/**
	 * Returns the average score across all evaluators.
	 */
	public double averageScore() {
		if (this.results == null || this.results.isEmpty()) {
			return 0.0;
		}
		return this.results.stream()
			.filter((r) -> r.score() != null)
			.mapToDouble(EvaluationResult::score)
			.average()
			.orElse(0.0);
	}

}
