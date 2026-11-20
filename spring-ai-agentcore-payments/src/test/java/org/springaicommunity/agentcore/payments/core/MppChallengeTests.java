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
import java.time.Instant;
import java.util.Base64;
import java.util.List;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.bedrockagentcore.model.MppPaymentOutput;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.http.HttpHeaders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Tests for {@link MppChallenge}.
 *
 * @author Andrei Shakirin
 */
class MppChallengeTests {

	static final String TEMPO_REQUEST = request(
			"{\"amount\":\"100000\",\"currency\":\"0x20c0000000000000000000000000000000000000\","
					+ "\"methodDetails\":{\"chainId\":42431,\"feePayer\":true},\"recipient\":\"0xf39F\"}");

	// As sent by https://mpp.dev/api/ping/paid (Tempo Moderato testnet), 2026-11-20
	static final String TEMPO_CHALLENGE = "Payment id=\"izsd_mxk\", realm=\"mpp.sh\", method=\"tempo\", "
			+ "intent=\"charge\", request=\"" + TEMPO_REQUEST + "\", description=\"Ping, endpoint access\"";

	private static final Instant NOW = Instant.parse("2026-11-20T12:00:00Z");

	private final JsonMapper jsonMapper = JsonMapper.builder().build();

	@Test
	void parsesChallengeAndKeepsItsText() {
		List<MppChallenge> challenges = this.parse(TEMPO_CHALLENGE);

		assertThat(challenges).hasSize(1);
		MppChallenge challenge = challenges.get(0);
		assertThat(challenge.id()).isEqualTo("izsd_mxk");
		assertThat(challenge.method()).isEqualTo("tempo");
		assertThat(challenge.network()).isEqualTo("eip155:42431");
		// the quoted comma stays inside the description
		assertThat(challenge.raw()).isEqualTo(TEMPO_CHALLENGE);
	}

	@Test
	void parsesSeveralChallengesInOneOrSeveralHeaders() {
		HttpHeaders headers = new HttpHeaders();
		headers.add(HttpHeaders.WWW_AUTHENTICATE, challenge("a", "evm", 8453) + ", " + challenge("b", "evm", 1));
		headers.add(HttpHeaders.WWW_AUTHENTICATE, challenge("c", "evm", 84532));

		assertThat(MppChallenge.parse(headers, this.jsonMapper)).extracting(MppChallenge::id)
			.containsExactly("a", "b", "c");
	}

	@Test
	void ignoresOtherSchemesAndKeepsTheirParametersOut() {
		List<MppChallenge> challenges = this
			.parse("Bearer realm=\"api\", " + challenge("a", "evm", 8453) + ", Basic realm=\"x\", charset=\"UTF-8\"");

		assertThat(challenges).hasSize(1);
		assertThat(challenges.get(0).raw()).isEqualTo(challenge("a", "evm", 8453));
	}

	@Test
	void dropsMalformedChallenges() {
		assertThat(this.parse("Payment id=\"a\", realm=\"r\", method=\"evm\", intent=\"charge\"")).isEmpty();
		assertThat(this.parse(
				"Payment id=\"a\", realm=\"r\", method=\"EVM\", intent=\"charge\", request=\"" + request("{}") + "\""))
			.isEmpty();
		assertThat(this.parse("Payment id=\"a\", realm=\"r\", method=\"evm\", intent=\"charge\", request=\"!!\""))
			.isEmpty();
		assertThat(this.parse(
				"Payment id=\"a\", realm=\"r\", method=\"evm\", intent=\"charge\", request=\"" + request("[1]") + "\""))
			.isEmpty();
	}

	@Test
	void derivesSolanaNetwork() {
		assertThat(this.parse(challenge("a", "solana", request("{\"methodDetails\":{}}"))).get(0).network())
			.isEqualTo("solana-mainnet");
		assertThat(this.parse(challenge("a", "solana", request("{\"methodDetails\":{\"network\":\"devnet\"}}")))
			.get(0)
			.network()).isEqualTo("solana-devnet");
		assertThat(this.parse(challenge("a", "solana", request("{\"methodDetails\":{\"network\":\"localnet\"}}")))
			.get(0)
			.network()).isNull();
	}

	@Test
	void selectsByNetworkPreferenceThenExpiryThenServerOrder() {
		List<MppChallenge> challenges = this.parse(challenge("late", "evm", 84532, "2026-11-20T13:00:00Z") + ", "
				+ challenge("soon", "evm", 84532, "2026-11-20T12:30:00Z") + ", " + challenge("base", "evm", 8453));

		assertThat(MppChallenge.select(challenges, "ETHEREUM", List.of("eip155:8453"), true, NOW).id())
			.isEqualTo("base");
		assertThat(MppChallenge.select(challenges, "ETHEREUM", List.of("eip155:84532"), true, NOW).id())
			.isEqualTo("soon");
		assertThat(MppChallenge
			.select(this.parse(challenge("x", "evm", 1) + ", " + challenge("y", "evm", 1)), "ETHEREUM", List.of(), true,
					NOW)
			.id()).isEqualTo("x");
	}

	@Test
	void selectsOnlyMethodsTheInstrumentCanPay() {
		List<MppChallenge> challenges = this
			.parse(challenge("sol", "solana", request("{}")) + ", " + challenge("tempo", "tempo", 42431));

		assertThat(MppChallenge.select(challenges, "ETHEREUM", NetworkPreferences.DEFAULT, true, NOW).id())
			.isEqualTo("tempo");
		assertThat(MppChallenge.select(challenges, "SOLANA", NetworkPreferences.DEFAULT, true, NOW).id())
			.isEqualTo("sol");
		assertThatExceptionOfType(MppChallenge.MppSelectionException.class)
			.isThrownBy(() -> MppChallenge.select(this.parse(challenge("sol", "solana", request("{}"))), "ETHEREUM",
					NetworkPreferences.DEFAULT, true, NOW))
			.withMessageContaining("network 'ETHEREUM'")
			.withMessageContaining("Offered methods: solana");
	}

