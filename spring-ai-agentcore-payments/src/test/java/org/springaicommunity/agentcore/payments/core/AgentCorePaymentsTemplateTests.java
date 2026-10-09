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

import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.core.document.Document;
import software.amazon.awssdk.services.bedrockagentcore.BedrockAgentCoreClient;
import software.amazon.awssdk.services.bedrockagentcore.model.BlockchainChainId;
import software.amazon.awssdk.services.bedrockagentcore.model.CreatePaymentSessionRequest;
import software.amazon.awssdk.services.bedrockagentcore.model.CreatePaymentSessionResponse;
import software.amazon.awssdk.services.bedrockagentcore.model.CryptoWalletNetwork;
import software.amazon.awssdk.services.bedrockagentcore.model.CryptoX402PaymentOutput;
import software.amazon.awssdk.services.bedrockagentcore.model.EmbeddedCryptoWallet;
import software.amazon.awssdk.services.bedrockagentcore.model.GetPaymentInstrumentResponse;
import software.amazon.awssdk.services.bedrockagentcore.model.InstrumentBalanceToken;
import software.amazon.awssdk.services.bedrockagentcore.model.PaymentInstrument;
import software.amazon.awssdk.services.bedrockagentcore.model.PaymentInstrumentDetails;
import software.amazon.awssdk.services.bedrockagentcore.model.PaymentOutput;
import software.amazon.awssdk.services.bedrockagentcore.model.PaymentSession;
import software.amazon.awssdk.services.bedrockagentcore.model.PaymentType;
import software.amazon.awssdk.services.bedrockagentcore.model.ProcessPaymentRequest;
import software.amazon.awssdk.services.bedrockagentcore.model.ProcessPaymentResponse;
import software.amazon.awssdk.services.bedrockagentcore.model.ValidationException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

/**
 * Tests for {@link AgentCorePaymentsTemplate}.
 *
 * @author Andrei Shakirin
 */
@ExtendWith(MockitoExtension.class)
class AgentCorePaymentsTemplateTests {

	private static final String ARN = "arn:aws:bedrock-agentcore:us-east-1:123456789012:payment-manager/pm-1";

	private static final PaymentContext CONTEXT = new PaymentContext("user-1", "instrument-1", "session-1");

	@Mock
	private BedrockAgentCoreClient client;

	@Test
	@SuppressWarnings("unchecked")
	void generatesPaymentHeaderBySigningSelectedOption() {
		this.givenEthereumInstrument();
		given(this.client.processPayment(any(Consumer.class))).willReturn(ProcessPaymentResponse.builder()
			.paymentOutput(PaymentOutput.fromCryptoX402(CryptoX402PaymentOutput.builder()
				.version("1")
				.payload(Document.fromMap(Map.of("signature", Document.fromString("0xsig"))))
				.build()))
			.build());
		AgentCorePaymentsTemplate template = new AgentCorePaymentsTemplate(this.client, ARN);

		PaymentHeader header = template.generatePaymentHeader(CONTEXT,
				X402PaymentRequirementsTests.v1(X402PaymentRequirementsTests.V1_BODY));

		assertThat(header.name()).isEqualTo("X-PAYMENT");
		JsonNode decoded = JsonMapper.builder().build().readTree(Base64.getDecoder().decode(header.value()));
		assertThat(decoded.get("network").stringValue()).isEqualTo("eip155:8453");
		assertThat(decoded.at("/payload/signature").stringValue()).isEqualTo("0xsig");

		ProcessPaymentRequest request = this.capturedProcessPayment();
		assertThat(request.paymentManagerArn()).isEqualTo(ARN);
		assertThat(request.userId()).isEqualTo("user-1");
		assertThat(request.paymentInstrumentId()).isEqualTo("instrument-1");
		assertThat(request.paymentSessionId()).isEqualTo("session-1");
		assertThat(request.paymentType()).isEqualTo(PaymentType.CRYPTO_X402);
		assertThat(request.clientToken()).isNotBlank();
		assertThat(request.paymentInput().cryptoX402().version()).isEqualTo("1");
		assertThat(request.paymentInput().cryptoX402().payload().asMap().get("network").asString())
			.isEqualTo("eip155:8453");
		assertThat(request.paymentInput().cryptoX402().permit2AllowanceLimit()).isNull();
	}

	@Test
	@SuppressWarnings("unchecked")
	void mapsBudgetRejectionToInsufficientBudgetException() {
		this.givenEthereumInstrument();
		// message as returned by AgentCore Payments (observed 2026-10-08); no reason code
		given(this.client.processPayment(any(Consumer.class))).willThrow(ValidationException.builder()
			.message("Insufficient budget for session payment-session-mAB6PzTFxEcvL4B. "
					+ "Pending amount: 0.001 USD, Transaction amount: 0.002000 USD")
			.build());
		AgentCorePaymentsTemplate template = new AgentCorePaymentsTemplate(this.client, ARN);

		assertThatExceptionOfType(InsufficientBudgetException.class)
			.isThrownBy(() -> template.generatePaymentHeader(CONTEXT,
					X402PaymentRequirementsTests.v1(X402PaymentRequirementsTests.V1_BODY)))
			.withMessageNotContaining("payment-session-");
	}

