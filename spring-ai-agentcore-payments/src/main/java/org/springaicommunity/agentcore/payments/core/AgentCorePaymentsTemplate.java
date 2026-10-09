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

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.regex.Pattern;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.bedrockagentcore.BedrockAgentCoreClient;
import software.amazon.awssdk.services.bedrockagentcore.model.Amount;
import software.amazon.awssdk.services.bedrockagentcore.model.BlockchainChainId;
import software.amazon.awssdk.services.bedrockagentcore.model.CreatePaymentInstrumentRequest;
import software.amazon.awssdk.services.bedrockagentcore.model.CryptoX402PaymentInput;
import software.amazon.awssdk.services.bedrockagentcore.model.CryptoX402PaymentOutput;
import software.amazon.awssdk.services.bedrockagentcore.model.Currency;
import software.amazon.awssdk.services.bedrockagentcore.model.GetPaymentInstrumentBalanceResponse;
import software.amazon.awssdk.services.bedrockagentcore.model.InstrumentBalanceToken;
import software.amazon.awssdk.services.bedrockagentcore.model.PaymentInput;
import software.amazon.awssdk.services.bedrockagentcore.model.PaymentInstrument;
import software.amazon.awssdk.services.bedrockagentcore.model.PaymentInstrumentSummary;
import software.amazon.awssdk.services.bedrockagentcore.model.PaymentSession;
import software.amazon.awssdk.services.bedrockagentcore.model.PaymentType;
import software.amazon.awssdk.services.bedrockagentcore.model.ProcessPaymentRequest;
import software.amazon.awssdk.services.bedrockagentcore.model.ProcessPaymentResponse;
import software.amazon.awssdk.services.bedrockagentcore.model.SessionLimits;
import software.amazon.awssdk.services.bedrockagentcore.model.ValidationException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import org.springframework.util.Assert;

/**
 * Thin wrapper over the Amazon Bedrock AgentCore Payments data plane, bound to one
 * payment manager. Besides payment instruments and sessions it turns an HTTP
 * {@code 402 Payment Required} response into the header that pays for the retried request
 * ({@link #generatePaymentHeader}). Supports the x402 protocol, versions 1 and 2.
 * <p>
 * Thread-safe. AWS SDK exceptions propagate unchanged, except two
 * {@code ValidationException}s of {@code ProcessPayment}, recognized by their message
 * (the service sends no reason code for them): {@code "Insufficient budget for session
 * ..."} becomes {@link InsufficientBudgetException} and {@code "Payment session not
 * found: ..."}, which the service also returns for an expired session, becomes
 * {@link PaymentSessionNotFoundException}.
 *
 * @author Andrei Shakirin
 */
public class AgentCorePaymentsTemplate {

	private static final Logger logger = LoggerFactory.getLogger(AgentCorePaymentsTemplate.class);

	private static final Pattern PERMIT2_ALLOWANCE_LIMIT = Pattern.compile("[0-9]{1,78}");

	/** Chains on which a wallet of a payment instrument network holds balances. */
	private static final Map<String, List<BlockchainChainId>> CHAINS_BY_NETWORK = Map.of("ETHEREUM",
			List.of(BlockchainChainId.BASE, BlockchainChainId.BASE_SEPOLIA, BlockchainChainId.ETHEREUM), "SOLANA",
			List.of(BlockchainChainId.SOLANA, BlockchainChainId.SOLANA_DEVNET));

	/**
	 * Start of the ProcessPayment error message when the session budget is exhausted, for
	 * example {@code "Insufficient budget for session <id>. Pending amount: ..."}
	 * (observed 2026-10-09).
	 */
	static final String INSUFFICIENT_BUDGET_MESSAGE = "insufficient budget for session";

	/**
	 * Start of the ProcessPayment error message for an expired, deleted or unknown
	 * session: {@code "Payment session not found: <id>"} (observed 2026-10-09).
	 */
	static final String SESSION_NOT_FOUND_MESSAGE = "payment session not found";