	@Test
	void rejectsExpiredChallengesAndOtherIntents() {
		List<MppChallenge> challenges = this.parse(challenge("old", "evm", 1, "2026-11-20T11:59:59Z") + ", "
				+ "Payment id=\"s\", realm=\"r\", method=\"evm\", intent=\"session\", request=\""
				+ request("{\"methodDetails\":{\"chainId\":1}}") + "\"");

		assertThatExceptionOfType(MppChallenge.MppSelectionException.class)
			.isThrownBy(() -> MppChallenge.select(challenges, "ETHEREUM", NetworkPreferences.DEFAULT, true, NOW))
			.withMessageContaining("1 expired, 1 with unsupported intent: session");
	}

	@Test
	void treatsUnparseableExpiryAsNotExpired() {
		List<MppChallenge> challenges = this.parse(challenge("a", "evm", 1, "tomorrow"));

		assertThat(MppChallenge.select(challenges, "ETHEREUM", NetworkPreferences.DEFAULT, true, NOW).id())
			.isEqualTo("a");
	}

	@Test
	void keepsTheReceivedTextOfTheChallenge() {
		String received = "payment  id=\"a\",realm=\"merchant\" ,  method=\"evm\", intent=\"charge\", request=\""
				+ request("{\"methodDetails\":{\"chainId\":1}}") + "\"";

		List<MppChallenge> challenges = this.parse("Bearer realm=\"api\", " + received + ", Basic realm=\"x\"");

		assertThat(challenges).hasSize(1);
		assertThat(challenges.get(0).raw()).isEqualTo(received);
	}

	@Test
	void skipsChallengesThatLeaveNetworkFeesToTheBuyer() {
		String unsponsored = challenge("unsponsored", "evm", 8453);
		String sponsored = challenge("sponsored", "evm",
				request("{\"methodDetails\":{\"chainId\":84532,\"feePayer\":true}}"));
		List<MppChallenge> challenges = this.parse(unsponsored + ", " + sponsored);

		assertThat(MppChallenge.select(challenges, "ETHEREUM", List.of("eip155:8453"), false, NOW).id())
			.isEqualTo("sponsored");
		assertThat(MppChallenge.select(challenges, "ETHEREUM", List.of("eip155:8453"), true, NOW).id())
			.isEqualTo("unsponsored");
		assertThatExceptionOfType(MppChallenge.MppSelectionException.class).isThrownBy(
				() -> MppChallenge.select(this.parse(unsponsored), "ETHEREUM", NetworkPreferences.DEFAULT, false, NOW))
			.withMessageContaining("leave the network fee to the buyer");
	}

	@Test
	void readsChainIdsOfAnySize() {
		String huge = "123456789012345678901234567890";

		assertThat(this.parse(challenge("a", "evm", request("{\"methodDetails\":{\"chainId\":\"" + huge + "\"}}")))
			.get(0)
			.network()).isEqualTo("eip155:" + huge);
		assertThat(this.parse(challenge("a", "evm", request("{\"methodDetails\":{\"chainId\":" + huge + "}}")))
			.get(0)
			.network()).isEqualTo("eip155:" + huge);
	}

	@Test
	void buildsAuthorizationHeaderFromCredential() {
		MppChallenge challenge = this.parse(TEMPO_CHALLENGE).get(0);

		assertThat(challenge.buildHeader(output("izsd_mxk", "eyJjcmVk")))
			.isEqualTo(new PaymentHeader("Authorization", "Payment eyJjcmVk"));
		assertThat(challenge.buildHeader(output(null, "Payment eyJjcmVk")).value()).isEqualTo("Payment eyJjcmVk");
		assertThatExceptionOfType(PaymentException.class)
			.isThrownBy(() -> challenge.buildHeader(output("other", "eyJjcmVk")))
			.withMessageContaining("paid challenge 'other' instead of 'izsd_mxk'");
		assertThatExceptionOfType(PaymentException.class).isThrownBy(() -> challenge.buildHeader(output(null, " ")))
			.withMessageContaining("no payment credential");
	}

	private List<MppChallenge> parse(String headerValue) {
		HttpHeaders headers = new HttpHeaders();
		headers.add(HttpHeaders.WWW_AUTHENTICATE, headerValue);
		return MppChallenge.parse(headers, this.jsonMapper);
	}

	static String challenge(String id, String method, long chainId) {
		return challenge(id, method, request("{\"methodDetails\":{\"chainId\":" + chainId + "}}"));
	}

	private static String challenge(String id, String method, long chainId, String expires) {
		return challenge(id, method, chainId) + ", expires=\"" + expires + "\"";
	}

	private static String challenge(String id, String method, String request) {
		return "Payment id=\"" + id + "\", realm=\"merchant\", method=\"" + method + "\", intent=\"charge\", request=\""
				+ request + "\"";
	}

	static String request(String json) {
		return Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
	}

	private static MppPaymentOutput output(String selectedPaymentId, String credential) {
		return MppPaymentOutput.builder()
			.version("1")
			.selectedPaymentId(selectedPaymentId)
			.paymentCredential(credential)
			.build();
	}

}
