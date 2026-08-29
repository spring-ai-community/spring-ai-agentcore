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

package org.springaicommunity.agentcore.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springaicommunity.agentcore.annotation.AgentCoreInvocation;
import org.springaicommunity.agentcore.context.AgentCoreContext;
import org.springaicommunity.agentcore.exception.AgentCoreInvocationException;

import org.springframework.http.HttpHeaders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class AgentCoreMethodInvokerTests {

	@Mock
	private ObjectMapper mockObjectMapper;

	@Mock
	private AgentCoreMethodRegistry mockRegistry;

	private AgentCoreMethodInvoker invoker;

	private Object testRequest;

	@BeforeEach
	void setUp() {
		this.invoker = new AgentCoreMethodInvoker(this.mockObjectMapper, this.mockRegistry, List.of());
		this.testRequest = "test prompt";
	}

	@Test
	void shouldInvokeStringToStringMethod() throws Exception {
		var testBean = new TestBean();
		var method = TestBean.class.getDeclaredMethod("stringMethod", String.class);

		given(this.mockRegistry.hasAgentMethod()).willReturn(true);
		given(this.mockRegistry.getAgentMethod()).willReturn(method);
		given(this.mockRegistry.getAgentBean()).willReturn(testBean);

		var result = this.invoker.invokeAgentMethod(this.testRequest);

		assertThat(result).isEqualTo("Response: test prompt");
	}

	@Test
	void shouldInvokeMapMethod() throws Exception {
		var testBean = new TestBean();
		var method = TestBean.class.getDeclaredMethod("mapMethod", Map.class);
		var mapRequest = Map.of("prompt", "test prompt");

		given(this.mockRegistry.hasAgentMethod()).willReturn(true);
		given(this.mockRegistry.getAgentMethod()).willReturn(method);
		given(this.mockRegistry.getAgentBean()).willReturn(testBean);

		var result = this.invoker.invokeAgentMethod(mapRequest);

		assertThat(result).isEqualTo("Map response: test prompt");
	}

	@Test
	void shouldInvokeCustomTypeMethod() throws Exception {
		var testBean = new TestBean();
		var method = TestBean.class.getDeclaredMethod("customTypeMethod", CustomRequest.class);
		var convertedRequest = new CustomRequest("test prompt");

		given(this.mockRegistry.hasAgentMethod()).willReturn(true);
		given(this.mockRegistry.getAgentMethod()).willReturn(method);
		given(this.mockRegistry.getAgentBean()).willReturn(testBean);

		var result = this.invoker.invokeAgentMethod(convertedRequest);

		assertThat(result).isEqualTo("Custom response: test prompt");
	}

	@Test
	void shouldInvokeNoArgsMethod() throws Exception {
		var testBean = new TestBean();
		var method = TestBean.class.getDeclaredMethod("noArgsMethod");

		given(this.mockRegistry.hasAgentMethod()).willReturn(true);
		given(this.mockRegistry.getAgentMethod()).willReturn(method);
		given(this.mockRegistry.getAgentBean()).willReturn(testBean);

		var result = this.invoker.invokeAgentMethod(this.testRequest);

		assertThat(result).isEqualTo("No args response");
	}

	@Test
	void shouldThrowExceptionWhenNoMethodRegistered() {
		given(this.mockRegistry.hasAgentMethod()).willReturn(false);

		assertThatThrownBy(() -> this.invoker.invokeAgentMethod(this.testRequest))
			.isInstanceOf(AgentCoreInvocationException.class)
			.hasMessage("No @AgentCoreInvocation method found");
	}

	@Test
	void shouldThrowExceptionForUnsupportedSignature() throws Exception {
		var testBean = new TestBean();
		var method = TestBean.class.getDeclaredMethod("unsupportedMethod", String.class, String.class);

		given(this.mockRegistry.hasAgentMethod()).willReturn(true);
		given(this.mockRegistry.getAgentMethod()).willReturn(method);
		given(this.mockRegistry.getAgentBean()).willReturn(testBean);

		assertThatThrownBy(() -> this.invoker.invokeAgentMethod(this.testRequest))
			.isInstanceOf(AgentCoreInvocationException.class)
			.hasMessage("Unsupported parameter combination");
	}

	@Test
	void shouldPropagateMethodExceptions() throws Exception {
		var testBean = new TestBean();
		var method = TestBean.class.getDeclaredMethod("throwingMethod", String.class);

		given(this.mockRegistry.hasAgentMethod()).willReturn(true);
		given(this.mockRegistry.getAgentMethod()).willReturn(method);
		given(this.mockRegistry.getAgentBean()).willReturn(testBean);

		assertThatThrownBy(() -> this.invoker.invokeAgentMethod(this.testRequest)).isInstanceOf(RuntimeException.class)
			.hasMessage("Method exception");
	}

	@Test
	void shouldInjectAgentCoreContext() throws Exception {
		var testBean = new TestBean();
		var method = TestBean.class.getDeclaredMethod("contextMethod", AgentCoreContext.class);
		var headers = new HttpHeaders();
		headers.add("test-header", "test-value");

		given(this.mockRegistry.hasAgentMethod()).willReturn(true);
		given(this.mockRegistry.getAgentMethod()).willReturn(method);
		given(this.mockRegistry.getAgentBean()).willReturn(testBean);

		var result = this.invoker.invokeAgentMethod(this.testRequest, headers);

		assertThat(result).isEqualTo("Context response: test-value");
	}

	@Test
	void shouldInjectBothRequestAndContext() throws Exception {
		var testBean = new TestBean();
		var method = TestBean.class.getDeclaredMethod("requestAndContextMethod", String.class, AgentCoreContext.class);
		var headers = new HttpHeaders();
		headers.add("session-id", "session-123");

		given(this.mockRegistry.hasAgentMethod()).willReturn(true);
		given(this.mockRegistry.getAgentMethod()).willReturn(method);
		given(this.mockRegistry.getAgentBean()).willReturn(testBean);

		var result = this.invoker.invokeAgentMethod(this.testRequest, headers);

		assertThat(result).isEqualTo("Request: test prompt, Session: session-123");
	}

	@Test
	void shouldProcessResultBeforeCallbackCleanup() throws Exception {
		var testBean = new TestBean();
		var method = TestBean.class.getDeclaredMethod("stringMethod", String.class);
		var events = new ArrayList<String>();
		AgentCoreInvocationCallback callback = new AgentCoreInvocationCallback() {
			@Override
			public void beforeInvocation(Object request, HttpHeaders headers) {
				events.add("before");
			}

			@Override
			public Object processResult(Object request, HttpHeaders headers, Object result) {
				events.add("result");
				return result + " transformed";
			}

			@Override
			public void afterInvocation(Object request, HttpHeaders headers) {
				events.add("after");
			}
		};
		AgentCoreMethodInvoker callbackInvoker = new AgentCoreMethodInvoker(this.mockObjectMapper, this.mockRegistry,
				List.of(callback));

		given(this.mockRegistry.hasAgentMethod()).willReturn(true);
		given(this.mockRegistry.getAgentMethod()).willReturn(method);
		given(this.mockRegistry.getAgentBean()).willReturn(testBean);

		Object result = callbackInvoker.invokeAgentMethod(this.testRequest);

		assertThat(result).isEqualTo("Response: test prompt transformed");
		assertThat(events).containsExactly("before", "result", "after");
	}

	@Test
	void shouldSortCallbacksAndCleanUpInReverseOrder() throws Exception {
		var testBean = new TestBean();
		var method = TestBean.class.getDeclaredMethod("stringMethod", String.class);
		var events = new ArrayList<String>();
		AgentCoreInvocationCallback lowPrecedence = callback("low", 100, events, false);
		AgentCoreInvocationCallback highPrecedence = callback("high", -100, events, false);
		AgentCoreMethodInvoker callbackInvoker = new AgentCoreMethodInvoker(this.mockObjectMapper, this.mockRegistry,
				List.of(lowPrecedence, highPrecedence));

		given(this.mockRegistry.hasAgentMethod()).willReturn(true);
		given(this.mockRegistry.getAgentMethod()).willReturn(method);
		given(this.mockRegistry.getAgentBean()).willReturn(testBean);

		callbackInvoker.invokeAgentMethod(this.testRequest);

		assertThat(events).containsExactly("high-before", "low-before", "low-after", "high-after");
	}

	@Test
	void shouldContinueCleanupAfterCallbackFailure() throws Exception {
		var testBean = new TestBean();
		var method = TestBean.class.getDeclaredMethod("stringMethod", String.class);
		var events = new ArrayList<String>();
		AgentCoreInvocationCallback first = callback("first", -100, events, false);
		AgentCoreInvocationCallback failing = callback("failing", 100, events, true);
		AgentCoreMethodInvoker callbackInvoker = new AgentCoreMethodInvoker(this.mockObjectMapper, this.mockRegistry,
				List.of(first, failing));

		given(this.mockRegistry.hasAgentMethod()).willReturn(true);
		given(this.mockRegistry.getAgentMethod()).willReturn(method);
		given(this.mockRegistry.getAgentBean()).willReturn(testBean);

		assertThatThrownBy(() -> callbackInvoker.invokeAgentMethod(this.testRequest))
			.isInstanceOf(IllegalStateException.class)
			.hasMessage("failing cleanup");
		assertThat(events).containsExactly("first-before", "failing-before", "failing-after", "first-after");
	}

	private static AgentCoreInvocationCallback callback(String name, int order, List<String> events,
			boolean failOnCleanup) {
		return new AgentCoreInvocationCallback() {
			@Override
			public void beforeInvocation(Object request, HttpHeaders headers) {
				events.add(name + "-before");
			}

			@Override
			public void afterInvocation(Object request, HttpHeaders headers) {
				events.add(name + "-after");
				if (failOnCleanup) {
					throw new IllegalStateException(name + " cleanup");
				}
			}

			@Override
			public int getOrder() {
				return order;
			}
		};
	}

	static class TestBean {

		@AgentCoreInvocation
		String stringMethod(String prompt) {
			return "Response: " + prompt;
		}

		@AgentCoreInvocation
		String mapMethod(Map<String, Object> request) {
			return "Map response: " + request.get("prompt");
		}

		@AgentCoreInvocation
		String customTypeMethod(CustomRequest request) {
			return "Custom response: " + request.prompt();
		}

		@AgentCoreInvocation
		String noArgsMethod() {
			return "No args response";
		}

		@AgentCoreInvocation
		String unsupportedMethod(String arg1, String arg2) {
			return "Should not be called";
		}

		@AgentCoreInvocation
		String throwingMethod(String prompt) {
			throw new RuntimeException("Method exception");
		}

		@AgentCoreInvocation
		String contextMethod(AgentCoreContext context) {
			return "Context response: " + context.getHeader("test-header");
		}

		@AgentCoreInvocation
		String requestAndContextMethod(String prompt, AgentCoreContext context) {
			return "Request: " + prompt + ", Session: " + context.getHeader("session-id");
		}

	}

	static class CustomRequest {

		private final String prompt;

		CustomRequest(String prompt) {
			this.prompt = prompt;
		}

		String prompt() {
			return this.prompt;
		}

	}

}
