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
package com.unicorn.agents;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springaicommunity.agentcore.throttle.ReactiveRateLimitingWebFilter;

import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.util.ClassUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Verifies that with {@code spring-boot-starter-web} excluded the whole AgentCore
 * runtime boots on a reactive (Netty) server and the reactive throttle filter applies the
 * per-client rate limits. The {@code /ping} endpoint is used so the assertions do not
 * depend on a live model call.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class ReactiveThrottlingTests {

	private static final String X_FORWARDED_FOR = "X-Forwarded-For";

	private static final String TEST_CLIENT_IP = "192.0.2.10";

	private static final int PING_LIMIT = 3;

	@LocalServerPort
	private int port;

	private WebTestClient webTestClient;

	@BeforeEach
	void setUp() {
		this.webTestClient = WebTestClient.bindToServer().baseUrl("http://localhost:" + this.port).build();
	}

	@Test
	void runsOnReactiveServerWithoutServletStack() {
		assertThat(ClassUtils.isPresent("org.springframework.web.servlet.DispatcherServlet", null)).isFalse();
	}

	@Test
	void wiresReactiveFilterAndNotServletFilter(ApplicationContext context) {
		assertThat(context.getBean(ReactiveRateLimitingWebFilter.class)).isNotNull();
		assertThatExceptionOfType(NoSuchBeanDefinitionException.class)
			.isThrownBy(() -> context.getBean("rateLimitingFilter"));
	}

	@Test
	void throttlesPingEndpointOnReactiveStack() {
		for (int i = 0; i < PING_LIMIT; i++) {
			this.getPing(TEST_CLIENT_IP).expectStatus().isOk();
		}
		this.getPing(TEST_CLIENT_IP).expectStatus().isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
	}

	private WebTestClient.ResponseSpec getPing(String clientId) {
		return this.webTestClient.get().uri("/ping").header(X_FORWARDED_FOR, clientId).exchange();
	}

}