	private final BedrockAgentCoreClient client;

	private final String paymentManagerArn;

	private final @Nullable String agentName;

	private final List<String> networkPreferences;

	private final @Nullable String permit2AllowanceLimit;

	// Exact decimals: a double would lose digits of an amount, and an out-of-range number
	// such as 1e348 would become Infinity, which cannot be sent to the service.
	private final JsonMapper jsonMapper = JsonMapper.builder()
		.enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
		.build();

	/** Wallet network per payment instrument; it does not change for an instrument. */
	private final Cache<String, String> instrumentNetworks = Caffeine.newBuilder().maximumSize(1_000).build();

	/**
	 * Creates a template with the default network preferences.
	 * @param client the AgentCore data plane client
	 * @param paymentManagerArn the payment manager ARN
	 */
	public AgentCorePaymentsTemplate(BedrockAgentCoreClient client, String paymentManagerArn) {
		this(client, paymentManagerArn, null, NetworkPreferences.DEFAULT, null);
	}

	/**
	 * Creates a template.
	 * @param client the AgentCore data plane client
	 * @param paymentManagerArn the payment manager ARN
	 * @param agentName agent name sent with data plane calls, may be {@code null}
	 * @param networkPreferences network identifiers used to choose between payment
	 * options, most preferred first
	 * @param permit2AllowanceLimit the Permit2 allowance for the x402 {@code upto}
	 * scheme, in the asset's smallest unit; {@code null} to not grant one
	 */
	public AgentCorePaymentsTemplate(BedrockAgentCoreClient client, String paymentManagerArn,
			@Nullable String agentName, List<String> networkPreferences, @Nullable String permit2AllowanceLimit) {
		Assert.notNull(client, "client must not be null");
		Assert.hasText(paymentManagerArn, "paymentManagerArn must not be empty");
		Assert.notNull(networkPreferences, "networkPreferences must not be null");
		Assert.isTrue(permit2AllowanceLimit == null || isValidPermit2AllowanceLimit(permit2AllowanceLimit),
				"permit2AllowanceLimit must be a positive integer of 1-78 digits");
		this.client = client;
		this.paymentManagerArn = paymentManagerArn;
		this.agentName = agentName;
		this.networkPreferences = List.copyOf(networkPreferences);
		this.permit2AllowanceLimit = permit2AllowanceLimit;
	}

	/**
	 * Creates a payment instrument. The payment manager ARN, agent name and an
	 * idempotency token are pre-filled. The end user must fund the instrument and grant
	 * signing permission (for Coinbase via the returned {@code redirectUrl}) before it
	 * can pay.
	 * @param request customizes the request, for example user id, connector id, type and
	 * details
	 * @return the created instrument
	 */
	public PaymentInstrument createPaymentInstrument(Consumer<CreatePaymentInstrumentRequest.Builder> request) {
		Assert.notNull(request, "request must not be null");
		return this.client.createPaymentInstrument((builder) -> {
			builder.paymentManagerArn(this.paymentManagerArn)
				.agentName(this.agentName)
				.clientToken(UUID.randomUUID().toString());
			request.accept(builder);
		}).paymentInstrument();
	}

	/**
	 * Returns a payment instrument.
	 * @param userId user owning the instrument
	 * @param paymentInstrumentId instrument id
	 * @return the instrument
	 */
	public PaymentInstrument getPaymentInstrument(String userId, String paymentInstrumentId) {
		Assert.hasText(userId, "userId must not be empty");
		Assert.hasText(paymentInstrumentId, "paymentInstrumentId must not be empty");
		return this.client
			.getPaymentInstrument((builder) -> builder.paymentManagerArn(this.paymentManagerArn)
				.agentName(this.agentName)
				.userId(userId)
				.paymentInstrumentId(paymentInstrumentId))
			.paymentInstrument();
	}

