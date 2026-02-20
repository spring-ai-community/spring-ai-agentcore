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

import java.time.Duration;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springaicommunity.agentcore.annotation.AgentCoreInvocation;
import org.springaicommunity.agentcore.ping.AgentCoreTaskTracker;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.test.web.reactive.server.FluxExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest(classes = EndToEndWebFluxIntegrationTest.FluxTestApp.class,
		webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Disabled
class EndToEndWebFluxIntegrationTest {

	@LocalServerPort
	private int port;

	@Autowired
	private WebTestClient webTestClient;

	@Autowired
	private AgentCoreTaskTracker agentCoreTaskTracker;

	@SpringBootApplication(scanBasePackages = "org.springaicommunity.agentcore.autoconfigure")
	static class FluxTestApp {

		@Service
		public static class TestFluxAgentService {

			@AgentCoreInvocation
			public Flux<?> handlePrompt(TestRequest request) {
				return switch (request.message()) {
					case "bad_request" -> Flux.error(new ResponseStatusException(HttpStatus.BAD_REQUEST));
					case "conflict" -> Flux.error(new ResponseStatusException(HttpStatus.CONFLICT));
					case "server_error" -> Flux.error(new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR));
					case "pojo_stream" -> Flux.just(new TestResponse(1, "response1"), new TestResponse(2, "response2"),
							new TestResponse(3, "response3"))
						.delayElements(Duration.ofMillis(10));
					default -> Flux.just("Hello", "World", "Stream").delayElements(Duration.ofMillis(10));
				};
			}

		}

	}

	record TestResponse(int id, String message) {
	}

	record TestRequest(String message) {
	}

	@Test
	void shouldStreamFluxResponseAsSSE() {
		var request = new TestRequest("test stream");

		FluxExchangeResult<String> result = webTestClient.post()
			.uri("http://localhost:" + port + "/invocations")
			.contentType(MediaType.APPLICATION_JSON)
			.bodyValue(request)
			.exchange()
			.expectStatus()
			.isOk()
			.expectHeader()
			.contentType(MediaType.TEXT_EVENT_STREAM)
			.returnResult(String.class);

		StepVerifier.create(result.getResponseBody())
			.expectNext("Hello")
			.expectNext("World")
			.expectNext("Stream")
			.verifyComplete();

		assertEquals(0, agentCoreTaskTracker.getCount());
	}

	@Test
	void shouldStreamPojoResponseAsSSE() {
		var request = new TestRequest("pojo_stream");

		FluxExchangeResult<String> result = webTestClient.post()
			.uri("http://localhost:" + port + "/invocations")
			.contentType(MediaType.APPLICATION_JSON)
			.bodyValue(request)
			.exchange()
			.expectStatus()
			.isOk()
			.expectHeader()
			.contentType(MediaType.TEXT_EVENT_STREAM)
			.returnResult(String.class);

		StepVerifier.create(result.getResponseBody()).expectNext("""
				{"id":1,"message":"response1"}""".trim()).expectNext("""
				{"id":2,"message":"response2"}""".trim()).expectNext("""
				{"id":3,"message":"response3"}""".trim()).verifyComplete();

		assertEquals(0, agentCoreTaskTracker.getCount());
	}

	@ParameterizedTest
	@CsvSource({ "bad_request, 400", "conflict, 409", "server_error, 500" })
	void shouldReturnErrorStatusForExceptions(String prompt, int expectedStatus) {
		var request = new TestRequest(prompt);

		webTestClient.post()
			.uri("http://localhost:" + port + "/invocations")
			.contentType(MediaType.APPLICATION_JSON)
			.bodyValue(request)
			.exchange()
			.expectStatus()
			.isEqualTo(expectedStatus);

		assertEquals(0, agentCoreTaskTracker.getCount());
	}

}