	@Test
	@SuppressWarnings("unchecked")
	void doesNotMapOtherValidationErrors() {
		this.givenEthereumInstrument();
		AgentCorePaymentsTemplate template = new AgentCorePaymentsTemplate(this.client, ARN);
		for (String message : List.of("Insufficient funds in wallet for transaction",
				"Invalid value for field 'session': value 'expired' is not an allowed state",
				"Payment instrument not found: payment-instrument-1")) {
			ValidationException error = ValidationException.builder().message(message).build();
			willThrow(error).given(this.client).processPayment(any(Consumer.class));

			assertThatExceptionOfType(ValidationException.class)
				.isThrownBy(() -> template.generatePaymentHeader(CONTEXT,
						X402PaymentRequirementsTests.v1(X402PaymentRequirementsTests.V1_BODY)))
				.isSameAs(error);
		}
	}

	@Test
	@SuppressWarnings("unchecked")
	void usesCallerTokenAndLooksUpInstrumentNetworkOnce() {
		this.givenEthereumInstrument();
		given(this.client.processPayment(any(Consumer.class))).willReturn(paymentWithProof());
		AgentCorePaymentsTemplate template = new AgentCorePaymentsTemplate(this.client, ARN);
		PaymentRequired paymentRequired = X402PaymentRequirementsTests.v1(X402PaymentRequirementsTests.V1_BODY);

		template.generatePaymentHeader(CONTEXT, paymentRequired, "purchase-1");
		template.generatePaymentHeader(CONTEXT, paymentRequired);

		then(this.client).should().getPaymentInstrument(any(Consumer.class));
		assertThat(this.capturedProcessPayments()).extracting(ProcessPaymentRequest::clientToken)
			.first()
			.isEqualTo("purchase-1");
	}

	@Test
	@SuppressWarnings("unchecked")
	void mapsMissingOrExpiredSessionToPaymentSessionNotFoundException() {
		this.givenEthereumInstrument();
		// message as returned by AgentCore Payments for an expired session (observed
		// 2026-10-09); an unknown session id gets the same message
		given(this.client.processPayment(any(Consumer.class))).willThrow(ValidationException.builder()
			.message("Payment session not found: payment-session-ppWwkpSBHBZhdJE")
			.build());
		AgentCorePaymentsTemplate template = new AgentCorePaymentsTemplate(this.client, ARN);

		assertThatExceptionOfType(PaymentSessionNotFoundException.class).isThrownBy(() -> template
			.generatePaymentHeader(CONTEXT, X402PaymentRequirementsTests.v1(X402PaymentRequirementsTests.V1_BODY)));
	}

	@Test
	@SuppressWarnings("unchecked")
	void looksUpInstrumentNetworkAgainAfterAFailedPayment() {
		this.givenEthereumInstrument();
		given(this.client.processPayment(any(Consumer.class))).willReturn(ProcessPaymentResponse.builder().build())
			.willReturn(paymentWithProof());
		AgentCorePaymentsTemplate template = new AgentCorePaymentsTemplate(this.client, ARN);
		PaymentRequired paymentRequired = X402PaymentRequirementsTests.v1(X402PaymentRequirementsTests.V1_BODY);

		assertThatExceptionOfType(PaymentException.class)
			.isThrownBy(() -> template.generatePaymentHeader(CONTEXT, paymentRequired));
		template.generatePaymentHeader(CONTEXT, paymentRequired);

		then(this.client).should(times(2)).getPaymentInstrument(any(Consumer.class));
	}

	@Test
	@SuppressWarnings("unchecked")
	void grantsPermit2AllowanceOnlyForUptoScheme() {
		this.givenEthereumInstrument();
		given(this.client.processPayment(any(Consumer.class))).willReturn(paymentWithProof());
		AgentCorePaymentsTemplate template = new AgentCorePaymentsTemplate(this.client, ARN, null,
				NetworkPreferences.DEFAULT, "1000000");

		template.generatePaymentHeader(CONTEXT, X402PaymentRequirementsTests.v1("""
				{"x402Version":1,"accepts":[
				  {"scheme":"upto","network":"base-sepolia","maxAmountRequired":"5000"}
				]}"""));
		template.generatePaymentHeader(CONTEXT, X402PaymentRequirementsTests.v1(X402PaymentRequirementsTests.V1_BODY));

		assertThat(this.capturedProcessPayments())
			.extracting((request) -> request.paymentInput().cryptoX402().permit2AllowanceLimit())
			.containsExactly("1000000", null);
	}

	@Test
	@SuppressWarnings("unchecked")
	void rejectsBalanceQueryOnChainOfAnotherNetwork() {
		this.givenEthereumInstrument();
		AgentCorePaymentsTemplate template = new AgentCorePaymentsTemplate(this.client, ARN);

		assertThatExceptionOfType(PaymentException.class)
			.isThrownBy(() -> template.getPaymentInstrumentBalance("user-1", "instrument-1", BlockchainChainId.SOLANA,
					InstrumentBalanceToken.USDC))
			.withMessageContaining("BASE_SEPOLIA");
		then(this.client).should(never()).getPaymentInstrumentBalance(any(Consumer.class));
	}

