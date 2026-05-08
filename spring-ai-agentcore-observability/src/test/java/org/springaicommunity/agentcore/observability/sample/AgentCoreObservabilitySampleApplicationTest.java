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

package org.springaicommunity.agentcore.observability.sample;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;

class AgentCoreObservabilitySampleApplicationTest {

	@Test
	void mainDelegatesToSpringApplicationRun() {
		ConfigurableApplicationContext ctx = mock(ConfigurableApplicationContext.class);
		try (MockedStatic<SpringApplication> spring = mockStatic(SpringApplication.class)) {
			spring.when(() -> SpringApplication.run(eq(AgentCoreObservabilitySampleApplication.class), any()))
				.thenReturn(ctx);
			AgentCoreObservabilitySampleApplication.main(new String[] { "--spring.main.banner-mode=off" });
			spring.verify(() -> SpringApplication.run(eq(AgentCoreObservabilitySampleApplication.class),
					any(String[].class)));
		}
	}

}
