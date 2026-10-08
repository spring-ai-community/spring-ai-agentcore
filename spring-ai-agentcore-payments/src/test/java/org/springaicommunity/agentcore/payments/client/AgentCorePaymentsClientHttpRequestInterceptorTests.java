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

package org.springaicommunity.agentcore.payments.client;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springaicommunity.agentcore.payments.core.AgentCorePaymentsTemplate;
import org.springaicommunity.agentcore.payments.core.PaymentContext;
import org.springaicommunity.agentcore.payments.core.PaymentException;
import org.springaicommunity.agentcore.payments.core.PaymentHeader;
import org.springaicommunity.agentcore.payments.core.PaymentRequired;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Tests for {@link AgentCorePaymentsClientHttpRequestInterceptor}.
 *
 * @author Andrei Shakirin
 */
class AgentCorePaymentsClientHttpRequestInterceptorTests {

	private static final String URL = "https://api.example.com/market-recap";

	private static final String REQUIREMENTS = "{\"x402Version\":1,\"accepts\":[]}";

	private static final PaymentContext DEFAULTS = new PaymentContext("user-1", "instrument-1", "session-1");

	private final AgentCorePaymentsTemplate payments = mock(AgentCorePaymentsTemplate.class);

	private final RestClient.Builder builder = RestClient.builder()
		.requestInterceptor(
				new AgentCorePaymentsClientHttpRequestInterceptor(this.payments, () -> DEFAULTS, Duration.ZERO));

	private final MockRestServiceServer server = MockRestServiceServer.bindTo(this.builder).build();

	@Test
	void paysForPaymentRequiredAndRetriesWithPaymentHeader() {
		this.server.expect(once(), requestTo(URL))
			.andExpect(headerDoesNotExist("X-PAYMENT"))
			.andRespond(
					withStatus(HttpStatus.PAYMENT_REQUIRED).contentType(MediaType.APPLICATION_JSON).body(REQUIREMENTS));
		this.server.expect(once(), requestTo(URL))
			.andExpect(header("X-PAYMENT", "proof"))
			.andRespond(withSuccess("paid content", MediaType.TEXT_PLAIN));
		given(this.payments.generatePaymentHeader(any(), any())).willReturn(new PaymentHeader("X-PAYMENT", "proof"));

		String body = this.builder.build().get().uri(URL).retrieve().body(String.class);

		assertThat(body).isEqualTo("paid content");
		this.server.verify();
		then(this.payments).should()
			.generatePaymentHeader(eq(DEFAULTS),
					argThat((PaymentRequired required) -> REQUIREMENTS.equals(required.body())));
	}

	@Test
	void usesPaymentContextFromRequestAttribute() {
		PaymentContext perRequest = new PaymentContext("user-2", "instrument-2", "session-2");
		this.server.expect(once(), requestTo(URL)).andRespond(withStatus(HttpStatus.PAYMENT_REQUIRED).body("{}"));
		this.server.expect(once(), requestTo(URL)).andRespond(withSuccess());
		given(this.payments.generatePaymentHeader(any(), any())).willReturn(new PaymentHeader("X-PAYMENT", "proof"));

		this.builder.build()
			.get()
			.uri(URL)
			.attribute(AgentCorePaymentsClientHttpRequestInterceptor.PAYMENT_CONTEXT_ATTRIBUTE, perRequest)
			.retrieve()
			.toBodilessEntity();

		then(this.payments).should().generatePaymentHeader(eq(perRequest), any());
	}

	@Test
	void passesThroughResponsesThatDoNotRequirePayment() {
		this.server.expect(once(), requestTo(URL)).andRespond(withSuccess("free content", MediaType.TEXT_PLAIN));

		String body = this.builder.build().get().uri(URL).retrieve().body(String.class);

		assertThat(body).isEqualTo("free content");
		then(this.payments).shouldHaveNoInteractions();
	}

	@Test
	void failsWithoutPayingTwiceWhenServerRejectsPayment() {
		this.server.expect(once(), requestTo(URL)).andRespond(withStatus(HttpStatus.PAYMENT_REQUIRED).body("{}"));
		this.server.expect(once(), requestTo(URL))
			.andRespond(withStatus(HttpStatus.PAYMENT_REQUIRED).body("{\"error\":\"invalid_payload\"}"));
		given(this.payments.generatePaymentHeader(any(), any())).willReturn(new PaymentHeader("X-PAYMENT", "proof"));

		assertThatExceptionOfType(PaymentException.class)
			.isThrownBy(() -> this.builder.build().get().uri(URL).retrieve().toBodilessEntity())
			.withMessageContaining("rejected")
			.withMessageContaining("invalid_payload");
		then(this.payments).should().generatePaymentHeader(any(), any());
	}

}
