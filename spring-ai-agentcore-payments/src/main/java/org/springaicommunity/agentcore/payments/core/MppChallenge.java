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

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;
import software.amazon.awssdk.services.bedrockagentcore.model.MppPaymentOutput;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import org.springframework.http.HttpHeaders;

/**
 * A payment challenge of the Machine Payments Protocol
 * (<a href="https://mpp.dev">MPP</a>):
 * {@code WWW-Authenticate: Payment id="..", realm="..", method="..", intent="..", request="..", expires=".."}.
 * The challenge is paid through AgentCore Payments and answered with
 * {@code Authorization: Payment <credential>}.
 * <p>
 * Only the {@code charge} intent is supported, with the methods {@code evm} and
 * {@code tempo} (paid by ETHEREUM instruments) and {@code solana} (SOLANA instruments).
 * The raw challenge text is kept, because it is forwarded to the service as received: the
 * merchant binds the challenge to its bytes.
 *
 * @author Andrei Shakirin
 */
final class MppChallenge {

	static final String AUTH_SCHEME = "Payment";

	static final String AUTHORIZATION_HEADER = HttpHeaders.AUTHORIZATION;

	private static final String CHARGE_INTENT = "charge";

	private static final Map<String, String> METHOD_NETWORKS = Map.of("evm", "ETHEREUM", "tempo", "ETHEREUM", "solana",
			"SOLANA");

	// localnet is a local test environment, not Solana testnet: it stays unranked
	private static final Map<String, String> SOLANA_NETWORKS = Map.of("mainnet", "solana-mainnet", "devnet",
			"solana-devnet", "testnet", "solana-testnet");

	private static final List<String> REQUIRED_PARAMS = List.of("id", "realm", "method", "intent", "request");

	private static final Pattern BASE64URL = Pattern.compile("[A-Za-z0-9_-]+");

	private static final Pattern METHOD = Pattern.compile("[a-z]+");

	private final Map<String, String> params;

	private final String raw;

	private final ObjectNode request;

	private final int position;

	private MppChallenge(Map<String, String> params, String raw, ObjectNode request, int position) {
		this.params = params;
		this.raw = raw;
		this.request = request;
		this.position = position;
	}

	/**
	 * Parses the {@code Payment} challenges of all {@code WWW-Authenticate} headers, in
	 * the order the server sent them. Challenges of other schemes and malformed
	 * challenges are ignored.
	 * @param headers response headers
	 * @param jsonMapper mapper for the decoded {@code request} parameter
	 * @return the valid challenges, possibly empty
	 */
	static List<MppChallenge> parse(HttpHeaders headers, JsonMapper jsonMapper) {
		List<String> values = headers.get(HttpHeaders.WWW_AUTHENTICATE);
		List<MppChallenge> challenges = new ArrayList<>();
		if (values == null) {
			return challenges;
		}
		for (String value : values) {
			parseHeaderValue(value, jsonMapper, challenges);
		}
		return challenges;
	}

	/**
	 * Selects the challenge to pay. Drops challenges with an intent other than
	 * {@code charge}, expired challenges and challenges whose method the instrument
	 * cannot pay; ranks the rest by network preference, then by soonest expiry, then by
	 * server order.
	 * @param challenges parsed challenges, not empty
	 * @param instrumentNetwork payment instrument network ({@code ETHEREUM} or
	 * {@code SOLANA})
	 * @param preferences network identifiers, most preferred first
	 * @param now reference time for expiry
	 * @return the selected challenge
	 * @throws MppSelectionException if no challenge can be paid
	 */
	static MppChallenge select(List<MppChallenge> challenges, String instrumentNetwork, List<String> preferences,
			Instant now) {
		String network = instrumentNetwork.trim().toUpperCase(Locale.ROOT);
		if (!METHOD_NETWORKS.containsValue(network)) {
			throw new MppSelectionException("MPP: unsupported payment instrument network '" + instrumentNetwork
					+ "'. Supported networks are ETHEREUM and SOLANA.");
		}
		List<MppChallenge> usable = new ArrayList<>();
		Set<String> unsupportedIntents = new TreeSet<>();
		int expired = 0;
		for (MppChallenge challenge : challenges) {
			if (!CHARGE_INTENT.equals(challenge.intent())) {
				unsupportedIntents.add(challenge.intent());
			}
			else if (challenge.isExpired(now)) {
				expired++;
			}
			else {
				usable.add(challenge);
			}
		}
		if (usable.isEmpty()) {
			throw new MppSelectionException("MPP: all " + challenges.size() + " challenge(s) were rejected (" + expired
					+ " expired, " + (challenges.size() - expired) + " with unsupported intent"
					+ ((unsupportedIntents.isEmpty()) ? "" : ": " + String.join(", ", unsupportedIntents))
					+ "); only the 'charge' intent is supported");
		}
		Set<String> methods = new TreeSet<>();
		List<MppChallenge> payable = new ArrayList<>();
		for (MppChallenge challenge : usable) {
			methods.add(challenge.method());
			if (network.equals(METHOD_NETWORKS.get(challenge.method()))) {
				payable.add(challenge);
			}
		}
		if (payable.isEmpty()) {
			throw new MppSelectionException("MPP: no challenge can be paid by a payment instrument on network '"
					+ network + "'. Offered methods: " + String.join(", ", methods) + "; supported methods: "
					+ String.join(", ", new TreeSet<>(METHOD_NETWORKS.keySet())));
		}
		List<String> ranking = preferences.stream().map((p) -> p.toLowerCase(Locale.ROOT)).toList();
		return payable.stream()
			.min(Comparator.comparingInt((MppChallenge c) -> c.rank(ranking))
				.thenComparing((MppChallenge c) -> c.expires(), Comparator.nullsLast(Comparator.naturalOrder()))
				.thenComparingInt((c) -> c.position))
			.orElseThrow();
	}

