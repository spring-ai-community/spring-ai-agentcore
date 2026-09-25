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

package org.springaicommunity.agentcore.memory.session;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import org.springframework.ai.session.Session;
import org.springframework.ai.session.SessionRepository;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards against silent drift of the pre-1.0 {@link SessionRepository} SPI. Every minor
 * release of spring-ai-session so far has changed the SPI (0.5.0 to 0.8.0 replaced
 * {@code replaceEvents} with {@code compactEvents} and changed {@code findById}). An
 * abstract addition fails compilation on its own, but a new {@code default} method would
 * compile and be silently inherited, even when its behavior is wrong for an append-only
 * AgentCore log. This test fails a dependency bump with the exact method name so the new
 * method gets an explicit AgentCore decision.
 *
 * @author Spring AI Community
 */
class SessionRepositorySpiConformanceTests {

	@Test
	void everySpiMethodIsDeclaredByAgentCoreSessionRepository() throws Exception {
		List<String> inherited = new ArrayList<>();
		for (Method spi : SessionRepository.class.getMethods()) {
			if (Modifier.isStatic(spi.getModifiers()) || spi.getDeclaringClass() == Object.class) {
				continue;
			}
			try {
				Method impl = AgentCoreSessionRepository.class.getDeclaredMethod(spi.getName(),
						spi.getParameterTypes());
				assertThat(impl.isBridge()).as("bridge for %s", spi).isFalse();
			}
			catch (NoSuchMethodException ex) {
				inherited.add(spi.getName() + Arrays.toString(spi.getParameterTypes()));
			}
		}
		assertThat(inherited)
			.as("SessionRepository methods not overridden by AgentCoreSessionRepository; decide how AgentCore should"
					+ " implement each one (or reject it with UnsupportedOperationException) and declare it explicitly")
			.isEmpty();
	}

	@Test
	void spiShapeMatchesTheVersionThisModuleIsBuiltFor() throws Exception {
		// Pins the 0.8.0 shape that AgentCoreSessionRepository and the startup
		// compatibility check rely on.
		assertThat(SessionRepository.class.getMethod("findById", String.class).getReturnType())
			.isEqualTo(Session.class);
		assertThat(SessionRepository.class.getMethod("compactEvents", String.class, List.class, List.class, long.class)
			.getReturnType()).isEqualTo(boolean.class);
		assertThat(Arrays.stream(SessionRepository.class.getMethods()).map(Method::getName))
			.doesNotContain("replaceEvents");
	}

}
