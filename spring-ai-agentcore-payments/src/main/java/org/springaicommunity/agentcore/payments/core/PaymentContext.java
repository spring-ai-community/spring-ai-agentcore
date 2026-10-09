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

package org.springaicommunity.agentcore.payments.core;

import org.jspecify.annotations.Nullable;

/**
 * Identifies who pays and from which budget: the AgentCore user, the payment instrument
 * (wallet) and the payment session (spending limit and expiry).
 *
 * @param userId AgentCore Payments user id
 * @param paymentInstrumentId payment instrument used to sign payments
 * @param paymentSessionId payment session that limits spending
 * @author Andrei Shakirin
 */
public record PaymentContext(@Nullable String userId, @Nullable String paymentInstrumentId,
		@Nullable String paymentSessionId) {

	/** Tool context key overriding the configured user id. */
	public static final String USER_ID_KEY = "agentcore.payments.user-id";

	/** Tool context key overriding the configured payment instrument id. */
	public static final String PAYMENT_INSTRUMENT_ID_KEY = "agentcore.payments.payment-instrument-id";

	/** Tool context key overriding the configured payment session id. */
	public static final String PAYMENT_SESSION_ID_KEY = "agentcore.payments.payment-session-id";

	/**
	 * Returns the user id or fails when it is not set.
	 * @return the user id
	 */
	public String requireUserId() {
		return require(this.userId, "userId", USER_ID_KEY);
	}

	/**
	 * Returns the payment instrument id or fails when it is not set.
	 * @return the payment instrument id
	 */
	public String requirePaymentInstrumentId() {
		return require(this.paymentInstrumentId, "paymentInstrumentId", PAYMENT_INSTRUMENT_ID_KEY);
	}

	/**
	 * Returns the payment session id or fails when it is not set.
	 * @return the payment session id
	 */
	public String requirePaymentSessionId() {
		return require(this.paymentSessionId, "paymentSessionId", PAYMENT_SESSION_ID_KEY);
	}

	private static String require(@Nullable String value, String name, String key) {
		if (value == null || value.isBlank()) {
			throw new PaymentConfigurationException(name + " is required for payments. Pass it in the tool context ('"
					+ key + "') or the PaymentContext request attribute"
					+ ((PAYMENT_SESSION_ID_KEY.equals(key)) ? "; create sessions with PaymentSessionRegistry." : "."));
		}
		return value;
	}

}
