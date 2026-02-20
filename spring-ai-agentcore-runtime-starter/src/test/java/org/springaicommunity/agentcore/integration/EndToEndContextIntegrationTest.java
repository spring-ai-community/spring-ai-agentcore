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

package org.springaicommunity.agentcore.integration;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springaicommunity.agentcore.annotation.AgentCoreInvocation;
import org.springaicommunity.agentcore.context.AgentCoreContext;
import org.springaicommunity.agentcore.context.AgentCoreHeaders;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = EndToEndContextIntegrationTest.ContextTestApp.class,
		webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class EndToEndContextIntegrationTest {

	@SpringBootApplication(scanBasePackages = "org.springaicommunity.agentcore.autoconfigure")
	static class ContextTestApp {

		@Service
		public static class TestAgentService {

			@AgentCoreInvocation
			public String handleWithContext(Map<String, String> request, AgentCoreContext context) {
				String message = request.get("message");
				String sessionId = context.getHeader(AgentCoreHeaders.SESSION_ID);
				String customHeader = context.getHeader("X-Custom-Header");
				return "Message: " + message + ", Session: " + sessionId + ", Custom: " + customHeader;
			}

		}

	}

	@LocalServerPort
	private int port;

	@Autowired
	private TestRestTemplate restTemplate;

	@Test
	void shouldInjectContextWithHeaders() {
		var request = Map.of("message", "Hello Context");

		var headers = new HttpHeaders();
		headers.set(AgentCoreHeaders.SESSION_ID, "session-123");
		headers.set("X-Custom-Header", "custom-value");

		var entity = new HttpEntity<>(request, headers);

		var response = restTemplate.postForEntity("http://localhost:" + port + "/invocations", entity, String.class);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getBody()).isEqualTo("Message: Hello Context, Session: session-123, Custom: custom-value");
	}

}
