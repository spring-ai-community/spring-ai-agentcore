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

import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.Nullable;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;

/**
 * The payment tools for a {@code ChatClient}: the payment query tools and, if enabled,
 * the {@code paidHttpRequest} tool.
 * <p>
 * Usage: <pre class="code">
 * chatClient.prompt(question).toolCallbacks(paymentsTools.toolCallbacks())...
 * </pre>
 * <p>
 * Deliberately not a {@code ToolCallbackProvider}: Spring AI publishes every
 * {@code ToolCallbackProvider} bean, for example through the MCP server starter, which
 * would expose paying tools to outside clients. Applications add these tools to their own
 * chat clients explicitly.
 *
 * @author Andrei Shakirin
 */
public class AgentCorePaymentsTools {

	private final List<ToolCallback> toolCallbacks;

	/**
	 * Creates the tools.
	 * @param queryTools the payment query tools
	 * @param paidHttpRequestTool the paid HTTP request tool, or {@code null} if disabled
	 */
	public AgentCorePaymentsTools(PaymentQueryTools queryTools, @Nullable PaidHttpRequestTool paidHttpRequestTool) {
		List<ToolCallback> callbacks = new ArrayList<>();
		callbacks.add(FunctionToolCallback.builder("getPaymentInstrument", queryTools::getPaymentInstrument)
			.description(PaymentQueryTools.GET_PAYMENT_INSTRUMENT_DESCRIPTION)
			.inputType(PaymentQueryTools.EmptyRequest.class)
			.build());
		callbacks.add(FunctionToolCallback.builder("listPaymentInstruments", queryTools::listPaymentInstruments)
			.description(PaymentQueryTools.LIST_PAYMENT_INSTRUMENTS_DESCRIPTION)
			.inputType(PaymentQueryTools.EmptyRequest.class)
			.build());
		callbacks
			.add(FunctionToolCallback.builder("getPaymentInstrumentBalance", queryTools::getPaymentInstrumentBalance)
				.description(PaymentQueryTools.GET_PAYMENT_INSTRUMENT_BALANCE_DESCRIPTION)
				.inputType(PaymentQueryTools.BalanceRequest.class)
				.build());
		callbacks.add(FunctionToolCallback.builder("getPaymentSession", queryTools::getPaymentSession)
			.description(PaymentQueryTools.GET_PAYMENT_SESSION_DESCRIPTION)
			.inputType(PaymentQueryTools.EmptyRequest.class)
			.build());
		if (paidHttpRequestTool != null) {
			callbacks.add(FunctionToolCallback.builder(PaidHttpRequestTool.NAME, paidHttpRequestTool::execute)
				.description(PaidHttpRequestTool.DESCRIPTION)
				.inputType(PaidHttpRequestTool.Request.class)
				.toolCallResultConverter((result, returnType) -> String.valueOf(result))
				.build());
		}
		this.toolCallbacks = List.copyOf(callbacks);
	}

	/**
	 * Returns the tools.
	 * @return the tool callbacks
	 */
	public ToolCallback[] toolCallbacks() {
		return this.toolCallbacks.toArray(ToolCallback[]::new);
	}

}
