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

package org.springaicommunity.agentcore.autoconfigure;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springaicommunity.agentcore.annotation.AgentCoreInvocation;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = AgentCoreIntegrationTest.TestApp.class,
		webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AgentCoreIntegrationTest {

	@SpringBootApplication(scanBasePackages = "org.springaicommunity.agentcore.autoconfigure")
	static class TestApp {

		@Service
		public static class TestAgentService {

			@AgentCoreInvocation
			public Map<String, Object> handleRequest(Map<String, Object> request) {
				return Map.of("response", "Integration: " + request.get("message"), "status", "success");
			}

		}

	}

	@LocalServerPort
	private int port;

	@Autowired
	private TestRestTemplate restTemplate;

	@Test
	void shouldHandleInvocationsEndpoint() {
		var request = Map.of("message", "Hello World");

		var response = restTemplate.postForEntity("http://localhost:" + port + "/invocations", request, Map.class);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getBody()).containsEntry("response", "Integration: Hello World");
		assertThat(response.getBody()).containsEntry("status", "success");
	}

	@Test
	void shouldHandlePingEndpoint() {
		var response = restTemplate.getForEntity("http://localhost:" + port + "/ping", Map.class);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getBody()).containsEntry("status", "Healthy");
		assertThat(response.getBody()).containsKey("time_of_last_update");
	}

}
