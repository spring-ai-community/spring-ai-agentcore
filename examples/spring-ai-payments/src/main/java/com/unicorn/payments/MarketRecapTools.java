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

import org.springaicommunity.agentcore.payments.client.AgentCorePaymentsClientHttpRequestInterceptor;
import org.springaicommunity.agentcore.payments.tool.PaymentContextResolver;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Way 1, HTTP client interceptor: a domain tool for a paid API. The tool contains no
 * payment code; the interceptor of the RestClient pays for HTTP 402 responses.
 */
@Component
class MarketRecapTools {

	private final RestClient paidRestClient;

	private final PaymentContextResolver paymentContextResolver;

	private final String marketRecapUrl;

	MarketRecapTools(RestClient paidRestClient, PaymentContextResolver paymentContextResolver,
			@Value("${app.market-recap-url}") String marketRecapUrl) {
		this.paidRestClient = paidRestClient;
		this.paymentContextResolver = paymentContextResolver;
		this.marketRecapUrl = marketRecapUrl;
	}

	@Tool(description = "Get a recap of recent prediction-market activity. This is a paid data source.")
	String getMarketRecap(ToolContext toolContext) {
		return this.paidRestClient.get()
			.uri(this.marketRecapUrl)
			// who pays for this call: user and payment session from the tool context
			.attribute(AgentCorePaymentsClientHttpRequestInterceptor.PAYMENT_CONTEXT_ATTRIBUTE,
					this.paymentContextResolver.resolve(toolContext))
			.retrieve()
			.body(String.class);
	}

}
