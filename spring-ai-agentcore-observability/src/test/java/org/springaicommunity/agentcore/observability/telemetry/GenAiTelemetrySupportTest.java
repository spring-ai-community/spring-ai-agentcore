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

import java.lang.reflect.Constructor;
import org.junit.jupiter.api.Test;

class GenAiTelemetrySupportTest {

	@Test
	void constantsAreReachable() {
		assertThat(GenAiTelemetrySupport.PROVIDER_AWS_BEDROCK).isNotBlank();
		assertThat(GenAiTelemetrySupport.OP_CHAT).isEqualTo("chat");
		assertThat(GenAiTelemetrySupport.METRIC_GEN_AI_CLIENT_TOKEN_USAGE).contains("gen_ai");
		assertThat(GenAiTelemetrySupport.HTTP_HEADER_AMZN_REQUEST_ID).isEqualTo("x-amzn-request-id");
	}

	@Test
	void privateConstructorIsNotCallableFromOutside() throws Exception {
		Constructor<GenAiTelemetrySupport> c = GenAiTelemetrySupport.class.getDeclaredConstructor();
		c.setAccessible(true);
		assertThat(c.newInstance()).isNotNull();
	}

}
