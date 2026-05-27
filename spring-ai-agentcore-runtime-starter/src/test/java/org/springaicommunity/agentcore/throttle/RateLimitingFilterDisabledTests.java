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
		properties = { "agentcore.throttle.invocations-limit=0", "agentcore.throttle.ping-limit=0" })
class RateLimitingFilterDisabledTests {

	@LocalServerPort
	private int port;

	private final RestTemplate restTemplate = new RestTemplate();

	@Test
	void shouldNotThrottleWhenLimitsAreZero() {
		String invocationsUrl = "http://localhost:" + this.port + "/invocations";
		String pingUrl = "http://localhost:" + this.port + "/ping";

		// Multiple requests should all succeed when limits are 0
		for (int i = 0; i < 5; i++) {
			ResponseEntity<String> invocationsResponse = this.restTemplate.postForEntity(invocationsUrl, "test" + i,
					String.class);
			assertEquals(HttpStatus.OK, invocationsResponse.getStatusCode());

			ResponseEntity<String> pingResponse = this.restTemplate.getForEntity(pingUrl, String.class);
			assertEquals(HttpStatus.OK, pingResponse.getStatusCode());
		}
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
