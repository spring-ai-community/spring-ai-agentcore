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

package org.springaicommunity.agentcore.integration;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springaicommunity.agentcore.annotation.AgentCoreInvocation;
import org.springaicommunity.agentcore.context.AgentCoreContext;
import org.springaicommunity.agentcore.context.AgentCoreHeaders;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Status codes that reach the caller when the {@code @AgentCoreInvocation} method throws.
 */
@AutoConfigureTestRestTemplate
@SpringBootTest(classes = EndToEndErrorStatusIntegrationTests.ErrorStatusTestApp.class,
		webEnvironment = WebEnvironment.RANDOM_PORT)
class EndToEndErrorStatusIntegrationTests {

	private static final String USER_ID_HEADER = AgentCoreHeaders.CUSTOM_HEADER_PREFIX + "User-Id";

	@LocalServerPort
	private int port;

	@Autowired
	private TestRestTemplate restTemplate;

	@Test
	void shouldReturnOkWhenUserIdIsPresent() {
		assertThat(this.invoke("ok", "alice").getStatusCode()).isEqualTo(HttpStatus.OK);
	}

	@Test
	void shouldReturnBadRequestWhenUserIdIsMissing() {
		assertThat(this.invoke("ok", null).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
	}

	@Test
	void shouldPassResponseStatusFromHandlerToCaller() {
		assertThat(this.invoke("forbidden", "alice").getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
	}

	@Test
	void shouldReturnServerErrorForOtherExceptions() {
		assertThat(this.invoke("bug", "alice").getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
	}

	private ResponseEntity<String> invoke(String action, String userId) {
		var headers = new HttpHeaders();
		if (userId != null) {
			headers.set(USER_ID_HEADER, userId);
		}
		return this.restTemplate.postForEntity("http://localhost:" + this.port + "/invocations",
				new HttpEntity<>(Map.of("action", action), headers), String.class);
	}

	@SpringBootApplication(scanBasePackages = "org.springaicommunity.agentcore.autoconfigure")
	static class ErrorStatusTestApp {

		@Service
		public static class TestAgentService {

			@AgentCoreInvocation
			public String handle(Map<String, String> request, AgentCoreContext context) {
				String userId = context.getHeader(USER_ID_HEADER);
				// Same failure as AgentCoreMemoryConversationIdParser.of(userId, ...)
				if (userId == null || userId.isBlank()) {
					throw new IllegalArgumentException("userId must not be null or blank");
				}
				return switch (request.get("action")) {
					case "forbidden" -> throw new ResponseStatusException(HttpStatus.FORBIDDEN, "not allowed");
					case "bug" -> throw new IllegalStateException("server bug");
					default -> "ok";
				};
			}

		}

	}

}
