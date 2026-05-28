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

/**
 * Result from an AgentCore evaluation.
 *
 * @param evaluatorId the evaluator that produced this result (e.g.,
 * "Builtin.Helpfulness")
 * @param score the evaluation score (typically 0.0 to 1.0)
 * @param label human-readable label (e.g., "Good", "Excellent", "Poor")
 * @param explanation detailed explanation of the score
 * @param inputTokens tokens used for input
 * @param outputTokens tokens used for output
 * @param errorCode error code when the API could not produce a score (null on success)
 * @param errorMessage human-readable error detail when {@code errorCode} is set (null on
 * success)
 * @author Maximilian Schellhorn
 */
public record EvaluationResult(String evaluatorId, Double score, String label, String explanation, Integer inputTokens,
		Integer outputTokens, String errorCode, String errorMessage) {

	/**
	 * Backwards-compatible constructor for callers that do not supply an
	 * {@code errorMessage}.
	 */
	public EvaluationResult(String evaluatorId, Double score, String label, String explanation, Integer inputTokens,
			Integer outputTokens, String errorCode) {
		this(evaluatorId, score, label, explanation, inputTokens, outputTokens, errorCode, null);
	}

	/**
	 * Returns true if this result indicates a passing score (>= 0.5).
	 */
	public boolean isPassing() {
		return this.score != null && this.score >= 0.5;
	}

	/**
	 * Returns true if the API returned an error for this evaluation instead of a score.
	 */
	public boolean isError() {
		return this.errorCode != null;
	}

}
