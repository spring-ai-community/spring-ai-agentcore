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

package org.springaicommunity.agentcore.controller;

import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springaicommunity.agentcore.autoconfigure.AgentCoreAutoConfiguration;
import org.springaicommunity.agentcore.exception.AgentCoreInvocationException;
import org.springaicommunity.agentcore.ping.AgentCoreTaskTracker;
import org.springaicommunity.agentcore.service.AgentCoreMethodInvoker;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = { AgentCoreInvocationsController.class })
@Import({ AgentCoreAutoConfiguration.class, AgentCoreInvocationsControllerTests.TestConfig.class })
class AgentCoreInvocationsControllerTests {

	@Autowired
	private MockMvc mockMvc;

	@MockBean
	private AgentCoreMethodInvoker mockInvoker;

	@MockBean
	private AgentCoreTaskTracker mockTaskTracker;

	@Autowired
	private ObjectMapper objectMapper;

	@Test
	void shouldHandleStringInput() throws Exception {
		given(this.mockInvoker.invokeAgentMethod(eq("hello"), any(HttpHeaders.class))).willReturn("world");

		this.mockMvc.perform(post("/invocations").contentType(MediaType.APPLICATION_JSON).content("\"hello\""))
			.andExpect(status().isOk())
			.andExpect(content().string("world"));
	}

	@Test
	void shouldHandleObjectInput() throws Exception {
		var input = new TestInput("test");
		var output = new TestOutput("result");

		given(this.mockInvoker.invokeAgentMethod(any(), any(HttpHeaders.class))).willReturn(output);

		this.mockMvc
			.perform(post("/invocations").contentType(MediaType.APPLICATION_JSON)
				.content(this.objectMapper.writeValueAsString(input)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.value").value("result"));
	}

	@Test
	void shouldHandleMapInput() throws Exception {
		var inputMap = Map.of("key", "value", "number", 42);
		var outputMap = Map.of("result", "processed", "input", inputMap);

		given(this.mockInvoker.invokeAgentMethod(any(), any(HttpHeaders.class))).willReturn(outputMap);

		this.mockMvc
			.perform(post("/invocations").contentType(MediaType.APPLICATION_JSON)
				.content(this.objectMapper.writeValueAsString(inputMap)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.result").value("processed"))
			.andExpect(jsonPath("$.input.key").value("value"))
			.andExpect(jsonPath("$.input.number").value(42));
	}

	@Test
	void shouldHandleTextPlainInput() throws Exception {
		given(this.mockInvoker.invokeAgentMethod(eq("plain text input"), any(HttpHeaders.class)))
			.willReturn("processed text");

		this.mockMvc.perform(post("/invocations").contentType(MediaType.TEXT_PLAIN).content("plain text input"))
			.andExpect(status().isOk())
			.andExpect(content().string("processed text"));
	}

	@Test
	void shouldHandleBinaryDataOutputWithJsonInput() throws Exception {
		String binaryData = "Binary response";
		var response = binaryData.getBytes();
		given(this.mockInvoker.invokeAgentMethod(any(), any(HttpHeaders.class))).willReturn(response);

		this.mockMvc
			.perform(post("/invocations").contentType(MediaType.APPLICATION_JSON)
				.accept(MediaType.APPLICATION_OCTET_STREAM)
				.content("""
						{"prompt":"test"}"""))
			.andExpect(status().isOk())
			.andExpect(content().contentType(MediaType.APPLICATION_OCTET_STREAM))
			.andExpect(content().bytes(binaryData.getBytes()));
	}

	@Test
	void shouldHandleWrappedBinaryDataOutputWithJsonInput() throws Exception {
		String binaryData = "Binary response";
		var response = ResponseEntity.ok()
			.contentType(MediaType.APPLICATION_OCTET_STREAM)
			.body((binaryData.getBytes()));
		given(this.mockInvoker.invokeAgentMethod(any(), any(HttpHeaders.class))).willReturn(response);

		this.mockMvc.perform(post("/invocations").contentType(MediaType.APPLICATION_JSON).content("""
				{"prompt":"test"}"""))
			.andExpect(status().isOk())
			.andExpect(content().contentType(MediaType.APPLICATION_OCTET_STREAM))
			.andExpect(content().bytes(binaryData.getBytes()));
	}

	@Test
	void shouldHandleBinaryDataOutputWithTextInput() throws Exception {
		String binaryData = "Binary response";
		var response = binaryData.getBytes();
		given(this.mockInvoker.invokeAgentMethod(any(), any(HttpHeaders.class))).willReturn(response);

		this.mockMvc
			.perform(post("/invocations").contentType(MediaType.TEXT_PLAIN)
				.accept(MediaType.APPLICATION_OCTET_STREAM)
				.content("test"))
			.andExpect(status().isOk())
			.andExpect(content().contentType(MediaType.APPLICATION_OCTET_STREAM))
			.andExpect(content().bytes(binaryData.getBytes()));
	}

	@Test
	void shouldHandleWrappedBinaryDataOutputWithTextInput() throws Exception {
		String binaryData = "Binary response";
		var response = ResponseEntity.ok()
			.contentType(MediaType.APPLICATION_OCTET_STREAM)
			.body((binaryData.getBytes()));
		given(this.mockInvoker.invokeAgentMethod(any(), any(HttpHeaders.class))).willReturn(response);

		this.mockMvc.perform(post("/invocations").contentType(MediaType.TEXT_PLAIN).content("test"))
			.andExpect(status().isOk())
			.andExpect(content().contentType(MediaType.APPLICATION_OCTET_STREAM))
			.andExpect(content().bytes(binaryData.getBytes()));
	}

	@Test
	void shouldHandleException() throws Exception {
		given(this.mockInvoker.invokeAgentMethod(any(), any(HttpHeaders.class)))
			.willThrow(new AgentCoreInvocationException("Test error"));

		this.mockMvc.perform(post("/invocations").contentType(MediaType.APPLICATION_JSON).content("{ }"))
			.andExpect(status().isInternalServerError());
	}

	@SpringBootApplication
	static class TestConfig {

	}

	record TestInput(String data) {
	}

	record TestOutput(String value) {
	}

}
