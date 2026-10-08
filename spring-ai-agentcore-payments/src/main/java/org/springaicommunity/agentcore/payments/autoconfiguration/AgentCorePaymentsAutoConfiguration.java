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

package org.springaicommunity.agentcore.payments.autoconfiguration;

import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springaicommunity.agentcore.payments.client.AgentCorePaymentsClientHttpRequestInterceptor;
import org.springaicommunity.agentcore.payments.core.AgentCorePaymentsTemplate;
import org.springaicommunity.agentcore.payments.core.PaymentContext;
import org.springaicommunity.agentcore.payments.core.PaymentSessionRegistry;
import org.springaicommunity.agentcore.payments.tool.AgentCorePaymentsToolCallbacks;
import org.springaicommunity.agentcore.payments.tool.AgentCorePaymentsTools;
import org.springaicommunity.agentcore.payments.tool.AllowedHosts;
import org.springaicommunity.agentcore.payments.tool.DefaultPaymentContextResolver;
import org.springaicommunity.agentcore.payments.tool.PaidHttpRequestTool;
import org.springaicommunity.agentcore.payments.tool.PaymentContextResolver;
import org.springaicommunity.agentcore.payments.tool.PaymentQueryTools;
import software.amazon.awssdk.services.bedrockagentcore.BedrockAgentCoreClient;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.boot.http.client.HttpRedirects;
import org.springframework.boot.http.client.InetAddressFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.web.client.RestClient;

/**
 * Auto-configuration for AgentCore Payments, active when
 * {@code agentcore.payments.payment-manager-arn} is set.
 *
 * @author Andrei Shakirin
 */
@AutoConfiguration
@ConditionalOnClass(BedrockAgentCoreClient.class)
@ConditionalOnProperty(prefix = AgentCorePaymentsProperties.PREFIX, name = "payment-manager-arn")
@EnableConfigurationProperties(AgentCorePaymentsProperties.class)
public class AgentCorePaymentsAutoConfiguration {

	private static final Logger logger = LoggerFactory.getLogger(AgentCorePaymentsAutoConfiguration.class);

	private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(30);

	@Bean(destroyMethod = "close")
	@ConditionalOnMissingBean
	BedrockAgentCoreClient bedrockAgentCoreClient() {
		return BedrockAgentCoreClient.create();
	}

	@Bean
	@ConditionalOnMissingBean
	AgentCorePaymentsTemplate agentCorePaymentsTemplate(BedrockAgentCoreClient client,
			AgentCorePaymentsProperties properties) {
		return new AgentCorePaymentsTemplate(client, properties.paymentManagerArn(), properties.agentName(),
				properties.networkPreferences(), properties.permit2AllowanceLimit());
	}

	@Bean
	@ConditionalOnMissingBean
	PaymentSessionRegistry paymentSessionRegistry(AgentCorePaymentsTemplate payments,
			AgentCorePaymentsProperties properties) {
		AgentCorePaymentsProperties.Session session = properties.session();
		return new PaymentSessionRegistry(payments, session.maxSpend(), session.expiry(), session.maxEntries());
	}

	@Bean
	@ConditionalOnMissingBean
	PaymentContextResolver paymentContextResolver(AgentCorePaymentsProperties properties) {
		if (properties.userId() != null || properties.paymentSessionId() != null) {
			logger.warn("agentcore.payments.user-id / payment-session-id are set: every payment without a user "
					+ "or session in the tool context or request attribute uses them. "
					+ "Intended for local runs and tests only.");
		}
		return new DefaultPaymentContextResolver(new PaymentContext(properties.userId(),
				properties.paymentInstrumentId(), properties.paymentSessionId()));
	}

	@Bean
	@ConditionalOnMissingBean
	AgentCorePaymentsClientHttpRequestInterceptor agentCorePaymentsClientHttpRequestInterceptor(
			AgentCorePaymentsTemplate payments, PaymentContextResolver contextResolver,
			AgentCorePaymentsProperties properties) {
		return new AgentCorePaymentsClientHttpRequestInterceptor(payments, () -> contextResolver.resolve(null),
				properties.postPaymentDelay());
	}

	@Bean
	@ConditionalOnMissingBean
	AgentCorePaymentsToolCallbacks agentCorePaymentsToolCallbacks(AgentCorePaymentsTemplate payments,
			PaymentContextResolver contextResolver, AgentCorePaymentsProperties properties) {
		return new AgentCorePaymentsToolCallbacks(payments, contextResolver, properties.postPaymentDelay());
	}

	@Bean
	@ConditionalOnMissingBean
	AgentCorePaymentsTools agentCorePaymentsTools(AgentCorePaymentsTemplate payments,
			PaymentContextResolver contextResolver, AgentCorePaymentsClientHttpRequestInterceptor paymentsInterceptor,
			AgentCorePaymentsProperties properties) {
		PaidHttpRequestTool paidHttpRequestTool = null;
		AgentCorePaymentsProperties.PaidHttpTool paidHttpTool = properties.paidHttpTool();
		if (paidHttpTool.enabled()) {
			if (paidHttpTool.allowedHosts().isEmpty()) {
				throw new IllegalStateException("agentcore.payments.paid-http-tool.enabled=true requires "
						+ "agentcore.payments.paid-http-tool.allowed-hosts: the hosts the model may call and pay");
			}
			paidHttpRequestTool = new PaidHttpRequestTool(paidHttpToolRestClient(paymentsInterceptor), contextResolver,
					AllowedHosts.of(paidHttpTool.allowedHosts()), paidHttpTool.maxResponseLength());
			logger.info("paidHttpRequest tool enabled for hosts {}", paidHttpTool.allowedHosts());
		}
		return new AgentCorePaymentsTools(new PaymentQueryTools(payments, contextResolver), paidHttpRequestTool);
	}

	/**
	 * Client of the {@code paidHttpRequest} tool. The model chooses the URLs, so only
	 * external addresses may be reached (no loopback, link-local such as the container
	 * credentials endpoint, or private networks). Redirects are not followed: the JDK
	 * client does not apply the address filter to redirect targets, and a redirect must
	 * not lead to paying a host outside the allowlist. The model sees the {@code 3xx}
	 * response and its {@code Location} header instead.
	 * @param paymentsInterceptor pays for {@code 402} responses
	 * @return the filtered client
	 */
	static RestClient paidHttpToolRestClient(ClientHttpRequestInterceptor paymentsInterceptor) {
		ClientHttpRequestFactory requestFactory = ClientHttpRequestFactoryBuilder.jdk()
			.build(HttpClientSettings.defaults()
				.withTimeouts(HTTP_TIMEOUT, HTTP_TIMEOUT)
				.withRedirects(HttpRedirects.DONT_FOLLOW)
				.withInetAddressFilter(InetAddressFilter.externalAddresses()));
		return RestClient.builder().requestFactory(requestFactory).requestInterceptor(paymentsInterceptor).build();
	}

}