	/**
	 * Builds the {@code Authorization} header from the output of ProcessPayment.
	 * @param output {@code paymentOutput.mpp} returned by ProcessPayment
	 * @return {@code Authorization: Payment <credential>}
	 */
	PaymentHeader buildHeader(@Nullable MppPaymentOutput output) {
		String credential = (output != null) ? output.paymentCredential() : null;
		if (credential == null || credential.isBlank()) {
			throw new PaymentException("MPP: ProcessPayment returned no payment credential");
		}
		String paidId = output.selectedPaymentId();
		if (paidId != null && !paidId.equals(this.id())) {
			throw new PaymentException(
					"MPP: ProcessPayment paid challenge '" + paidId + "' instead of '" + this.id() + "'");
		}
		credential = credential.trim();
		String value = (credential.regionMatches(true, 0, AUTH_SCHEME + " ", 0, AUTH_SCHEME.length() + 1)) ? credential
				: AUTH_SCHEME + " " + credential;
		return new PaymentHeader(AUTHORIZATION_HEADER, value);
	}

	String id() {
		return this.params.get("id");
	}

	String method() {
		return this.params.get("method");
	}

	/**
	 * The challenge as received, without other challenges of the same header.
	 * @return {@code Payment id="..", ...}
	 */
	String raw() {
		return this.raw;
	}

	/**
	 * The network of the challenge, for preference ordering: {@code eip155:<chainId>} for
	 * EVM methods, {@code solana-<network>} for Solana.
	 * @return lower-case network identifier, or {@code null} if unknown
	 */
	@Nullable String network() {
		JsonNode details = this.request.path("methodDetails");
		String family = METHOD_NETWORKS.get(this.method());
		if ("ETHEREUM".equals(family)) {
			JsonNode chainId = details.path("chainId");
			if (chainId.isIntegralNumber()) {
				return "eip155:" + chainId.asLong();
			}
			if (chainId.isString() && chainId.stringValue().trim().matches("[0-9]+")) {
				return "eip155:" + Long.parseLong(chainId.stringValue().trim());
			}
			return null;
		}
		if ("SOLANA".equals(family)) {
			JsonNode solana = details.path("network");
			String name = (solana.isString() && !solana.stringValue().isBlank()) ? solana.stringValue() : "mainnet";
			return SOLANA_NETWORKS.get(name.trim().toLowerCase(Locale.ROOT));
		}
		return null;
	}

	private String intent() {
		return this.params.get("intent").trim().toLowerCase(Locale.ROOT);
	}

	private int rank(List<String> ranking) {
		String network = this.network();
		int index = (network != null) ? ranking.indexOf(network) : -1;
		return (index >= 0) ? index : ranking.size();
	}

	// A missing or unparseable expiry counts as not expired: the service decides
	private boolean isExpired(Instant now) {
		Instant expires = this.expires();
		return expires != null && !expires.isAfter(now);
	}

	private @Nullable Instant expires() {
		String value = this.params.get("expires");
		if (value == null || value.isBlank()) {
			return null;
		}
		try {
			return OffsetDateTime.parse(value.trim()).toInstant();
		}
		catch (DateTimeParseException ex) {
			try {
				return LocalDateTime.parse(value.trim()).toInstant(ZoneOffset.UTC);
			}
			catch (DateTimeParseException ignored) {
				return null;
			}
		}
	}

