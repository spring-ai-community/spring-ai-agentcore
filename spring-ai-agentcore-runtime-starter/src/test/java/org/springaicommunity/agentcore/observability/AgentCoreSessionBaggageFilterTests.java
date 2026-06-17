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

package org.springaicommunity.agentcore.observability;

import java.util.concurrent.atomic.AtomicReference;

import io.opentelemetry.api.baggage.Baggage;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;

/**
 * Tests for {@link AgentCoreSessionBaggageFilter}.
 *
 * @author Vaquar Khan
 */
class AgentCoreSessionBaggageFilterTests {

	private final AgentCoreSessionBaggageFilter filter = new AgentCoreSessionBaggageFilter();

	@Test
	@DisplayName("Should set session.id in baggage when session header is present")
	void shouldSetBaggageWhenHeaderPresent() throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest();
		request.addHeader("X-Amzn-Bedrock-AgentCore-Runtime-Session-Id", "test-session-123");
		MockHttpServletResponse response = new MockHttpServletResponse();

		AtomicReference<String> capturedBaggage = new AtomicReference<>();
		FilterChain chain = (req, res) -> capturedBaggage
			.set(Baggage.current().getEntryValue(AgentCoreSessionBaggageFilter.BAGGAGE_KEY));

		this.filter.doFilter(request, response, chain);

		assertThat(capturedBaggage.get()).isEqualTo("test-session-123");
	}

	@Test
	@DisplayName("Should not set baggage when session header is missing")
	void shouldNotSetBaggageWhenHeaderMissing() throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest();
		MockHttpServletResponse response = new MockHttpServletResponse();

		AtomicReference<String> capturedBaggage = new AtomicReference<>();
		FilterChain chain = (req, res) -> capturedBaggage
			.set(Baggage.current().getEntryValue(AgentCoreSessionBaggageFilter.BAGGAGE_KEY));

		this.filter.doFilter(request, response, chain);

		assertThat(capturedBaggage.get()).isNull();
	}

	@Test
	@DisplayName("Should not set baggage when session header is blank")
	void shouldNotSetBaggageWhenHeaderBlank() throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest();
		request.addHeader("X-Amzn-Bedrock-AgentCore-Runtime-Session-Id", "   ");
		MockHttpServletResponse response = new MockHttpServletResponse();

		AtomicReference<String> capturedBaggage = new AtomicReference<>();
		FilterChain chain = (req, res) -> capturedBaggage
			.set(Baggage.current().getEntryValue(AgentCoreSessionBaggageFilter.BAGGAGE_KEY));

		this.filter.doFilter(request, response, chain);

		assertThat(capturedBaggage.get()).isNull();
	}

	@Test
	@DisplayName("Baggage scope should be closed after filter chain completes")
	void shouldCloseBaggageScopeAfterChain() throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest();
		request.addHeader("X-Amzn-Bedrock-AgentCore-Runtime-Session-Id", "scoped-session");
		MockHttpServletResponse response = new MockHttpServletResponse();
		FilterChain chain = mock(FilterChain.class);

		this.filter.doFilter(request, response, chain);

		then(chain).should().doFilter(request, response);
		// After filter completes, baggage should not leak
		assertThat(Baggage.current().getEntryValue(AgentCoreSessionBaggageFilter.BAGGAGE_KEY)).isNull();
	}

	@Test
	@DisplayName("Should preserve existing baggage entries")
	void shouldPreserveExistingBaggage() throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest();
		request.addHeader("X-Amzn-Bedrock-AgentCore-Runtime-Session-Id", "my-session");
		MockHttpServletResponse response = new MockHttpServletResponse();

		// Set up pre-existing baggage
		Baggage existingBaggage = Baggage.builder().put("existing.key", "existing-value").build();

		AtomicReference<String> capturedExisting = new AtomicReference<>();
		AtomicReference<String> capturedSession = new AtomicReference<>();
		FilterChain chain = (req, res) -> {
			capturedExisting.set(Baggage.current().getEntryValue("existing.key"));
			capturedSession.set(Baggage.current().getEntryValue(AgentCoreSessionBaggageFilter.BAGGAGE_KEY));
		};

		try (var ignored = existingBaggage.makeCurrent()) {
			this.filter.doFilter(request, response, chain);
		}

		assertThat(capturedSession.get()).isEqualTo("my-session");
		assertThat(capturedExisting.get()).isEqualTo("existing-value");
	}

}
