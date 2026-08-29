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

import java.time.Duration;

import org.junit.jupiter.api.BeforeEach;
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

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = EndToEndWebFluxIntegrationTests.FluxTestApp.class,
		webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = "spring.main.web-application-type=reactive")
class EndToEndWebFluxIntegrationTests {

	@LocalServerPort
	private int port;

	private WebTestClient webTestClient;

	@Autowired
	private AgentCoreTaskTracker agentCoreTaskTracker;

	@BeforeEach
	void setUpWebTestClient() {
		this.webTestClient = WebTestClient.bindToServer().baseUrl("http://localhost:" + this.port).build();
	}

	@Test
	void shouldStreamFluxResponseAsSSE() {
		var request = new TestRequest("test stream");

		FluxExchangeResult<String> result = this.webTestClient.post()
			.uri("http://localhost:" + this.port + "/invocations")
			.contentType(MediaType.APPLICATION_JSON)
			.accept(MediaType.TEXT_EVENT_STREAM)
			.bodyValue(request)
			.exchange()
			.expectStatus()
			.isOk()
			.expectHeader()
			.contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM)
			.returnResult(String.class);

		StepVerifier.create(result.getResponseBody())
			.expectNext("Hello")
			.expectNext("World")
			.expectNext("Stream")
			.verifyComplete();

		assertThat(this.agentCoreTaskTracker.getCount()).isEqualTo(0);
	}

	@Test
	void shouldStreamPojoResponseAsSSE() {
		var request = new TestRequest("pojo_stream");

		FluxExchangeResult<String> result = this.webTestClient.post()
			.uri("http://localhost:" + this.port + "/invocations")
			.contentType(MediaType.APPLICATION_JSON)
			.accept(MediaType.TEXT_EVENT_STREAM)
			.bodyValue(request)
			.exchange()
			.expectStatus()
			.isOk()
			.expectHeader()
			.contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM)
			.returnResult(String.class);

		StepVerifier.create(result.getResponseBody()).expectNext("""
				{"id":1,"message":"response1"}""".trim()).expectNext("""
				{"id":2,"message":"response2"}""".trim()).expectNext("""
				{"id":3,"message":"response3"}""".trim()).verifyComplete();

		assertThat(this.agentCoreTaskTracker.getCount()).isEqualTo(0);
	}

	@ParameterizedTest
	@CsvSource({ "bad_request, 400", "conflict, 409", "server_error, 500" })
	void shouldReturnErrorStatusForExceptions(String prompt, int expectedStatus) {
		var request = new TestRequest(prompt);

		this.webTestClient.post()
			.uri("http://localhost:" + this.port + "/invocations")
			.contentType(MediaType.APPLICATION_JSON)
			.accept(MediaType.TEXT_EVENT_STREAM)
			.bodyValue(request)
			.exchange()
			.expectStatus()
			.isEqualTo(expectedStatus);

		assertThat(this.agentCoreTaskTracker.getCount()).isEqualTo(0);
	}

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

}
