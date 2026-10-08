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

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import org.springframework.http.HttpHeaders;

/**
 * Payment requirements of an x402 {@code 402} response
 * (<a href= "https://www.x402.org/">x402.org</a>). Version 2 sends them base64-encoded in
 * the {@code Payment-Required} header, version 1 in the response body.
 *
 * @author Andrei Shakirin
 */
final class X402PaymentRequirements {

	static final String PAYMENT_REQUIRED_HEADER = "Payment-Required";

	static final String V1_PAYMENT_HEADER = "X-PAYMENT";

	static final String V2_PAYMENT_HEADER = "PAYMENT-SIGNATURE";

	private final JsonMapper jsonMapper;

	private final ObjectNode payload;

	private final int version;

	private X402PaymentRequirements(JsonMapper jsonMapper, ObjectNode payload, int version) {
		this.jsonMapper = jsonMapper;
		this.payload = payload;
		this.version = version;
	}

	static X402PaymentRequirements parse(PaymentRequired response, JsonMapper jsonMapper) {
		String header = response.headers().getFirst(PAYMENT_REQUIRED_HEADER);
		String json;
		if (header != null) {
			try {
				json = new String(Base64.getDecoder().decode(header.trim()), StandardCharsets.UTF_8);
			}
			catch (IllegalArgumentException ex) {
				throw new PaymentException("x402: Payment-Required header is not valid base64", ex);
			}
		}
		else {
			json = response.body();
		}
		if (header == null && isMpp(response)) {
			throw new PaymentException(
					"The 402 response uses the Machine Payments Protocol (WWW-Authenticate: Payment), "
							+ "which is not supported yet; only x402 is supported");
		}
		if (json == null || json.isBlank()) {
			throw new PaymentException("x402: 402 response carries no payment requirements");
		}
		JsonNode node;
		try {
			node = jsonMapper.readTree(json);
		}
		catch (JacksonException ex) {
			throw new PaymentException("x402: payment requirements are not valid JSON", ex);
		}
		if (!(node instanceof ObjectNode payload)) {
			throw new PaymentException("x402: payment requirements must be a JSON object");
		}
		JsonNode version = payload.get("x402Version");
		if (version == null || !version.canConvertToInt()) {
			throw new PaymentException("x402: missing or invalid x402Version");
		}
		JsonNode accepts = payload.get("accepts");
		if (accepts == null || !accepts.isArray()) {
			throw new PaymentException("x402: missing accepts list");
		}
		int v = version.asInt();
		if (v != 1 && v != 2) {
			throw new PaymentException("x402: unsupported x402Version " + v + ", supported versions are 1 and 2");
		}
		return new X402PaymentRequirements(jsonMapper, payload, v);
	}

	int version() {
		return this.version;
	}

	/**
	 * Selects the payment option to pay: only options on the instrument's blockchain are
	 * eligible; among them the first matching preference wins, otherwise the first
	 * eligible option.
	 * @param instrumentNetwork payment instrument network ({@code ETHEREUM} or
	 * {@code SOLANA})
	 * @param preferences network identifiers, most preferred first
	 * @return the selected entry of {@code accepts}
	 */
	ObjectNode selectAccept(String instrumentNetwork, List<String> preferences) {
		Set<String> supported = NetworkPreferences.networksFor(instrumentNetwork);
		List<ObjectNode> eligible = new ArrayList<>();
		for (JsonNode accept : this.payload.get("accepts")) {
			if (accept instanceof ObjectNode option && supported.contains(network(option))) {
				eligible.add(option);
			}
		}
		if (eligible.isEmpty()) {
			throw new PaymentException(
					"x402: no payment option matches the payment instrument network '" + instrumentNetwork + "'");
		}
		for (String preferred : preferences) {
			for (ObjectNode option : eligible) {
				if (network(option).equals(preferred.toLowerCase(Locale.ROOT))) {
					return option;
				}
			}
		}
		return eligible.get(0);
	}

	/**
	 * Builds the request header carrying the signed payment.
	 * @param accept the selected payment option
	 * @param proof {@code paymentOutput.cryptoX402.payload} returned by ProcessPayment
	 * @return {@code X-PAYMENT} (v1) or {@code PAYMENT-SIGNATURE} (v2) header
	 */
	PaymentHeader buildHeader(ObjectNode accept, JsonNode proof) {
		ObjectNode header = this.jsonMapper.createObjectNode();
		header.put("x402Version", this.version);
		String name;
		if (this.version == 1) {
			header.set("scheme", accept.get("scheme"));
			header.set("network", accept.get("network"));
			name = V1_PAYMENT_HEADER;
		}
		else {
			header.set("resource", this.payload.get("resource"));
			header.set("accepted", accept);
			JsonNode extensions = this.payload.get("extensions");
			header.set("extensions", (extensions != null) ? extensions : this.jsonMapper.createObjectNode());
			name = V2_PAYMENT_HEADER;
		}
		header.set("payload", proof);
		byte[] json = this.jsonMapper.writeValueAsBytes(header);
		return new PaymentHeader(name, Base64.getEncoder().encodeToString(json));
	}

	private static boolean isMpp(PaymentRequired response) {
		List<String> challenges = response.headers().get(HttpHeaders.WWW_AUTHENTICATE);
		return challenges != null && challenges.stream()
			.anyMatch((challenge) -> challenge.trim().regionMatches(true, 0, "Payment ", 0, "Payment ".length()));
	}

	static boolean isUptoScheme(ObjectNode accept) {
		JsonNode scheme = accept.get("scheme");
		return scheme != null && scheme.isString() && "upto".equalsIgnoreCase(scheme.stringValue().trim());
	}

	private static String network(ObjectNode option) {
		JsonNode network = option.get("network");
		return (network != null && network.isString()) ? network.stringValue().toLowerCase(Locale.ROOT) : "";
	}

}
