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


package com.unicorn.payments;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import org.springaicommunity.agentcore.payments.core.AgentCorePaymentsTemplate;
import org.springaicommunity.agentcore.payments.core.PaymentHeader;
import org.springaicommunity.agentcore.payments.core.PaymentRequired;
import org.springaicommunity.agentcore.payments.tool.PaymentContextResolver;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

/**
 * Way 3, own integration: a domain tool on a non-Spring HTTP client (JDK
 * {@link HttpClient}). On HTTP 402 it asks AgentCore Payments for the payment header and
 * sends the request again.
 */
@Component
class FortuneTools {

	/** Lets the signed authorization become valid on chain before it is used. */
	private static final Duration POST_PAYMENT_DELAY = Duration.ofSeconds(3);

	private final HttpClient httpClient = HttpClient.newHttpClient();

	private final AgentCorePaymentsTemplate payments;

	private final PaymentContextResolver paymentContextResolver;

	private final String fortuneUrl;

	FortuneTools(AgentCorePaymentsTemplate payments, PaymentContextResolver paymentContextResolver,
			@Value("${app.fortune-url}") String fortuneUrl) {
		this.payments = payments;
		this.paymentContextResolver = paymentContextResolver;
		this.fortuneUrl = fortuneUrl;
	}

	@Tool(description = "Get a fortune reading. This is a paid service.")
	String getFortune(ToolContext toolContext) throws IOException, InterruptedException {
		HttpResponse<String> response = this.send(null);
		if (response.statusCode() == PaymentRequired.PAYMENT_REQUIRED_STATUS) {
			HttpHeaders headers = new HttpHeaders();
			response.headers().map().forEach(headers::addAll);
			PaymentHeader paymentHeader = this.payments.generatePaymentHeader(
					this.paymentContextResolver.resolve(toolContext),
					new PaymentRequired(response.statusCode(), headers, response.body()));
			Thread.sleep(POST_PAYMENT_DELAY.toMillis());
			response = this.send(paymentHeader);
		}
		return response.body();
	}

	private HttpResponse<String> send(PaymentHeader paymentHeader) throws IOException, InterruptedException {
		HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(this.fortuneUrl)).GET();
		if (paymentHeader != null) {
			request.header(paymentHeader.name(), paymentHeader.value());
		}
		return this.httpClient.send(request.build(), HttpResponse.BodyHandlers.ofString());
	}

}
