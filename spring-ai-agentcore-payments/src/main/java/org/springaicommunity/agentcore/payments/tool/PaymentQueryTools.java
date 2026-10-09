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

package org.springaicommunity.agentcore.payments.tool;

import java.util.List;
import java.util.Locale;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import org.jspecify.annotations.Nullable;
import org.springaicommunity.agentcore.payments.core.AgentCorePaymentsTemplate;
import org.springaicommunity.agentcore.payments.core.PaymentContext;
import software.amazon.awssdk.services.bedrockagentcore.model.Amount;
import software.amazon.awssdk.services.bedrockagentcore.model.BlockchainChainId;
import software.amazon.awssdk.services.bedrockagentcore.model.GetPaymentInstrumentBalanceResponse;
import software.amazon.awssdk.services.bedrockagentcore.model.InstrumentBalanceToken;
import software.amazon.awssdk.services.bedrockagentcore.model.PaymentInstrument;
import software.amazon.awssdk.services.bedrockagentcore.model.PaymentSession;
import software.amazon.awssdk.services.bedrockagentcore.model.TokenBalance;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.util.Assert;

/**
 * Tools that let the model inspect its payment instrument and remaining session budget.
 * They only read the user, instrument and session of the resolved {@link PaymentContext};
 * the model cannot name other instruments or sessions.
 *
 * @author Andrei Shakirin
 */
public class PaymentQueryTools {

	/** Default description of the getPaymentInstrument tool. */
	public static final String GET_PAYMENT_INSTRUMENT_DESCRIPTION = "Get a payment instrument (wallet): network, address, status.";

	/** Default description of the listPaymentInstruments tool. */
	public static final String LIST_PAYMENT_INSTRUMENTS_DESCRIPTION = "List the payment instruments (wallets) of the current user.";

	/** Default description of the getPaymentInstrumentBalance tool. */
	public static final String GET_PAYMENT_INSTRUMENT_BALANCE_DESCRIPTION = "Get the token balance of a wallet on a blockchain.";

	/** Default description of the getPaymentSession tool. */
	public static final String GET_PAYMENT_SESSION_DESCRIPTION = "Get the payment session: limit, remaining budget, expiry.";

	private static final String CHAIN_DESCRIPTION = "BASE_SEPOLIA, BASE, ETHEREUM, SOLANA or SOLANA_DEVNET";

	private final AgentCorePaymentsTemplate payments;

	private final PaymentContextResolver contextResolver;

	public PaymentQueryTools(AgentCorePaymentsTemplate payments, PaymentContextResolver contextResolver) {
		Assert.notNull(payments, "payments must not be null");
		Assert.notNull(contextResolver, "contextResolver must not be null");
		this.payments = payments;
		this.contextResolver = contextResolver;
	}

	public InstrumentInfo getPaymentInstrument(EmptyRequest request, @Nullable ToolContext toolContext) {
		PaymentContext context = this.contextResolver.resolve(toolContext);
		return InstrumentInfo
			.from(this.payments.getPaymentInstrument(context.requireUserId(), context.requirePaymentInstrumentId()));
	}

	public List<InstrumentInfo> listPaymentInstruments(EmptyRequest request, @Nullable ToolContext toolContext) {
		String userId = this.contextResolver.resolve(toolContext).requireUserId();
		return this.payments.listPaymentInstruments(userId)
			.stream()
			.map((summary) -> new InstrumentInfo(summary.paymentInstrumentId(), summary.paymentConnectorId(),
					summary.paymentInstrumentTypeAsString(), summary.statusAsString(), null, null))
			.toList();
	}

	public BalanceInfo getPaymentInstrumentBalance(BalanceRequest request, @Nullable ToolContext toolContext) {
		PaymentContext context = this.contextResolver.resolve(toolContext);
		String instrumentId = context.requirePaymentInstrumentId();
		BlockchainChainId chain = BlockchainChainId.fromValue(request.chain().trim().toUpperCase(Locale.ROOT));
		InstrumentBalanceToken token = InstrumentBalanceToken
			.fromValue((request.token() != null) ? request.token().trim().toUpperCase(Locale.ROOT) : "USDC");
		Assert.isTrue(chain != BlockchainChainId.UNKNOWN_TO_SDK_VERSION, "Unsupported chain: " + request.chain());
		Assert.isTrue(token != InstrumentBalanceToken.UNKNOWN_TO_SDK_VERSION, "Unsupported token: " + request.token());
		GetPaymentInstrumentBalanceResponse balance = this.payments.getPaymentInstrumentBalance(context.requireUserId(),
				instrumentId, chain, token);
		TokenBalance tokenBalance = balance.tokenBalance();
		if (tokenBalance == null) {
			return new BalanceInfo(balance.paymentInstrumentId(), chain.toString(), token.toString(), null, null);
		}
		return new BalanceInfo(balance.paymentInstrumentId(), tokenBalance.chainAsString(),
				tokenBalance.tokenAsString(), tokenBalance.amount(), tokenBalance.decimals());
	}

