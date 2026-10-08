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
import java.util.Arrays;

import org.springaicommunity.agentcore.payments.core.AgentCorePaymentsTemplate;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.util.Assert;

/**
 * Opts tools into automatic payment by wrapping them in {@link PaymentToolCallback}.
 * <pre class="code">
 * chatClient.prompt()
 *     .toolCallbacks(paymentsToolCallbacks.wrap(ToolCallbacks.from(myPaidApiTools)))
 *     ...
 * </pre>
 *
 * @author Andrei Shakirin
 */
public class AgentCorePaymentsToolCallbacks {

	private final AgentCorePaymentsTemplate payments;

	private final PaymentContextResolver contextResolver;

	private final Duration postPaymentDelay;

	public AgentCorePaymentsToolCallbacks(AgentCorePaymentsTemplate payments, PaymentContextResolver contextResolver,
			Duration postPaymentDelay) {
		Assert.notNull(payments, "payments must not be null");
		Assert.notNull(contextResolver, "contextResolver must not be null");
		Assert.notNull(postPaymentDelay, "postPaymentDelay must not be null");
		this.payments = payments;
		this.contextResolver = contextResolver;
		this.postPaymentDelay = postPaymentDelay;
	}

	/**
	 * Wraps a tool so that it pays for {@code 402} responses.
	 * @param toolCallback the tool to wrap
	 * @return the paying tool
	 */
	public ToolCallback wrap(ToolCallback toolCallback) {
		if (toolCallback instanceof PaymentToolCallback) {
			return toolCallback;
		}
		return new PaymentToolCallback(toolCallback, this.payments, this.contextResolver, this.postPaymentDelay);
	}

	/**
	 * Wraps tools so that they pay for {@code 402} responses.
	 * @param toolCallbacks the tools to wrap
	 * @return the paying tools
	 */
	public ToolCallback[] wrap(ToolCallback... toolCallbacks) {
		return Arrays.stream(toolCallbacks).map(this::wrap).toArray(ToolCallback[]::new);
	}

	/**
	 * Wraps all tools of a provider so that they pay for {@code 402} responses.
	 * <p>
	 * Do not register the result as a bean: Spring AI publishes
	 * {@code ToolCallbackProvider} beans, for example through the MCP server starter,
	 * which would offer the paying tools to outside clients. Pass it to the chat client
	 * directly.
	 * @param provider the tools to wrap
	 * @return a provider of the paying tools
	 */
	public ToolCallbackProvider wrap(ToolCallbackProvider provider) {
		return ToolCallbackProvider.from(this.wrap(provider.getToolCallbacks()));
	}

}
