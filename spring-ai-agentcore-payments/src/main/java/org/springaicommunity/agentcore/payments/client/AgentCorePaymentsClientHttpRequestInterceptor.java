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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springaicommunity.agentcore.payments.core.AgentCorePaymentsTemplate;
import org.springaicommunity.agentcore.payments.core.PaymentContext;
import org.springaicommunity.agentcore.payments.core.PaymentException;
import org.springaicommunity.agentcore.payments.core.PaymentHeader;
import org.springaicommunity.agentcore.payments.core.PaymentRequired;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.util.Assert;
import org.springframework.util.StreamUtils;

/**
 * Pays for {@code 402 Payment Required} responses of a Spring {@code RestClient} or
 * {@code RestTemplate}: the payment is signed through AgentCore Payments and the request
 * is sent again with the payment header. A request is paid at most once; if the server
 * answers {@code 402} again, it rejected the payment and a {@link PaymentException} is
 * thrown instead of paying twice. <pre class="code">
 * RestClient paidApi = RestClient.builder()
 *     .baseUrl("https://api.example.com")
 *     .requestInterceptor(paymentsInterceptor)
 *     .build();
 * </pre>
 * <p>
 * The user, payment instrument and payment session are taken from the request attribute
 * {@link #PAYMENT_CONTEXT_ATTRIBUTE} if present, otherwise from the configured defaults.
 *
 * @author Andrei Shakirin
 */
public class AgentCorePaymentsClientHttpRequestInterceptor implements ClientHttpRequestInterceptor {

	/** Request attribute holding the {@link PaymentContext} of a request. */
	public static final String PAYMENT_CONTEXT_ATTRIBUTE = PaymentContext.class.getName();

	private static final Logger logger = LoggerFactory.getLogger(AgentCorePaymentsClientHttpRequestInterceptor.class);

	private final AgentCorePaymentsTemplate payments;

	private final Supplier<PaymentContext> defaultContext;

	private final Duration postPaymentDelay;

	/**
	 * Creates the interceptor.
	 * @param payments the payments template used to sign payments
	 * @param defaultContext supplies the payment context of requests without
	 * {@link #PAYMENT_CONTEXT_ATTRIBUTE}
	 * @param postPaymentDelay wait before sending the paid request, so that the signed
	 * authorization is valid on chain. If the thread is interrupted during the wait, the
	 * payment is already signed and counted against the session budget, but the paid
	 * request is not sent
	 */
	public AgentCorePaymentsClientHttpRequestInterceptor(AgentCorePaymentsTemplate payments,
			Supplier<PaymentContext> defaultContext, Duration postPaymentDelay) {
		Assert.notNull(payments, "payments must not be null");
		Assert.notNull(defaultContext, "defaultContext must not be null");
		Assert.notNull(postPaymentDelay, "postPaymentDelay must not be null");
		this.payments = payments;
		this.defaultContext = defaultContext;
		this.postPaymentDelay = postPaymentDelay;
	}

	@Override
	public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
			throws IOException {
		ClientHttpResponse response = execution.execute(request, body);
		if (response.getStatusCode().value() != PaymentRequired.PAYMENT_REQUIRED_STATUS) {
			return response;
		}
		PaymentRequired paymentRequired = read(response);
		logger.info("{} {}://{}{} requires payment, paying through AgentCore Payments", request.getMethod(),
				request.getURI().getScheme(), request.getURI().getAuthority(), request.getURI().getPath());
		PaymentHeader header = this.payments.generatePaymentHeader(this.paymentContext(request), paymentRequired);
		sleep(this.postPaymentDelay);

		request.getHeaders().set(header.name(), header.value());
		ClientHttpResponse paidResponse = execution.execute(request, body);
		if (paidResponse.getStatusCode().value() == PaymentRequired.PAYMENT_REQUIRED_STATUS) {
			PaymentRequired rejected = read(paidResponse);
			throw new PaymentException("Payment for " + request.getURI().getHost() + request.getURI().getPath()
					+ " was rejected by the server: " + rejected.body());
		}
		return paidResponse;
	}

	private PaymentContext paymentContext(HttpRequest request) {
		return (request.getAttributes().get(PAYMENT_CONTEXT_ATTRIBUTE) instanceof PaymentContext context) ? context
				: this.defaultContext.get();
	}

	private static PaymentRequired read(ClientHttpResponse response) throws IOException {
		try (response) {
			HttpHeaders headers = new HttpHeaders();
			headers.putAll(response.getHeaders());
			String body = StreamUtils.copyToString(response.getBody(), StandardCharsets.UTF_8);
			return new PaymentRequired(PaymentRequired.PAYMENT_REQUIRED_STATUS, headers, body);
		}
	}

	private static void sleep(Duration delay) {
		if (delay.isZero() || delay.isNegative()) {
			return;
		}
		try {
			Thread.sleep(delay.toMillis());
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new PaymentException("Interrupted while waiting to send the paid request", ex);
		}
	}

}
