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
import java.util.List;

import org.jspecify.annotations.Nullable;
import org.springaicommunity.agentcore.payments.core.NetworkPreferences;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for AgentCore Payments.
 *
 * @param paymentManagerArn the payment manager ARN; setting it enables the module
 * @param userId fallback user for calls without one in the tool context or request
 * attribute, intended for local runs and tests: in production every such call would pay
 * from this user's wallet
 * @param paymentInstrumentId default payment instrument (wallet)
 * @param paymentSessionId fallback payment session, intended for local runs and tests;
 * applications create sessions per conversation, see {@code PaymentSessionRegistry}
 * @param agentName agent name sent with data plane calls
 * @param networkPreferences network identifiers used to choose between payment options,
 * most preferred first
 * @param permit2AllowanceLimit the Permit2 allowance for the x402 {@code upto} scheme, in
 * the asset's smallest unit
 * @param postPaymentDelay wait before retrying a paid request
 * @param paidHttpTool settings of the paid HTTP request tool
 * @param session defaults of payment sessions created by {@code PaymentSessionRegistry}
 * @author Andrei Shakirin
 */
@ConfigurationProperties(prefix = AgentCorePaymentsProperties.PREFIX)
public record AgentCorePaymentsProperties(@Nullable String paymentManagerArn, @Nullable String userId,
		@Nullable String paymentInstrumentId, @Nullable String paymentSessionId, @Nullable String agentName,
		List<String> networkPreferences, @Nullable String permit2AllowanceLimit, Duration postPaymentDelay,
		PaidHttpTool paidHttpTool, Session session) {

	/** Property prefix. */
	public static final String PREFIX = "agentcore.payments";

	/** Default wait before retrying a paid request. */
	public static final Duration DEFAULT_POST_PAYMENT_DELAY = Duration.ofSeconds(3);

	public AgentCorePaymentsProperties {
		if (networkPreferences == null || networkPreferences.isEmpty()) {
			networkPreferences = NetworkPreferences.DEFAULT;
		}
		if (postPaymentDelay == null) {
			postPaymentDelay = DEFAULT_POST_PAYMENT_DELAY;
		}
		if (paidHttpTool == null) {
			paidHttpTool = new PaidHttpTool(null, null, null);
		}
		if (session == null) {
			session = new Session(null, null, null);
		}
	}

	/**
	 * Settings of the paid HTTP request tool.
	 *
	 * @param enabled whether the tool is registered; off by default because the model
	 * chooses the URLs it calls and pays
	 * @param allowedHosts hosts the tool may call and pay, required when enabled:
	 * hostnames (case-insensitive, port ignored) or {@code *.domain} for subdomains
	 * @param maxResponseLength maximum response body length returned to the model
	 */
	public record PaidHttpTool(Boolean enabled, List<String> allowedHosts, Integer maxResponseLength) {

		/** Default maximum response body length. */
		public static final int DEFAULT_MAX_RESPONSE_LENGTH = 10000;

		public PaidHttpTool {
			if (enabled == null) {
				enabled = false;
			}
			if (allowedHosts == null) {
				allowedHosts = List.of();
			}
			if (maxResponseLength == null || maxResponseLength <= 0) {
				maxResponseLength = DEFAULT_MAX_RESPONSE_LENGTH;
			}
		}

	}

	/**
	 * Defaults of payment sessions created by {@code PaymentSessionRegistry}.
	 *
	 * @param maxSpend budget of a new session in USD
	 * @param expiry lifetime of a new session, 15 to 480 minutes
	 * @param maxEntries maximum number of remembered sessions
	 */
	public record Session(String maxSpend, Duration expiry, Long maxEntries) {

		/** Default budget of a new session in USD. */
		public static final String DEFAULT_MAX_SPEND = "1.00";

		/** Default lifetime of a new session. */
		public static final Duration DEFAULT_EXPIRY = Duration.ofMinutes(60);

		/** Default maximum number of remembered sessions. */
		public static final long DEFAULT_MAX_ENTRIES = 10_000;

		public Session {
			if (maxSpend == null || maxSpend.isBlank()) {
				maxSpend = DEFAULT_MAX_SPEND;
			}
			if (expiry == null) {
				expiry = DEFAULT_EXPIRY;
			}
			if (maxEntries == null || maxEntries <= 0) {
				maxEntries = DEFAULT_MAX_ENTRIES;
			}
		}

	}

}