	/**
	 * Lists all payment instruments of a user.
	 * @param userId user owning the instruments
	 * @return instrument summaries, all pages
	 */
	public List<PaymentInstrumentSummary> listPaymentInstruments(String userId) {
		Assert.hasText(userId, "userId must not be empty");
		return this.client.listPaymentInstrumentsPaginator(
				(builder) -> builder.paymentManagerArn(this.paymentManagerArn).agentName(this.agentName).userId(userId))
			.paymentInstruments()
			.stream()
			.toList();
	}

	/**
	 * Returns the token balance of a payment instrument on a chain.
	 * @param userId user owning the instrument
	 * @param paymentInstrumentId instrument id
	 * @param chain chain to query, for example {@link BlockchainChainId#BASE_SEPOLIA}
	 * @param token token to query, for example {@link InstrumentBalanceToken#USDC}
	 * @return the balance
	 */
	public GetPaymentInstrumentBalanceResponse getPaymentInstrumentBalance(String userId, String paymentInstrumentId,
			BlockchainChainId chain, InstrumentBalanceToken token) {
		Assert.notNull(chain, "chain must not be null");
		Assert.notNull(token, "token must not be null");
		PaymentInstrument instrument = this.getPaymentInstrument(userId, paymentInstrumentId);
		List<BlockchainChainId> chains = CHAINS_BY_NETWORK.get(instrumentNetwork(instrument));
		if (chains != null && !chains.contains(chain)) {
			throw new PaymentException("Chain " + chain + " does not match the network of payment instrument "
					+ paymentInstrumentId + "; use one of " + chains);
		}
		String paymentConnectorId = instrument.paymentConnectorId();
		return this.client.getPaymentInstrumentBalance((builder) -> builder.paymentManagerArn(this.paymentManagerArn)
			.agentName(this.agentName)
			.userId(userId)
			.paymentConnectorId(paymentConnectorId)
			.paymentInstrumentId(paymentInstrumentId)
			.chain(chain)
			.token(token));
	}

	/**
	 * Creates a payment session that limits how much can be spent and for how long.
	 * @param userId user the session belongs to
	 * @param maxSpendAmountUsd maximum amount in USD, for example {@code "1.00"}
	 * @param expiryTimeInMinutes session lifetime, 15 to 480 minutes
	 * @return the created session
	 */
	public PaymentSession createPaymentSession(String userId, String maxSpendAmountUsd, int expiryTimeInMinutes) {
		return this.createPaymentSession(userId, maxSpendAmountUsd, expiryTimeInMinutes, UUID.randomUUID().toString());
	}

	/**
	 * Creates a payment session with an idempotency token chosen by the caller. Reuse the
	 * same token only when retrying the creation of this one session.
	 * @param userId user the session belongs to
	 * @param maxSpendAmountUsd maximum amount in USD, for example {@code "1.00"}
	 * @param expiryTimeInMinutes session lifetime, 15 to 480 minutes
	 * @param clientToken the idempotency token of this session
	 * @return the created session
	 */
	public PaymentSession createPaymentSession(String userId, String maxSpendAmountUsd, int expiryTimeInMinutes,
			String clientToken) {
		Assert.hasText(clientToken, "clientToken must not be empty");
		Assert.hasText(userId, "userId must not be empty");
		Assert.hasText(maxSpendAmountUsd, "maxSpendAmountUsd must not be empty");
		return this.client
			.createPaymentSession((builder) -> builder.paymentManagerArn(this.paymentManagerArn)
				.agentName(this.agentName)
				.userId(userId)
				.limits(SessionLimits.builder()
					.maxSpendAmount(Amount.builder().value(maxSpendAmountUsd).currency(Currency.USD).build())
					.build())
				.expiryTimeInMinutes(expiryTimeInMinutes)
				.clientToken(clientToken))
			.paymentSession();
	}