	private static void parseHeaderValue(@Nullable String value, JsonMapper jsonMapper, List<MppChallenge> out) {
		if (value == null) {
			return;
		}
		Map<String, String> params = null;
		List<String> parts = new ArrayList<>();
		for (String part : splitOutsideQuotes(value)) {
			if (startsScheme(part, AUTH_SCHEME)) {
				add(params, parts, jsonMapper, out);
				params = new LinkedHashMap<>();
				parts = new ArrayList<>();
				String rest = part.substring(AUTH_SCHEME.length()).trim();
				if (!rest.isEmpty() && putParam(params, rest)) {
					parts.add(rest);
				}
			}
			else if (startsOtherScheme(part)) {
				// e.g. Bearer realm="x": its params must not become part of the challenge
				add(params, parts, jsonMapper, out);
				params = null;
			}
			else if (params != null && putParam(params, part)) {
				parts.add(part);
			}
		}
		add(params, parts, jsonMapper, out);
	}

	private static void add(@Nullable Map<String, String> params, List<String> parts, JsonMapper jsonMapper,
			List<MppChallenge> out) {
		if (params == null || parts.isEmpty()) {
			return;
		}
		for (String name : REQUIRED_PARAMS) {
			String param = params.get(name);
			if (param == null || param.isBlank()) {
				return;
			}
		}
		if (!METHOD.matcher(params.get("method")).matches()) {
			return;
		}
		ObjectNode request = decodeRequest(params.get("request"), jsonMapper);
		if (request == null) {
			return;
		}
		out.add(new MppChallenge(params, AUTH_SCHEME + " " + String.join(", ", parts), request, out.size()));
	}

	// The request parameter is JSON, base64url-encoded without padding
	private static @Nullable ObjectNode decodeRequest(String encoded, JsonMapper jsonMapper) {
		if (!BASE64URL.matcher(encoded).matches()) {
			return null;
		}
		try {
			byte[] json = Base64.getUrlDecoder().decode(encoded);
			return (jsonMapper.readTree(json) instanceof ObjectNode request) ? request : null;
		}
		// Jackson throws NumberFormatException for a number out of the BigDecimal range
		catch (IllegalArgumentException | JacksonException ex) {
			return null;
		}
	}

	private static boolean putParam(Map<String, String> params, String part) {
		int equals = part.indexOf('=');
		if (equals <= 0) {
			return false;
		}
		String name = part.substring(0, equals).trim().toLowerCase(Locale.ROOT);
		if (name.isEmpty()) {
			return false;
		}
		params.put(name, unquote(part.substring(equals + 1)));
		return true;
	}

	private static String unquote(String value) {
		String trimmed = value.trim();
		if (trimmed.length() >= 2 && trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
			return trimmed.substring(1, trimmed.length() - 1).replace("\\\"", "\"").replace("\\\\", "\\");
		}
		return trimmed;
	}

	private static boolean startsScheme(String part, String scheme) {
		return part.equalsIgnoreCase(scheme) || part.regionMatches(true, 0, scheme + " ", 0, scheme.length() + 1);
	}

	// A new scheme is a bare token, or a token followed by whitespace before its first
	// parameter; a parameter of the current challenge has no whitespace before '='
	private static boolean startsOtherScheme(String part) {
		int equals = part.indexOf('=');
		String head = (equals >= 0) ? part.substring(0, equals).trim() : part;
		if (equals >= 0 && !head.contains(" ")) {
			return false;
		}
		return !startsScheme(part, AUTH_SCHEME);
	}

	// RFC 9110 parameter values may be quoted strings containing commas
	private static List<String> splitOutsideQuotes(String value) {
		List<String> parts = new ArrayList<>();
		StringBuilder current = new StringBuilder();
		boolean quoted = false;
		boolean escaped = false;
		for (char c : value.toCharArray()) {
			if (escaped) {
				escaped = false;
			}
			else if (c == '\\' && quoted) {
				escaped = true;
			}
			else if (c == '"') {
				quoted = !quoted;
			}
			else if (c == ',' && !quoted) {
				addPart(parts, current);
				current.setLength(0);
				continue;
			}
			current.append(c);
		}
		addPart(parts, current);
		return parts;
	}

	private static void addPart(List<String> parts, StringBuilder current) {
		String part = current.toString().trim();
		if (!part.isEmpty()) {
			parts.add(part);
		}
	}

	/**
	 * No offered challenge can be paid. Raised before ProcessPayment is called, so the
	 * caller may still pay an x402 option of the same response.
	 */
	static final class MppSelectionException extends PaymentException {

		MppSelectionException(String message) {
			super(message);
		}

	}

}
