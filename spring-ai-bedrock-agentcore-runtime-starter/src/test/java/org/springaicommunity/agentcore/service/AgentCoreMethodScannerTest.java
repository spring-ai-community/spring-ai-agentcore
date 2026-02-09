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

package org.springaicommunity.agentcore.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springaicommunity.agentcore.annotation.AgentCoreInvocation;
import org.springaicommunity.agentcore.exception.AgentCoreInvocationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AgentCoreMethodScannerTest {

	@Mock
	private AgentCoreMethodRegistry mockRegistry;

	private AgentCoreMethodScanner scanner;

	@BeforeEach
	void setUp() {
		scanner = new AgentCoreMethodScanner(mockRegistry);
	}

	@Test
	void shouldDiscoverAndRegisterAgentCoreInvocationMethod() {
		var bean = new BeanWithSingleMethod();

		var result = scanner.postProcessAfterInitialization(bean, "testBean");

		assertThat(result).isSameAs(bean);
		verify(mockRegistry).registerMethod(eq(bean), any());
	}

	@Test
	void shouldIgnoreBeansWithoutAgentCoreInvocationMethods() {
		var bean = new BeanWithoutAnnotation();

		var result = scanner.postProcessAfterInitialization(bean, "testBean");

		assertThat(result).isSameAs(bean);
		verify(mockRegistry, never()).registerMethod(any(), any());
	}

	@Test
	void shouldPropagateRegistryExceptionForMultipleMethods() {
		var bean = new BeanWithMultipleMethods();
		doThrow(new AgentCoreInvocationException("Multiple methods")).when(mockRegistry).registerMethod(any(), any());

		assertThatThrownBy(() -> scanner.postProcessAfterInitialization(bean, "testBean"))
			.isInstanceOf(AgentCoreInvocationException.class)
			.hasMessage("Multiple methods");
	}

	static class BeanWithSingleMethod {

		@AgentCoreInvocation
		public String handleRequest(String input) {
			return "response";
		}

		public void regularMethod() {
			// Not annotated
		}

	}

	static class BeanWithoutAnnotation {

		public String regularMethod(String input) {
			return "response";
		}

		public void anotherMethod() {
			// No annotations
		}

	}

	static class BeanWithMultipleMethods {

		@AgentCoreInvocation
		public String firstMethod(String input) {
			return "response1";
		}

		@AgentCoreInvocation
		public String secondMethod(String input) {
			return "response2";
		}

	}

}
