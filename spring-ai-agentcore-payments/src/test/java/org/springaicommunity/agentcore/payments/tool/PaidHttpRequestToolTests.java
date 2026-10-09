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

package org.springaicommunity.agentcore.payments.tool;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.springaicommunity.agentcore.payments.client.AgentCorePaymentsClientHttpRequestInterceptor;
import org.springaicommunity.agentcore.payments.core.PaymentContext;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.ExpectedCount.never;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Tests for {@link PaidHttpRequestTool}.
 *
 * @author Andrei Shakirin
 */
class PaidHttpRequestToolTests {

	private static final String URL = "https://api.example.com/data";

	private final AtomicReference<Object> paymentContextAttribute = new AtomicReference<>();

	private final RestClient.Builder builder = RestClient.builder().requestInterceptor((request, body, execution) -> {
		this.paymentContextAttribute
			.set(request.getAttributes().get(AgentCorePaymentsClientHttpRequestInterceptor.PAYMENT_CONTEXT_ATTRIBUTE));
		return execution.execute(request, body);
	});

	private final MockRestServiceServer server = MockRestServiceServer.bindTo(this.builder).build();

	private final PaymentContextResolver resolver = new DefaultPaymentContextResolver(
			new PaymentContext("user-1", "instrument-1", "session-1"));

	@Test
	void passesPaymentContextOfToolCallToPaymentsInterceptor() {
		this.server.expect(requestTo(URL)).andRespond(withSuccess("{\"ok\":true}", MediaType.APPLICATION_JSON));

		String result = this.tool(100)
			.execute(new PaidHttpRequestTool.Request(URL, null, null, null),
					new ToolContext(Map.of(PaymentContext.PAYMENT_SESSION_ID_KEY, "session-2")));

		assertThat(result).isEqualTo(
				"{\"statusCode\":200,\"headers\":{\"Content-Type\":\"application/json\"},\"body\":{\"ok\":true}}");
		assertThat(this.paymentContextAttribute).hasValue(new PaymentContext("user-1", "instrument-1", "session-2"));
	}

	@Test
	void refusesHostsOutsideTheAllowlistWithoutSendingARequest() {
		this.server.expect(never(), requestTo("https://evil.example.org/pay"));

		String result = this.tool(100)
			.execute(new PaidHttpRequestTool.Request("https://evil.example.org/pay", null, null, null), null);

		assertThat(result).contains("\"statusCode\":0").contains("Host not allowed: evil.example.org");
		this.server.verify();
	}

	@Test
	void refusesMethodsOtherThanStandardHttpMethods() {
		String result = this.tool(100).execute(new PaidHttpRequestTool.Request(URL, "TRACE", null, null), null);

		assertThat(result).contains("HTTP method not allowed: TRACE");
	}

	@Test
	void returnsRedirectWithoutFollowingItAndHidesCookies() {
		this.server.expect(requestTo(URL))
			.andRespond(withStatus(HttpStatus.FOUND).header(HttpHeaders.LOCATION, "https://evil.example.org/pay")
				.header(HttpHeaders.SET_COOKIE, "session=secret")
				.header("PAYMENT-RESPONSE", "settled"));

		String result = this.tool(100).execute(new PaidHttpRequestTool.Request(URL, null, null, null), null);

		assertThat(result).startsWith("{\"statusCode\":302")
			.contains("https://evil.example.org/pay")
			.contains("PAYMENT-RESPONSE")
			.doesNotContain("session=secret");
		this.server.verify();
	}

	@Test
	void sendsMethodHeadersAndBodyAndTruncatesLongResponses() {
		this.server.expect(requestTo(URL))
			.andExpect(method(HttpMethod.POST))
			.andExpect(header("Accept", "text/plain"))
			.andExpect(header("Content-Type", "application/json"))
			.andRespond(withSuccess("x".repeat(50), MediaType.TEXT_PLAIN));

		String result = this.tool(10)
			.execute(new PaidHttpRequestTool.Request(URL, "post", Map.of("Accept", "text/plain"), "{\"q\":1}"), null);

		assertThat(result).startsWith("{\"statusCode\":200").contains("xxxxxxxxxx... [truncated]");
		this.server.verify();
	}

	private PaidHttpRequestTool tool(int maxResponseLength) {
		return new PaidHttpRequestTool(this.builder.build(), this.resolver, AllowedHosts.of(List.of("api.example.com")),
				maxResponseLength);
	}

}
