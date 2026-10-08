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
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.bedrockagentcore.BedrockAgentCoreClient;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
class PaymentsConfiguration {

	/**
	 * AgentCore client in the region of the payment manager; replaces the auto-configured
	 * client that uses the default region.
	 */
	@Bean(destroyMethod = "close")
	BedrockAgentCoreClient bedrockAgentCoreClient(@Value("${app.payments.region}") String region) {
		return BedrockAgentCoreClient.builder().region(Region.of(region)).build();
	}

	/**
	 * Way 1: a RestClient with the payments interceptor. Every call through it pays for
	 * HTTP 402 responses automatically.
	 */
	@Bean
	RestClient paidRestClient(AgentCorePaymentsClientHttpRequestInterceptor paymentsInterceptor) {
		return RestClient.builder().requestInterceptor(paymentsInterceptor).build();
	}

}
