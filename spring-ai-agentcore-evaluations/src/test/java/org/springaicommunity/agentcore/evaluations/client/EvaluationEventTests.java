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

package org.springaicommunity.agentcore.evaluations.client;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class EvaluationEventTests {

	private static EvaluationEvent event(List<EvaluationResult> results) {
		return new EvaluationEvent("sess", "trace", results, Instant.EPOCH, Duration.ZERO);
	}

	@Test
	void averageScoreIgnoresNullScores() {
		EvaluationEvent e = event(List.of(result(0.8), result(null), result(0.4)));
		assertThat(e.averageScore()).isCloseTo(0.6, within(1e-9));
	}

	@Test
	void averageScoreIsZeroWhenNoResults() {
		assertThat(event(List.of()).averageScore()).isEqualTo(0.0);
	}

	@Test
	void allPassingRequiresEveryResultAboveThreshold() {
		assertThat(event(List.of(result(0.9), result(0.5))).allPassing()).isTrue();
		assertThat(event(List.of(result(0.9), result(0.4))).allPassing()).isFalse();
		assertThat(event(List.of(result(0.9), result(null))).allPassing()).isFalse();
	}

	private static EvaluationResult result(Double score) {
		return new EvaluationResult("evaluator", score, null, null, null, null, null);
	}

}
