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
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.http.HttpHeaders;

/**
 * An HTTP {@code 402 Payment Required} response as received from a paid resource.
 *
 * @param statusCode HTTP status code, must be {@code 402}
 * @param headers response headers (case-insensitive)
 * @param body response body, may be {@code null}
 * @author Andrei Shakirin
 */
public record PaymentRequired(int statusCode, HttpHeaders headers, @Nullable String body) {

	/** HTTP status code for Payment Required. */
	public static final int PAYMENT_REQUIRED_STATUS = 402;

	private static final JsonMapper JSON_MAPPER = JsonMapper.builder()
		.enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
		.build();

	/**
	 * Whether the response offers a valid MPP challenge
	 * ({@code WWW-Authenticate: Payment ...}). Paying it sends the credential in the
	 * {@code Authorization} header of the retried request, replacing any credential the
	 * request already carries.
	 * @return {@code true} if an MPP challenge is offered
	 */
	public boolean offersMpp() {
		return !MppChallenge.parse(this.headers, JSON_MAPPER).isEmpty();
	}

	/**
	 * Whether the response offers x402 payment options.
	 * @return {@code true} if valid x402 requirements with at least one option are
	 * present
	 */
	public boolean offersX402() {
		try {
			return X402PaymentRequirements.parse(this, JSON_MAPPER).hasOptions();
		}
		catch (PaymentException ex) {
			return false;
		}
	}

	/**
	 * Returns this response without its {@code WWW-Authenticate} headers, so that only
	 * x402 options are paid. Used when the retried request must keep its own
	 * {@code Authorization} header, which an MPP payment would replace.
	 * @return a copy without MPP challenges
	 */
	public PaymentRequired withoutMpp() {
		HttpHeaders headers = new HttpHeaders();
		headers.putAll(this.headers);
		headers.remove(HttpHeaders.WWW_AUTHENTICATE);
		return new PaymentRequired(this.statusCode, headers, this.body);
	}

}
