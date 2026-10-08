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


package com.unicorn.payments;

import java.util.Map;

import org.springaicommunity.agentcore.annotation.AgentCoreInvocation;
import org.springaicommunity.agentcore.context.AgentCoreContext;
import org.springaicommunity.agentcore.context.AgentCoreHeaders;
import org.springaicommunity.agentcore.payments.core.PaymentContext;
import org.springaicommunity.agentcore.payments.core.PaymentSessionRegistry;
import org.springaicommunity.agentcore.payments.tool.AgentCorePaymentsTools;
import org.jspecify.annotations.Nullable;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.RestController;

/**
 * AgentCore Runtime agent that pays for x402 APIs. The request selects how it pays:
 * <ul>
 * <li>{@code interceptor}: domain tool on a RestClient with the payments interceptor
 * ({@link MarketRecapTools})</li>
 * <li>{@code tool}: the generic {@code paidHttpRequest} tool and payment query tools; the
 * prompt names the URL</li>
 * <li>{@code custom}: domain tool on the JDK HttpClient that pays through
 * {@code AgentCorePaymentsTemplate} ({@link FortuneTools})</li>
 * </ul>
 * All three pay from the budget of the conversation, created by
 * {@link PaymentSessionRegistry} per user and AgentCore Runtime session.
 */
@RestController
public class PaymentsAgent {

	private final ChatClient chatClient;

	private final PaymentSessionRegistry paymentSessions;

	private final MarketRecapTools marketRecapTools;

	private final FortuneTools fortuneTools;

	private final AgentCorePaymentsTools paymentsTools;

	private final @Nullable String defaultUserId;

	private final @Nullable String defaultSessionId;

	PaymentsAgent(ChatClient.Builder chatClient, PaymentSessionRegistry paymentSessions,
			MarketRecapTools marketRecapTools, FortuneTools fortuneTools,
			AgentCorePaymentsTools paymentsTools,
			@Value("${app.payments.default-user-id:#{null}}") @Nullable String defaultUserId,
			@Value("${app.payments.default-session-id:#{null}}") @Nullable String defaultSessionId) {
		this.chatClient = chatClient.build();
		this.paymentSessions = paymentSessions;
		this.marketRecapTools = marketRecapTools;
		this.fortuneTools = fortuneTools;
		this.paymentsTools = paymentsTools;
		this.defaultUserId = defaultUserId;
		this.defaultSessionId = defaultSessionId;
	}

	@AgentCoreInvocation
	public String invoke(PromptRequest request, AgentCoreContext context) {
		String userId = this.required(context, AgentCoreHeaders.USER_ID, this.defaultUserId);
		String runtimeSessionId = this.required(context, AgentCoreHeaders.SESSION_ID, this.defaultSessionId);

		// one budget per conversation: created on the first invocation, reused afterwards
		String paymentSessionId = this.paymentSessions.getOrCreate(userId, runtimeSessionId);

		return this.chatClient.prompt()
			.user(request.prompt())
			.toolCallbacks(this.tools(request.approach()))
			.toolContext(Map.of(PaymentContext.USER_ID_KEY, userId, PaymentContext.PAYMENT_SESSION_ID_KEY,
					paymentSessionId))
			.call()
			.content();
	}

	// header value, or the local-profile default; never a shared fallback in a deployment
	private String required(AgentCoreContext context, String header, @Nullable String localDefault) {
		String value = valueOrDefault(context.getHeader(header), localDefault);
		if (value == null) {
			throw new IllegalStateException(
					"No AgentCore Runtime header " + header + "; for local runs start with the 'local' profile");
		}
		return value;
	}

	// The request chooses the approach to compare them; a production agent uses a fixed set of tools.
	private ToolCallback[] tools(@Nullable String approach) {
		return switch (valueOrDefault(approach, "interceptor")) {
			case "interceptor" -> ToolCallbacks.from(this.marketRecapTools);
			case "tool" -> this.paymentsTools.toolCallbacks();
			case "custom" -> ToolCallbacks.from(this.fortuneTools);
			default -> throw new IllegalArgumentException(
					"Unknown approach '" + approach + "', use interceptor, tool or custom");
		};
	}

	private static @Nullable String valueOrDefault(@Nullable String value, @Nullable String defaultValue) {
		return (value != null && !value.isBlank()) ? value : defaultValue;
	}

}
