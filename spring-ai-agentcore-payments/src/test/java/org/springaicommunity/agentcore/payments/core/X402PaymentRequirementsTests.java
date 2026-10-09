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
import java.util.Base64;
import java.util.List;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import org.springframework.http.HttpHeaders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Tests for {@link X402PaymentRequirements}.
 *
 * @author Andrei Shakirin
 */
class X402PaymentRequirementsTests {

	static final String V1_BODY = """
			{"x402Version":1,"accepts":[
			  {"scheme":"exact","network":"solana-devnet","maxAmountRequired":"5000","payTo":"So1","asset":"SoA"},
			  {"scheme":"exact","network":"base-sepolia","maxAmountRequired":"5000","payTo":"0xabc","asset":"0xdef"},
			  {"scheme":"exact","network":"eip155:8453","maxAmountRequired":"5000","payTo":"0xabc","asset":"0xdef"}
			]}""";

	private final JsonMapper jsonMapper = JsonMapper.builder().build();

	@Test
	void selectsPreferredOptionOnInstrumentNetworkFromV1Body() {
		X402PaymentRequirements requirements = X402PaymentRequirements.parse(v1(V1_BODY), this.jsonMapper);

		assertThat(requirements.version()).isEqualTo(1);
		assertThat(requirements.selectAccept("ETHEREUM", NetworkPreferences.DEFAULT).get("network").stringValue())
			.isEqualTo("eip155:8453");
		assertThat(requirements.selectAccept("ETHEREUM", List.of("base-sepolia")).get("network").stringValue())
			.isEqualTo("base-sepolia");
		assertThat(requirements.selectAccept("SOLANA", List.of()).get("network").stringValue())
			.isEqualTo("solana-devnet");
	}

	@Test
	void buildsV1PaymentHeader() {
		X402PaymentRequirements requirements = X402PaymentRequirements.parse(v1(V1_BODY), this.jsonMapper);
		ObjectNode accept = requirements.selectAccept("ETHEREUM", List.of("base-sepolia"));

		PaymentHeader header = requirements.buildHeader(accept, this.jsonMapper.readTree("{\"signature\":\"0x1\"}"));

		assertThat(header.name()).isEqualTo("X-PAYMENT");
		JsonNode decoded = this.decode(header.value());
		assertThat(decoded.get("x402Version").asInt()).isEqualTo(1);
		assertThat(decoded.get("scheme").stringValue()).isEqualTo("exact");
		assertThat(decoded.get("network").stringValue()).isEqualTo("base-sepolia");
		assertThat(decoded.at("/payload/signature").stringValue()).isEqualTo("0x1");
	}

	@Test
	void readsV2RequirementsFromHeaderAndBuildsPaymentSignature() {
		String v2 = """
				{"x402Version":2,"resource":{"url":"https://example.com/api"},
				 "accepts":[{"scheme":"exact","network":"eip155:84532","amount":"10","payTo":"0xabc","asset":"0xdef"}]}""";
		HttpHeaders headers = new HttpHeaders();
		headers.add("payment-required", Base64.getEncoder().encodeToString(v2.getBytes(StandardCharsets.UTF_8)));
		X402PaymentRequirements requirements = X402PaymentRequirements.parse(new PaymentRequired(402, headers, "{}"),
				this.jsonMapper);
		ObjectNode accept = requirements.selectAccept("ETHEREUM", NetworkPreferences.DEFAULT);

		PaymentHeader header = requirements.buildHeader(accept, this.jsonMapper.readTree("{\"signature\":\"0x2\"}"));

		assertThat(header.name()).isEqualTo("PAYMENT-SIGNATURE");
		JsonNode decoded = this.decode(header.value());
		assertThat(decoded.get("x402Version").asInt()).isEqualTo(2);
		assertThat(decoded.at("/resource/url").stringValue()).isEqualTo("https://example.com/api");
		assertThat(decoded.at("/accepted/network").stringValue()).isEqualTo("eip155:84532");
		assertThat(decoded.get("extensions").isObject()).isTrue();
		assertThat(decoded.at("/payload/signature").stringValue()).isEqualTo("0x2");
	}

	@Test
	void failsWhenNoOptionMatchesInstrumentNetwork() {
		X402PaymentRequirements requirements = X402PaymentRequirements
			.parse(v1("{\"x402Version\":1,\"accepts\":[{\"network\":\"base\"}]}"), this.jsonMapper);

		assertThatExceptionOfType(PaymentException.class)
			.isThrownBy(() -> requirements.selectAccept("SOLANA", NetworkPreferences.DEFAULT))
			.withMessageContaining("SOLANA");
	}

	@Test
	void failsOnResponseWithoutRequirements() {
		assertThatExceptionOfType(PaymentException.class)
			.isThrownBy(() -> X402PaymentRequirements.parse(v1("{\"error\":\"nope\"}"), this.jsonMapper))
			.withMessageContaining("x402Version");
	}

	@Test
	void explainsThatMppIsNotSupported() {
		HttpHeaders headers = new HttpHeaders();
		headers.add(HttpHeaders.WWW_AUTHENTICATE, "Payment id=\"abc\", method=\"evm\", intent=\"charge\"");

		assertThatExceptionOfType(PaymentException.class)
			.isThrownBy(() -> X402PaymentRequirements.parse(new PaymentRequired(402, headers, "{}"), this.jsonMapper))
			.withMessageContaining("Machine Payments Protocol")
			.withMessageContaining("only x402 is supported");
	}

	static PaymentRequired v1(String body) {
		return new PaymentRequired(402, new HttpHeaders(), body);
	}

	private JsonNode decode(String header) {
		return this.jsonMapper.readTree(Base64.getDecoder().decode(header));
	}

}