	public SessionInfo getPaymentSession(EmptyRequest request, @Nullable ToolContext toolContext) {
		PaymentContext context = this.contextResolver.resolve(toolContext);
		return SessionInfo
			.from(this.payments.getPaymentSession(context.requireUserId(), context.requirePaymentSessionId()));
	}

	/**
	 * Input of tools without parameters.
	 */
	public record EmptyRequest() {
	}

	/**
	 * Input of the getPaymentInstrumentBalance tool.
	 *
	 * @param chain the chain, for example BASE_SEPOLIA, BASE, SOLANA, SOLANA_DEVNET
	 * @param token the token, USDC if not set
	 */
	public record BalanceRequest(
			@JsonProperty(required = true) @JsonPropertyDescription(CHAIN_DESCRIPTION) String chain,
			@JsonPropertyDescription("Token, USDC if not set") @Nullable String token) {
	}

	/**
	 * Payment instrument details returned to the model.
	 *
	 * @param paymentInstrumentId the instrument id
	 * @param paymentConnectorId the connector id
	 * @param type the instrument type
	 * @param status the instrument status
	 * @param network the wallet network
	 * @param walletAddress the wallet address
	 */
	public record InstrumentInfo(String paymentInstrumentId, String paymentConnectorId, @Nullable String type,
			@Nullable String status, @Nullable String network, @Nullable String walletAddress) {

		static InstrumentInfo from(PaymentInstrument instrument) {
			String walletNetwork = null;
			String address = null;
			if (instrument.paymentInstrumentDetails() != null
					&& instrument.paymentInstrumentDetails().embeddedCryptoWallet() != null) {
				walletNetwork = instrument.paymentInstrumentDetails().embeddedCryptoWallet().networkAsString();
				address = instrument.paymentInstrumentDetails().embeddedCryptoWallet().walletAddress();
			}
			return new InstrumentInfo(instrument.paymentInstrumentId(), instrument.paymentConnectorId(),
					instrument.paymentInstrumentTypeAsString(), instrument.statusAsString(), walletNetwork, address);
		}

	}

	/**
	 * Token balance returned to the model.
	 *
	 * @param paymentInstrumentId the instrument id
	 * @param chain the chain
	 * @param token the token
	 * @param amount the amount in the token's smallest unit
	 * @param decimals the token decimals
	 */
	public record BalanceInfo(String paymentInstrumentId, @Nullable String chain, @Nullable String token,
			@Nullable String amount, @Nullable Integer decimals) {
	}

	/**
	 * Payment session details returned to the model.
	 *
	 * @param paymentSessionId the session id
	 * @param maxSpendAmount the spending limit
	 * @param availableSpendAmount the remaining budget
	 * @param currency the currency
	 * @param expiryTimeInMinutes the session lifetime
	 * @param createdAt the creation time
	 */
	public record SessionInfo(String paymentSessionId, @Nullable String maxSpendAmount,
			@Nullable String availableSpendAmount, @Nullable String currency, @Nullable Integer expiryTimeInMinutes,
			@Nullable String createdAt) {

		static SessionInfo from(PaymentSession session) {
			Amount max = (session.limits() != null) ? session.limits().maxSpendAmount() : null;
			Amount available = (session.availableLimits() != null) ? session.availableLimits().availableSpendAmount()
					: null;
			String maxCurrency = (max != null) ? max.currencyAsString() : null;
			return new SessionInfo(session.paymentSessionId(), (max != null) ? max.value() : null,
					(available != null) ? available.value() : null, maxCurrency, session.expiryTimeInMinutes(),
					(session.createdAt() != null) ? session.createdAt().toString() : null);
		}

	}

}