	/**
	 * Returns a payment session including its remaining budget ({@code availableLimits}).
	 * @param userId user the session belongs to
	 * @param paymentSessionId session id
	 * @return the session
	 */
	public PaymentSession getPaymentSession(String userId, String paymentSessionId) {
		Assert.hasText(userId, "userId must not be empty");
		Assert.hasText(paymentSessionId, "paymentSessionId must not be empty");
		return this.client
			.getPaymentSession((builder) -> builder.paymentManagerArn(this.paymentManagerArn)
				.agentName(this.agentName)
				.userId(userId)
				.paymentSessionId(paymentSessionId))
			.paymentSession();
	}

	/**
	 * Deletes a payment session.
	 * @param userId user the session belongs to
	 * @param paymentSessionId session id
	 */
	public void deletePaymentSession(String userId, String paymentSessionId) {
		Assert.hasText(userId, "userId must not be empty");
		Assert.hasText(paymentSessionId, "paymentSessionId must not be empty");
		this.client.deletePaymentSession((builder) -> builder.paymentManagerArn(this.paymentManagerArn)
			.userId(userId)
			.paymentSessionId(paymentSessionId));
	}

	/**
	 * Calls {@code ProcessPayment}. The payment manager ARN, agent name and an
	 * idempotency token are pre-filled. Most callers should use
	 * {@link #generatePaymentHeader}.
	 * @param request customizes the request
	 * @return the payment result with the payment proof
	 * @throws InsufficientBudgetException if the session budget is exhausted
	 * @throws PaymentSessionNotFoundException if the session has expired, was deleted or
	 * never existed
	 */
	public ProcessPaymentResponse processPayment(Consumer<ProcessPaymentRequest.Builder> request) {
		Assert.notNull(request, "request must not be null");
		try {
			return this.client.processPayment((builder) -> {
				builder.paymentManagerArn(this.paymentManagerArn)
					.agentName(this.agentName)
					.clientToken(UUID.randomUUID().toString());
				request.accept(builder);
			});
		}
		catch (ValidationException ex) {
			// The service reports these cases only in the message (no reason code). The
			// messages name the session id, so they stay in the cause: tool callers
			// return
			// the exception message to the model.
			String message = String.valueOf(ex.getMessage()).toLowerCase(Locale.ROOT);
			if (message.startsWith(INSUFFICIENT_BUDGET_MESSAGE)) {
				throw new InsufficientBudgetException("Insufficient payment session budget", ex);
			}
			if (message.startsWith(SESSION_NOT_FOUND_MESSAGE)) {
				throw new PaymentSessionNotFoundException(
						"Payment session not found: it has expired, was deleted or never existed", ex);
			}
			logger.debug("ProcessPayment validation error not mapped to a payment exception: {}", ex.getMessage());
			throw ex;
		}
	}

	/**
	 * Pays for a resource that answered {@code 402 Payment Required} and returns the
	 * header to send when retrying the request. Selects the payment option matching the
	 * instrument's network and the configured preferences, then signs it through
	 * {@code ProcessPayment} within the session budget.
	 * @param context user, payment instrument and payment session to pay with
	 * @param response the {@code 402} response
	 * @return {@code X-PAYMENT} (x402 v1) or {@code PAYMENT-SIGNATURE} (x402 v2) header
	 * @throws PaymentConfigurationException if user, instrument or session is missing
	 * @throws PaymentException if the response cannot be paid
	 */
	public PaymentHeader generatePaymentHeader(PaymentContext context, PaymentRequired response) {
		return this.generatePaymentHeader(context, response, UUID.randomUUID().toString());
	}

