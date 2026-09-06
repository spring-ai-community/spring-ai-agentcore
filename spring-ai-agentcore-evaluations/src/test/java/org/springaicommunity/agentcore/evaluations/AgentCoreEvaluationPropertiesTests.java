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

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AgentCoreEvaluationPropertiesTests {

	@Test
	void unsetDefaultsMatchDocumentedValues() {
		AgentCoreEvaluationProperties props = new AgentCoreEvaluationProperties(true, null, null, null, null, null,
				null, null);

		assertThat(props.evaluatorIds()).isEqualTo(AgentCoreEvaluationProperties.DEFAULT_EVALUATOR_IDS);
		assertThat(props.async()).isTrue();
		assertThat(props.metricsEnabled()).isTrue();
		assertThat(props.sampleRate()).isEqualTo(1.0);
		assertThat(props.includeHistory()).isFalse();
		assertThat(props.executorPoolSize()).isEqualTo(AgentCoreEvaluationProperties.DEFAULT_EXECUTOR_POOL_SIZE);
	}

	@Test
	void explicitValuesAreHonoured() {
		AgentCoreEvaluationProperties props = new AgentCoreEvaluationProperties(true, "us-west-2", null, false, false,
				0.25, true, 4);

		assertThat(props.async()).isFalse();
		assertThat(props.metricsEnabled()).isFalse();
		assertThat(props.sampleRate()).isEqualTo(0.25);
		assertThat(props.region()).isEqualTo("us-west-2");
		assertThat(props.includeHistory()).isTrue();
		assertThat(props.executorPoolSize()).isEqualTo(4);
	}

	@Test
	void outOfRangeSampleRateFallsBackToOne() {
		assertThat(new AgentCoreEvaluationProperties(true, null, null, null, null, -0.5, null, null).sampleRate())
			.isEqualTo(1.0);
		assertThat(new AgentCoreEvaluationProperties(true, null, null, null, null, 1.5, null, null).sampleRate())
			.isEqualTo(1.0);
	}

	@Test
	void nonPositiveExecutorPoolSizeFallsBackToDefault() {
		assertThat(new AgentCoreEvaluationProperties(true, null, null, null, null, null, null, 0).executorPoolSize())
			.isEqualTo(AgentCoreEvaluationProperties.DEFAULT_EXECUTOR_POOL_SIZE);
		assertThat(new AgentCoreEvaluationProperties(true, null, null, null, null, null, null, -1).executorPoolSize())
			.isEqualTo(AgentCoreEvaluationProperties.DEFAULT_EXECUTOR_POOL_SIZE);
	}

}