	@Test
	void requiresPaymentSessionBeforeCallingTheService() {
		AgentCorePaymentsTemplate template = new AgentCorePaymentsTemplate(this.client, ARN);

		assertThatExceptionOfType(PaymentConfigurationException.class)
			.isThrownBy(() -> template.generatePaymentHeader(new PaymentContext("user-1", "instrument-1", null),
					X402PaymentRequirementsTests.v1(X402PaymentRequirementsTests.V1_BODY)))
			.withMessageContaining(PaymentContext.PAYMENT_SESSION_ID_KEY);
		then(this.client).shouldHaveNoInteractions();
	}

	@Test
	@SuppressWarnings("unchecked")
	void passesOutOfRangeAndDecimalNumbersOfTheOptionExactly() {
		this.givenEthereumInstrument();
		given(this.client.processPayment(any(Consumer.class))).willReturn(paymentWithProof());
		AgentCorePaymentsTemplate template = new AgentCorePaymentsTemplate(this.client, ARN);

		template.generatePaymentHeader(CONTEXT, X402PaymentRequirementsTests.v1("""
				{"x402Version":1,"accepts":[{"scheme":"exact","network":"eip155:8453","maxAmountRequired":"5000",
				  "payTo":"0xabc","asset":"0xdef","extra":{"huge":1e348,"price":0.10000000000000000001}}]}"""));

		Map<String, Document> extra = this.capturedProcessPayment()
			.paymentInput()
			.cryptoX402()
			.payload()
			.asMap()
			.get("extra")
			.asMap();
		assertThat(extra.get("huge").asNumber().bigDecimalValue()).isEqualByComparingTo("1e348");
		assertThat(extra.get("price").asNumber().bigDecimalValue()).isEqualByComparingTo("0.10000000000000000001");
	}

	@Test
	@SuppressWarnings("unchecked")
	void createsPaymentSessionWithCallerToken() {
		given(this.client.createPaymentSession(any(Consumer.class))).willReturn(
				CreatePaymentSessionResponse.builder().paymentSession(PaymentSession.builder().build()).build());
		AgentCorePaymentsTemplate template = new AgentCorePaymentsTemplate(this.client, ARN);

		template.createPaymentSession("user-1", "0.50", 15, "session-token-1");

		ArgumentCaptor<Consumer<CreatePaymentSessionRequest.Builder>> captor = ArgumentCaptor.forClass(Consumer.class);
		then(this.client).should().createPaymentSession(captor.capture());
		CreatePaymentSessionRequest.Builder builder = CreatePaymentSessionRequest.builder();
		captor.getValue().accept(builder);
		CreatePaymentSessionRequest request = builder.build();
		assertThat(request.clientToken()).isEqualTo("session-token-1");
		assertThat(request.userId()).isEqualTo("user-1");
		assertThat(request.limits().maxSpendAmount().value()).isEqualTo("0.50");
		assertThat(request.expiryTimeInMinutes()).isEqualTo(15);
	}

	@SuppressWarnings("unchecked")
	private void givenEthereumInstrument() {
		given(this.client.getPaymentInstrument(any(Consumer.class))).willReturn(GetPaymentInstrumentResponse.builder()
			.paymentInstrument(PaymentInstrument.builder()
				.paymentInstrumentId("instrument-1")
				.paymentInstrumentDetails(PaymentInstrumentDetails.builder()
					.embeddedCryptoWallet(EmbeddedCryptoWallet.builder().network(CryptoWalletNetwork.ETHEREUM).build())
					.build())
				.build())
			.build());
	}

	private static ProcessPaymentResponse paymentWithProof() {
		return ProcessPaymentResponse.builder()
			.paymentOutput(PaymentOutput.fromCryptoX402(CryptoX402PaymentOutput.builder()
				.version("1")
				.payload(Document.fromMap(Map.of("signature", Document.fromString("0xsig"))))
				.build()))
			.build();
	}

	@SuppressWarnings("unchecked")
	private List<ProcessPaymentRequest> capturedProcessPayments() {
		ArgumentCaptor<Consumer<ProcessPaymentRequest.Builder>> captor = ArgumentCaptor.forClass(Consumer.class);
		then(this.client).should(atLeastOnce()).processPayment(captor.capture());
		return captor.getAllValues().stream().map((consumer) -> {
			ProcessPaymentRequest.Builder builder = ProcessPaymentRequest.builder();
			consumer.accept(builder);
			return builder.build();
		}).toList();
	}

	@SuppressWarnings("unchecked")
	private ProcessPaymentRequest capturedProcessPayment() {
		ArgumentCaptor<Consumer<ProcessPaymentRequest.Builder>> captor = ArgumentCaptor.forClass(Consumer.class);
		then(this.client).should().processPayment(captor.capture());
		ProcessPaymentRequest.Builder builder = ProcessPaymentRequest.builder();
		captor.getValue().accept(builder);
		return builder.build();
	}

}
