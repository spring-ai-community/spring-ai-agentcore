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

package org.springaicommunity.agentcore.throttle;

import org.junit.jupiter.api.Test;
import org.springaicommunity.agentcore.annotation.AgentCoreInvocation;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = { "agentcore.throttle.invocations-limit=2", "agentcore.throttle.ping-limit=3" })
class RateLimitingFilterTest {

	@LocalServerPort
	private int port;

	private final RestTemplate restTemplate = new RestTemplate();

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

	@Test
	void shouldThrottleInvocationsEndpoint() {
		String url = "http://localhost:" + port + "/invocations";

		// First two requests should succeed
		ResponseEntity<String> response1 = restTemplate.postForEntity(url, "test1", String.class);
		assertEquals(HttpStatus.OK, response1.getStatusCode());

		ResponseEntity<String> response2 = restTemplate.postForEntity(url, "test2", String.class);
		assertEquals(HttpStatus.OK, response2.getStatusCode());

		// Third request should be throttled
		try {
			ResponseEntity<String> response3 = restTemplate.postForEntity(url, "test3", String.class);
			assertEquals(HttpStatus.TOO_MANY_REQUESTS, response3.getStatusCode());
		}

		catch (org.springframework.web.client.HttpClientErrorException e) {
			assertEquals(HttpStatus.TOO_MANY_REQUESTS, e.getStatusCode());
		}
	}

	@Test
	void shouldThrottlePingEndpoint() {
		String url = "http://localhost:" + port + "/ping";

		// First three requests should succeed
		for (int i = 0; i < 3; i++) {
			ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);
			assertEquals(HttpStatus.OK, response.getStatusCode());
		}

		// Fourth request should be throttled
		try {
			ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);
			assertEquals(HttpStatus.TOO_MANY_REQUESTS, response.getStatusCode());
		}

		catch (org.springframework.web.client.HttpClientErrorException e) {
			assertEquals(HttpStatus.TOO_MANY_REQUESTS, e.getStatusCode());
		}
	}

	@Test
	void shouldUseXForwardedForHeaderForClientIdentification() {
		String url = "http://localhost:" + port + "/invocations";

		// Create RestTemplate with interceptor to add X-Forwarded-For header
		RestTemplate clientWithHeader = new RestTemplate();
		clientWithHeader.getInterceptors().add((request, body, execution) -> {
			request.getHeaders().add("X-Forwarded-For", "192.168.1.100");
			return execution.execute(request, body);
		});

		// First two requests with X-Forwarded-For should succeed
		for (int i = 0; i < 2; i++) {
			ResponseEntity<String> response = clientWithHeader.postForEntity(url, "test", String.class);
			assertEquals(HttpStatus.OK, response.getStatusCode());
		}

		// Third request with same X-Forwarded-For should be throttled
		try {
			clientWithHeader.postForEntity(url, "test", String.class);
		}
		catch (org.springframework.web.client.HttpClientErrorException e) {
			assertEquals(HttpStatus.TOO_MANY_REQUESTS, e.getStatusCode());
		}

		// Request from different IP (different X-Forwarded-For) should succeed
		RestTemplate clientWithDifferentIp = new RestTemplate();
		clientWithDifferentIp.getInterceptors().add((request, body, execution) -> {
			request.getHeaders().add("X-Forwarded-For", "192.168.1.200");
			return execution.execute(request, body);
		});

		ResponseEntity<String> response = clientWithDifferentIp.postForEntity(url, "test", String.class);
		assertEquals(HttpStatus.OK, response.getStatusCode());
	}

}
