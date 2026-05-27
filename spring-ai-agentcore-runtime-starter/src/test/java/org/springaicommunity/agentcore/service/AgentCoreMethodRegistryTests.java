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

@ExtendWith(MockitoExtension.class)
class AgentCoreMethodRegistryTests {

	private AgentCoreMethodRegistry registry;

	@Mock
	private Object mockBean;

	@BeforeEach
	void setUp() {
		this.registry = new AgentCoreMethodRegistry();
	}

	@Test
	void shouldRegisterSingleMethod() throws NoSuchMethodException {
		var method = TestBean.class.getDeclaredMethod("testMethod");

		this.registry.registerMethod(this.mockBean, method);

		assertThat(this.registry.hasAgentMethod()).isTrue();
		assertThat(this.registry.getAgentBean()).isEqualTo(this.mockBean);
		assertThat(this.registry.getAgentMethod()).isEqualTo(method);
	}

	@Test
	void shouldMakeMethodAccessible() throws NoSuchMethodException {
		var method = TestBean.class.getDeclaredMethod("privateMethod");
		var testBean = new TestBean();

		// Register the method (this should make it accessible)
		this.registry.registerMethod(testBean, method);

		// Verify the method is now accessible
		assertThat(method.canAccess(testBean)).isTrue();

		// Verify registry state
		assertThat(this.registry.hasAgentMethod()).isTrue();
		assertThat(this.registry.getAgentBean()).isEqualTo(testBean);
		assertThat(this.registry.getAgentMethod()).isEqualTo(method);
	}

	@Test
	void shouldThrowExceptionWhenRegisteringMultipleMethods() throws NoSuchMethodException {
		var method1 = TestBean.class.getDeclaredMethod("testMethod");
		var method2 = TestBean.class.getDeclaredMethod("anotherMethod");

		this.registry.registerMethod(this.mockBean, method1);

		assertThatThrownBy(() -> this.registry.registerMethod(this.mockBean, method2))
			.isInstanceOf(AgentCoreInvocationException.class)
			.hasMessage("Multiple @AgentCoreInvocation methods found. Only one is allowed in MVP.");
	}

	@Test
	void shouldReturnFalseWhenNoMethodRegistered() {
		assertThat(this.registry.hasAgentMethod()).isFalse();
		assertThat(this.registry.getAgentBean()).isNull();
		assertThat(this.registry.getAgentMethod()).isNull();
	}

	static class TestBean {

		@AgentCoreInvocation
		void testMethod() {
		}

		@AgentCoreInvocation
		void anotherMethod() {
		}

		@AgentCoreInvocation
		private void privateMethod() {
		}

	}

}