	/**
	 * Pays for a resource like
	 * {@link #generatePaymentHeader(PaymentContext, PaymentRequired)} with an idempotency
	 * token chosen by the caller. Reuse the same token only within your own retry loop
	 * for one purchase (for example after a timeout); every new purchase needs a new
	 * token, otherwise AgentCore Payments returns the earlier payment and the merchant
	 * rejects it as a replay.
	 * @param context user, payment instrument and payment session to pay with
	 * @param response the {@code 402} response
	 * @param clientToken the idempotency token of this purchase
	 * @return {@code X-PAYMENT} (x402 v1) or {@code PAYMENT-SIGNATURE} (x402 v2) header
	 * @throws PaymentConfigurationException if user, instrument or session is missing
	 * @throws PaymentException if the response cannot be paid
	 */
	public PaymentHeader generatePaymentHeader(PaymentContext context, PaymentRequired response, String clientToken) {
		Assert.hasText(clientToken, "clientToken must not be empty");
		Assert.notNull(context, "context must not be null");
		Assert.notNull(response, "response must not be null");
		if (response.statusCode() != PaymentRequired.PAYMENT_REQUIRED_STATUS) {
			throw new PaymentException("Expected status code 402, got " + response.statusCode());
		}
		String userId = context.requireUserId();
		String paymentInstrumentId = context.requirePaymentInstrumentId();
		String paymentSessionId = context.requirePaymentSessionId();

		X402PaymentRequirements requirements = X402PaymentRequirements.parse(response, this.jsonMapper);
		String instrumentNetwork = this.instrumentNetworks.get(paymentInstrumentId,
				(id) -> instrumentNetwork(this.getPaymentInstrument(userId, id)));
		try {
			return this.pay(requirements, instrumentNetwork, userId, paymentInstrumentId, paymentSessionId,
					clientToken);
		}
		catch (InsufficientBudgetException | PaymentSessionNotFoundException ex) {
			throw ex;
		}
		catch (RuntimeException ex) {
			// the instrument may have been recreated with another network
			this.instrumentNetworks.invalidate(paymentInstrumentId);
			throw ex;
		}
	}

	private PaymentHeader pay(X402PaymentRequirements requirements, String instrumentNetwork, String userId,
			String paymentInstrumentId, String paymentSessionId, String clientToken) {
		ObjectNode accept = requirements.selectAccept(instrumentNetwork, this.networkPreferences);

		CryptoX402PaymentInput.Builder input = CryptoX402PaymentInput.builder()
			.version(String.valueOf(requirements.version()))
			.payload(DocumentConverter.toDocument(accept));
		if (this.permit2AllowanceLimit != null && X402PaymentRequirements.isUptoScheme(accept)) {
			input.permit2AllowanceLimit(this.permit2AllowanceLimit);
		}

		logger.debug("Paying x402 v{} requirement on network {} for user {}", requirements.version(),
				accept.get("network"), userId);
		ProcessPaymentResponse payment = this.processPayment((builder) -> builder.userId(userId)
			.clientToken(clientToken)
			.paymentSessionId(paymentSessionId)
			.paymentInstrumentId(paymentInstrumentId)
			.paymentType(PaymentType.CRYPTO_X402)
			.paymentInput(PaymentInput.fromCryptoX402(input.build())));

		CryptoX402PaymentOutput output = (payment.paymentOutput() != null) ? payment.paymentOutput().cryptoX402()
				: null;
		if (output == null || output.payload() == null) {
			throw new PaymentException("ProcessPayment returned no x402 payment proof (status " + payment.status()
					+ ", processPaymentId " + payment.processPaymentId() + ")");
		}
		JsonNode proof = DocumentConverter.toJsonNode(output.payload());
		return requirements.buildHeader(accept, proof);
	}

	private static String instrumentNetwork(PaymentInstrument instrument) {
		if (instrument.paymentInstrumentDetails() == null
				|| instrument.paymentInstrumentDetails().embeddedCryptoWallet() == null
				|| instrument.paymentInstrumentDetails().embeddedCryptoWallet().networkAsString() == null) {
			throw new PaymentException(
					"Payment instrument " + instrument.paymentInstrumentId() + " has no crypto wallet network");
		}
		return instrument.paymentInstrumentDetails().embeddedCryptoWallet().networkAsString();
	}

	private static boolean isValidPermit2AllowanceLimit(String value) {
		return PERMIT2_ALLOWANCE_LIMIT.matcher(value).matches() && !value.chars().allMatch((c) -> c == '0');
	}

}
