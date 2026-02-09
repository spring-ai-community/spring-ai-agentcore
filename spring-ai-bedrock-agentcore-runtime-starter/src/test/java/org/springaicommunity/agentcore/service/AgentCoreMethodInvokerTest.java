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

import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springaicommunity.agentcore.annotation.AgentCoreInvocation;
import org.springaicommunity.agentcore.exception.AgentCoreInvocationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentCoreMethodInvokerTest {

	@Mock
	private ObjectMapper mockObjectMapper;

	@Mock
	private AgentCoreMethodRegistry mockRegistry;

	private AgentCoreMethodInvoker invoker;

	private Object testRequest;

	@BeforeEach
	void setUp() {
		invoker = new AgentCoreMethodInvoker(mockObjectMapper, mockRegistry);
		testRequest = "test prompt";
	}

	@Test
	void shouldInvokeStringToStringMethod() throws Exception {
		var testBean = new TestBean();
		var method = TestBean.class.getDeclaredMethod("stringMethod", String.class);

		when(mockRegistry.hasAgentMethod()).thenReturn(true);
		when(mockRegistry.getAgentMethod()).thenReturn(method);
		when(mockRegistry.getAgentBean()).thenReturn(testBean);

		var result = invoker.invokeAgentMethod(testRequest);

		assertThat(result).isEqualTo("Response: test prompt");
	}

	@Test
	void shouldInvokeMapMethod() throws Exception {
		var testBean = new TestBean();
		var method = TestBean.class.getDeclaredMethod("mapMethod", Map.class);
		var mapRequest = Map.of("prompt", "test prompt");

		when(mockRegistry.hasAgentMethod()).thenReturn(true);
		when(mockRegistry.getAgentMethod()).thenReturn(method);
		when(mockRegistry.getAgentBean()).thenReturn(testBean);

		var result = invoker.invokeAgentMethod(mapRequest);

		assertThat(result).isEqualTo("Map response: test prompt");
	}

	@Test
	void shouldInvokeCustomTypeMethod() throws Exception {
		var testBean = new TestBean();
		var method = TestBean.class.getDeclaredMethod("customTypeMethod", CustomRequest.class);
		var convertedRequest = new CustomRequest("test prompt");

		when(mockRegistry.hasAgentMethod()).thenReturn(true);
		when(mockRegistry.getAgentMethod()).thenReturn(method);
		when(mockRegistry.getAgentBean()).thenReturn(testBean);

		var result = invoker.invokeAgentMethod(convertedRequest);

		assertThat(result).isEqualTo("Custom response: test prompt");
	}

	@Test
	void shouldInvokeNoArgsMethod() throws Exception {
		var testBean = new TestBean();
		var method = TestBean.class.getDeclaredMethod("noArgsMethod");

		when(mockRegistry.hasAgentMethod()).thenReturn(true);
		when(mockRegistry.getAgentMethod()).thenReturn(method);
		when(mockRegistry.getAgentBean()).thenReturn(testBean);

		var result = invoker.invokeAgentMethod(testRequest);

		assertThat(result).isEqualTo("No args response");
	}

	@Test
	void shouldThrowExceptionWhenNoMethodRegistered() {
		when(mockRegistry.hasAgentMethod()).thenReturn(false);

		assertThatThrownBy(() -> invoker.invokeAgentMethod(testRequest))
			.isInstanceOf(AgentCoreInvocationException.class)
			.hasMessage("No @AgentCoreInvocation method found");
	}

	@Test
	void shouldThrowExceptionForUnsupportedSignature() throws Exception {
		var testBean = new TestBean();
		var method = TestBean.class.getDeclaredMethod("unsupportedMethod", String.class, String.class);

		when(mockRegistry.hasAgentMethod()).thenReturn(true);
		when(mockRegistry.getAgentMethod()).thenReturn(method);
		when(mockRegistry.getAgentBean()).thenReturn(testBean);

		assertThatThrownBy(() -> invoker.invokeAgentMethod(testRequest))
			.isInstanceOf(AgentCoreInvocationException.class)
			.hasMessage("Unsupported parameter combination");
	}

	@Test
	void shouldPropagateMethodExceptions() throws Exception {
		var testBean = new TestBean();
		var method = TestBean.class.getDeclaredMethod("throwingMethod", String.class);

		when(mockRegistry.hasAgentMethod()).thenReturn(true);
		when(mockRegistry.getAgentMethod()).thenReturn(method);
		when(mockRegistry.getAgentBean()).thenReturn(testBean);

		assertThatThrownBy(() -> invoker.invokeAgentMethod(testRequest)).isInstanceOf(RuntimeException.class)
			.hasMessage("Method exception");
	}

	@Test
	void shouldInjectAgentCoreContext() throws Exception {
		var testBean = new TestBean();
		var method = TestBean.class.getDeclaredMethod("contextMethod",
				org.springaicommunity.agentcore.context.AgentCoreContext.class);
		var headers = new org.springframework.http.HttpHeaders();
		headers.add("test-header", "test-value");

		when(mockRegistry.hasAgentMethod()).thenReturn(true);
		when(mockRegistry.getAgentMethod()).thenReturn(method);
		when(mockRegistry.getAgentBean()).thenReturn(testBean);

		var result = invoker.invokeAgentMethod(testRequest, headers);

		assertThat(result).isEqualTo("Context response: test-value");
	}

	@Test
	void shouldInjectBothRequestAndContext() throws Exception {
		var testBean = new TestBean();
		var method = TestBean.class.getDeclaredMethod("requestAndContextMethod", String.class,
				org.springaicommunity.agentcore.context.AgentCoreContext.class);
		var headers = new org.springframework.http.HttpHeaders();
		headers.add("session-id", "session-123");

		when(mockRegistry.hasAgentMethod()).thenReturn(true);
		when(mockRegistry.getAgentMethod()).thenReturn(method);
		when(mockRegistry.getAgentBean()).thenReturn(testBean);

		var result = invoker.invokeAgentMethod(testRequest, headers);

		assertThat(result).isEqualTo("Request: test prompt, Session: session-123");
	}

	static class TestBean {

		@AgentCoreInvocation
		public String stringMethod(String prompt) {
			return "Response: " + prompt;
		}

		@AgentCoreInvocation
		public String mapMethod(Map<String, Object> request) {
			return "Map response: " + request.get("prompt");
		}

		@AgentCoreInvocation
		public String customTypeMethod(CustomRequest request) {
			return "Custom response: " + request.prompt();
		}

		@AgentCoreInvocation
		public String noArgsMethod() {
			return "No args response";
		}

		@AgentCoreInvocation
		public String unsupportedMethod(String arg1, String arg2) {
			return "Should not be called";
		}

		@AgentCoreInvocation
		public String throwingMethod(String prompt) {
			throw new RuntimeException("Method exception");
		}

		@AgentCoreInvocation
		public String contextMethod(org.springaicommunity.agentcore.context.AgentCoreContext context) {
			return "Context response: " + context.getHeader("test-header");
		}

		@AgentCoreInvocation
		public String requestAndContextMethod(String prompt,
				org.springaicommunity.agentcore.context.AgentCoreContext context) {
			return "Request: " + prompt + ", Session: " + context.getHeader("session-id");
		}

	}

	static class CustomRequest {

		private final String prompt;

		CustomRequest(String prompt) {
			this.prompt = prompt;
		}

		public String prompt() {
			return prompt;
		}

	}

}
