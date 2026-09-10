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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springaicommunity.agentcore.annotation.AgentCoreInvocation;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.test.web.reactive.server.WebTestClient;

@SpringBootTest(classes = ReactiveRateLimitingWebFilterTests.ReactiveTestApp.class,
		webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = { "spring.main.web-application-type=reactive", "agentcore.throttle.invocations-limit=2",
				"agentcore.throttle.ping-limit=3" })
class ReactiveRateLimitingWebFilterTests {

	private static final String X_FORWARDED_FOR = "X-Forwarded-For";

	@LocalServerPort
	private int port;

	private WebTestClient webTestClient;

	@BeforeEach
	void setUp() {
		this.webTestClient = WebTestClient.bindToServer().baseUrl("http://localhost:" + this.port).build();
	}

	@Test
	void shouldThrottleInvocationsEndpoint() {
		for (int i = 0; i < 2; i++) {
			this.postInvocation("192.0.2.1").expectStatus().isOk();
		}

		this.postInvocation("192.0.2.1")
			.expectStatus()
			.isEqualTo(429)
			.expectHeader()
			.contentType(MediaType.APPLICATION_JSON)
			.expectBody()
			.json("""
					{"error":"Rate limit exceeded"}""");
	}

	@Test
	void shouldThrottlePingEndpoint() {
		for (int i = 0; i < 3; i++) {
			this.getPing("192.0.2.2").expectStatus().isOk();
		}

		this.getPing("192.0.2.2").expectStatus().isEqualTo(429);
	}

	@Test
	void shouldUseXForwardedForHeaderForClientIdentification() {
		for (int i = 0; i < 2; i++) {
			this.postInvocation("192.0.2.3").expectStatus().isOk();
		}
		this.postInvocation("192.0.2.3").expectStatus().isEqualTo(429);

		this.postInvocation("192.0.2.4").expectStatus().isOk();
	}

	private WebTestClient.ResponseSpec postInvocation(String clientId) {
		return this.webTestClient.post()
			.uri("/invocations")
			.header(X_FORWARDED_FOR, clientId)
			.contentType(MediaType.TEXT_PLAIN)
			.bodyValue("test")
			.exchange();
	}

	private WebTestClient.ResponseSpec getPing(String clientId) {
		return this.webTestClient.get().uri("/ping").header(X_FORWARDED_FOR, clientId).exchange();
	}

	@SpringBootApplication(scanBasePackages = "org.springaicommunity.agentcore.autoconfigure")
	static class ReactiveTestApp {

		@Service
		public static class TestAgentService {

			@AgentCoreInvocation
			public String handle(String request) {
				return "Message: " + request;
			}

		}

	}

}
