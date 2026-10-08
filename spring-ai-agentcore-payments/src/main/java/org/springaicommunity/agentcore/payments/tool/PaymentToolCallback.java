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

import java.time.Duration;
import java.util.Map;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springaicommunity.agentcore.payments.core.AgentCorePaymentsTemplate;
import org.springaicommunity.agentcore.payments.core.PaymentException;
import org.springaicommunity.agentcore.payments.core.PaymentHeader;
import org.springaicommunity.agentcore.payments.core.PaymentRequired;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.execution.ToolExecutionException;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.http.HttpHeaders;
import org.springframework.util.Assert;

/**
 * Wraps a tool so that {@code 402 Payment Required} responses are paid automatically.
 * <p>
 * When the wrapped tool returns a result starting with {@value #PAYMENT_REQUIRED_MARKER}
 * followed by JSON {@code {"statusCode":402,"headers":{...},"body":...}}, the payment is
 * signed through AgentCore Payments, the payment header is added to the {@code headers}
 * object of the tool input, and the tool is called once more. The tool is paid at most
 * once per call: if it reports {@code 402} again, the server rejected the payment and the
 * call fails instead of paying twice.
 * <p>
 * Payment failures are thrown as {@link ToolExecutionException}. By default Spring AI
 * returns the message to the model; with
 * {@code spring.ai.tools.throw-exception-on-error=true} the application receives it.
 *
 * @author Andrei Shakirin
 */
public class PaymentToolCallback implements ToolCallback {

	/** Prefix of a tool result that reports a {@code 402 Payment Required} response. */
	public static final String PAYMENT_REQUIRED_MARKER = "PAYMENT_REQUIRED: ";

	private static final Logger logger = LoggerFactory.getLogger(PaymentToolCallback.class);

	private static final JsonMapper JSON_MAPPER = JsonMapper.builder().build();

	private final ToolCallback delegate;

	private final AgentCorePaymentsTemplate payments;

	private final PaymentContextResolver contextResolver;

	private final Duration postPaymentDelay;

	/**
	 * Creates a paying wrapper.
	 * @param delegate the tool to wrap
	 * @param payments the payments template used to sign payments
	 * @param contextResolver resolves user, instrument and session per call
	 * @param postPaymentDelay wait before calling the tool with the payment, so that the
	 * signed authorization is valid on chain
	 */
	public PaymentToolCallback(ToolCallback delegate, AgentCorePaymentsTemplate payments,
			PaymentContextResolver contextResolver, Duration postPaymentDelay) {
		Assert.notNull(delegate, "delegate must not be null");
		Assert.notNull(payments, "payments must not be null");
		Assert.notNull(contextResolver, "contextResolver must not be null");
		Assert.notNull(postPaymentDelay, "postPaymentDelay must not be null");
		this.delegate = delegate;
		this.payments = payments;
		this.contextResolver = contextResolver;
		this.postPaymentDelay = postPaymentDelay;
	}

	@Override
	public ToolDefinition getToolDefinition() {
		return this.delegate.getToolDefinition();
	}

	@Override
	public ToolMetadata getToolMetadata() {
		return this.delegate.getToolMetadata();
	}

	@Override
	public String call(String toolInput) {
		return this.call(toolInput, null);
	}

	@Override
	public String call(String toolInput, @Nullable ToolContext toolContext) {
		String result = this.delegate.call(toolInput, toolContext);
		PaymentRequired paymentRequired = this.paymentRequired(result);
		if (paymentRequired == null) {
			return result;
		}
		String toolName = this.getToolDefinition().name();
		logger.info("Tool '{}' requires payment, paying through AgentCore Payments", toolName);

		String paidInput;
		try {
			PaymentHeader header = this.payments.generatePaymentHeader(this.contextResolver.resolve(toolContext),
					paymentRequired);
			paidInput = withHeader(toolInput, header);
			sleep(this.postPaymentDelay);
		}
		catch (RuntimeException ex) {
			throw new ToolExecutionException(this.getToolDefinition(), ex);
		}

		String paidResult = this.delegate.call(paidInput, toolContext);
		PaymentRequired rejected = this.paymentRequired(paidResult);
		if (rejected != null) {
			throw new ToolExecutionException(this.getToolDefinition(), new PaymentException(
					"Payment for tool '" + toolName + "' was rejected by the server: " + rejected.body()));
		}
		return paidResult;
	}

	private @Nullable PaymentRequired paymentRequired(String result) {
		try {
			return extractPaymentRequired(result);
		}
		catch (PaymentException ex) {
			throw new ToolExecutionException(this.getToolDefinition(), ex);
		}
	}

	/**
	 * Parses a tool result carrying the {@value #PAYMENT_REQUIRED_MARKER} marker.
	 * @param result the tool result, raw or JSON-encoded as a string
	 * @return the 402 response, or {@code null} if the result does not require payment
	 */
	static @Nullable PaymentRequired extractPaymentRequired(@Nullable String result) {
		String text = unquote(result);
		if (text == null || !text.startsWith(PAYMENT_REQUIRED_MARKER)) {
			return null;
		}
		JsonNode node;
		try {
			node = JSON_MAPPER.readTree(text.substring(PAYMENT_REQUIRED_MARKER.length()));
		}
		catch (JacksonException ex) {
			throw new PaymentException("Tool result after " + PAYMENT_REQUIRED_MARKER.trim() + " is not valid JSON",
					ex);
		}
		HttpHeaders headers = new HttpHeaders();
		for (Map.Entry<String, JsonNode> header : node.path("headers").properties()) {
			if (header.getValue().isArray()) {
				header.getValue().forEach((value) -> headers.add(header.getKey(), value.asString()));
			}
			else {
				headers.add(header.getKey(), header.getValue().asString());
			}
		}
		JsonNode body = node.get("body");
		String bodyText = (body == null || body.isNull()) ? null
				: ((body.isString()) ? body.stringValue() : body.toString());
		return new PaymentRequired(node.path("statusCode").asInt(PaymentRequired.PAYMENT_REQUIRED_STATUS), headers,
				bodyText);
	}

	private static @Nullable String unquote(@Nullable String result) {
		if (result != null && result.startsWith("\"")) {
			try {
				JsonNode node = JSON_MAPPER.readTree(result);
				if (node.isString()) {
					return node.stringValue();
				}
			}
			catch (JacksonException ex) {
				// not a JSON string, use as is
			}
		}
		return result;
	}

	private static String withHeader(String toolInput, PaymentHeader header) {
		JsonNode input = JSON_MAPPER.readTree(toolInput);
		if (!(input instanceof ObjectNode object)) {
			throw new PaymentException("Cannot add the payment header: tool input is not a JSON object");
		}
		ObjectNode headers = (object.get("headers") instanceof ObjectNode existing) ? existing
				: object.putObject("headers");
		headers.put(header.name(), header.value());
		return JSON_MAPPER.writeValueAsString(object);
	}

	private static void sleep(Duration delay) {
		if (delay.isZero() || delay.isNegative()) {
			return;
		}
		try {
			Thread.sleep(delay.toMillis());
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new PaymentException("Interrupted while waiting to retry the paid request", ex);
		}
	}

}
