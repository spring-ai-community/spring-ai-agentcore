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

package org.springaicommunity.agentcore.throttle;

import org.junit.jupiter.api.Test;
import org.springaicommunity.agentcore.annotation.AgentCoreInvocation;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
		properties = { "agentcore.throttle.invocations-limit=2", "agentcore.throttle.ping-limit=3" })
class RateLimitingFilterTests {

	@LocalServerPort
	private int port;

	private final RestTemplate restTemplate = new RestTemplate();

	@Test
	void shouldThrottleInvocationsEndpoint() {
		String url = "http://localhost:" + this.port + "/invocations";

		// First two requests should succeed
		ResponseEntity<String> response1 = this.restTemplate.postForEntity(url, "test1", String.class);
		assertThat(response1.getStatusCode()).isEqualTo(HttpStatus.OK);

		ResponseEntity<String> response2 = this.restTemplate.postForEntity(url, "test2", String.class);
		assertThat(response2.getStatusCode()).isEqualTo(HttpStatus.OK);

		// Third request should be throttled
		try {
			ResponseEntity<String> response3 = this.restTemplate.postForEntity(url, "test3", String.class);
			assertThat(response3.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
		}

		catch (HttpClientErrorException ex) {
			assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
		}
	}

	@Test
	void shouldThrottlePingEndpoint() {
		String url = "http://localhost:" + this.port + "/ping";

		// First three requests should succeed
		for (int i = 0; i < 3; i++) {
			ResponseEntity<String> response = this.restTemplate.getForEntity(url, String.class);
			assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		}

		// Fourth request should be throttled
		try {
			ResponseEntity<String> response = this.restTemplate.getForEntity(url, String.class);
			assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
		}

		catch (HttpClientErrorException ex) {
			assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
		}
	}

	@Test
	void shouldUseXForwardedForHeaderForClientIdentification() {
		String url = "http://localhost:" + this.port + "/invocations";

		// Create RestTemplate with interceptor to add X-Forwarded-For header
		RestTemplate clientWithHeader = new RestTemplate();
		clientWithHeader.getInterceptors().add((request, body, execution) -> {
			request.getHeaders().add("X-Forwarded-For", "192.168.1.100");
			return execution.execute(request, body);
		});

		// First two requests with X-Forwarded-For should succeed
		for (int i = 0; i < 2; i++) {
			ResponseEntity<String> response = clientWithHeader.postForEntity(url, "test", String.class);
			assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		}

		// Third request with same X-Forwarded-For should be throttled
		try {
			clientWithHeader.postForEntity(url, "test", String.class);
		}
		catch (HttpClientErrorException ex) {
			assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
		}

		// Request from different IP (different X-Forwarded-For) should succeed
		RestTemplate clientWithDifferentIp = new RestTemplate();
		clientWithDifferentIp.getInterceptors().add((request, body, execution) -> {
			request.getHeaders().add("X-Forwarded-For", "192.168.1.200");
			return execution.execute(request, body);
		});

		ResponseEntity<String> response = clientWithDifferentIp.postForEntity(url, "test", String.class);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
	}

	@SpringBootApplication(scanBasePackages = "org.springaicommunity.agentcore.autoconfigure")
	static class ContextTestApp {

		@Service
		public static class TestAgentService {

			@AgentCoreInvocation
			public String handleWithContext(String request) {
				return "Message: " + request;
			}

		}

	}

}
