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

package org.springaicommunity.agentcore.payments;

import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariables;
import org.springaicommunity.agentcore.payments.client.AgentCorePaymentsClientHttpRequestInterceptor;
import org.springaicommunity.agentcore.payments.core.AgentCorePaymentsTemplate;
import org.springaicommunity.agentcore.payments.core.NetworkPreferences;
import org.springaicommunity.agentcore.payments.core.PaymentContext;
import org.springaicommunity.agentcore.payments.core.PaymentSessionRegistry;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.bedrockagentcore.BedrockAgentCoreClient;
import software.amazon.awssdk.services.bedrockagentcore.model.PaymentSession;

import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pays for a live x402 endpoint through AgentCore Payments, using a {@code RestClient}
 * with {@link AgentCorePaymentsClientHttpRequestInterceptor}. Requires an existing
 * payment manager and a funded payment instrument with signing permission granted
 * (end-user steps that cannot be automated). Creates its payment session through
 * {@link PaymentSessionRegistry} and deletes it.
 * <p>
 * Enable with {@code AGENTCORE_PAYMENTS_IT=true} and set
 * {@code AGENTCORE_PAYMENTS_MANAGER_ARN}, {@code AGENTCORE_PAYMENTS_USER_ID},
 * {@code AGENTCORE_PAYMENTS_INSTRUMENT_ID} and {@code AGENTCORE_PAYMENTS_URL} (a paid
 * x402 endpoint on the instrument's network). Optional: {@code AGENTCORE_PAYMENTS_REGION}
 * (default {@code us-east-1}).
 *
 * @author Andrei Shakirin
 */
@Tag("integration")
@EnabledIfEnvironmentVariables({ @EnabledIfEnvironmentVariable(named = "AGENTCORE_PAYMENTS_IT", matches = "true"),
		@EnabledIfEnvironmentVariable(named = "AGENTCORE_PAYMENTS_MANAGER_ARN", matches = ".+"),
		@EnabledIfEnvironmentVariable(named = "AGENTCORE_PAYMENTS_USER_ID", matches = ".+"),
		@EnabledIfEnvironmentVariable(named = "AGENTCORE_PAYMENTS_INSTRUMENT_ID", matches = ".+"),
		@EnabledIfEnvironmentVariable(named = "AGENTCORE_PAYMENTS_URL", matches = ".+") })
class AgentCorePaymentsIT {

	@Test
	void paysForX402EndpointWithinSessionBudget() {
		String region = System.getenv().getOrDefault("AGENTCORE_PAYMENTS_REGION", "us-east-1");
		String userId = System.getenv("AGENTCORE_PAYMENTS_USER_ID");
		String instrumentId = System.getenv("AGENTCORE_PAYMENTS_INSTRUMENT_ID");
		try (BedrockAgentCoreClient client = BedrockAgentCoreClient.builder().region(Region.of(region)).build()) {
			AgentCorePaymentsTemplate payments = new AgentCorePaymentsTemplate(client,
					System.getenv("AGENTCORE_PAYMENTS_MANAGER_ARN"), "spring-ai-agentcore-it",
					NetworkPreferences.DEFAULT, null);
			PaymentSessionRegistry sessions = new PaymentSessionRegistry(payments, "1.00", Duration.ofMinutes(15), 10);
			String runtimeSessionId = "spring-ai-agentcore-it-" + UUID.randomUUID();
			String paymentSessionId = sessions.getOrCreate(userId, runtimeSessionId);
			assertThat(sessions.getOrCreate(userId, runtimeSessionId)).isEqualTo(paymentSessionId);
			try {
				// Main path: a RestClient with the payments interceptor pays for 402
				// responses
				PaymentContext context = new PaymentContext(userId, instrumentId, paymentSessionId);
				var paymentsInterceptor = new AgentCorePaymentsClientHttpRequestInterceptor(payments, () -> context,
						Duration.ofSeconds(3));
				RestClient paidClient = RestClient.builder().requestInterceptor(paymentsInterceptor).build();

				String result = paidClient.get()
					.uri(System.getenv("AGENTCORE_PAYMENTS_URL"))
					.retrieve()
					.toEntity(String.class)
					.getStatusCode()
					.toString();

				assertThat(result).startsWith("200");
				PaymentSession afterPayment = payments.getPaymentSession(userId, paymentSessionId);
				assertThat(afterPayment.availableLimits().availableSpendAmount().value()).isNotEqualTo("1.00");
			}
			finally {
				payments.deletePaymentSession(userId, paymentSessionId);
			}
		}
	}

}
