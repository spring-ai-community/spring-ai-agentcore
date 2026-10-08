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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springaicommunity.agentcore.payments.core.AgentCorePaymentsTemplate;
import org.springaicommunity.agentcore.payments.core.InsufficientBudgetException;
import org.springaicommunity.agentcore.payments.core.PaymentContext;
import org.springaicommunity.agentcore.payments.core.PaymentHeader;
import org.springaicommunity.agentcore.payments.core.PaymentRequired;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.execution.ToolExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;

/**
 * Tests for {@link PaymentToolCallback}.
 *
 * @author Andrei Shakirin
 */
class PaymentToolCallbackTests {

	private static final String PAYMENT_REQUIRED = PaymentToolCallback.PAYMENT_REQUIRED_MARKER
			+ "{\"statusCode\":402,\"headers\":{\"content-type\":\"application/json\"},"
			+ "\"body\":{\"x402Version\":1,\"accepts\":[]}}";

	private final AgentCorePaymentsTemplate payments = mock(AgentCorePaymentsTemplate.class);

	private final PaymentContextResolver resolver = new DefaultPaymentContextResolver(
			new PaymentContext("user-1", "instrument-1", "session-1"));

	@Test
	void passesThroughResultsThatDoNotRequirePayment() {
		RecordingTool tool = new RecordingTool((input) -> "{\"statusCode\":200}");

		String result = this.paying(tool).call("{\"url\":\"https://example.com\"}");

		assertThat(result).isEqualTo("{\"statusCode\":200}");
		then(this.payments).shouldHaveNoInteractions();
	}

	@Test
	void paysAndRetriesWithPaymentHeaderInToolInput() {
		RecordingTool tool = new RecordingTool(
				(input) -> (input.contains("X-PAYMENT")) ? "{\"statusCode\":200}" : PAYMENT_REQUIRED);
		given(this.payments.generatePaymentHeader(any(), any())).willReturn(new PaymentHeader("X-PAYMENT", "proof"));

		String result = this.paying(tool).call("{\"url\":\"https://example.com\",\"headers\":{\"Accept\":\"*/*\"}}");

		assertThat(result).isEqualTo("{\"statusCode\":200}");
		assertThat(tool.inputs).hasSize(2);
		assertThat(tool.inputs.get(1)).contains("\"Accept\":\"*/*\"").contains("\"X-PAYMENT\":\"proof\"");
		then(this.payments).should()
			.generatePaymentHeader(eq(new PaymentContext("user-1", "instrument-1", "session-1")),
					any(PaymentRequired.class));
	}

	@Test
	void usesPaymentContextFromToolContext() {
		RecordingTool tool = new RecordingTool(
				(input) -> (input.contains("X-PAYMENT")) ? "{\"statusCode\":200}" : PAYMENT_REQUIRED);
		given(this.payments.generatePaymentHeader(any(), any())).willReturn(new PaymentHeader("X-PAYMENT", "proof"));

		this.paying(tool)
			.call("{\"url\":\"https://example.com\"}",
					new ToolContext(Map.of(PaymentContext.PAYMENT_SESSION_ID_KEY, "session-2")));

		then(this.payments).should()
			.generatePaymentHeader(eq(new PaymentContext("user-1", "instrument-1", "session-2")),
					any(PaymentRequired.class));
	}

	@Test
	void recognizesMarkerInJsonEncodedStringResult() {
		RecordingTool tool = new RecordingTool((input) -> (input.contains("X-PAYMENT")) ? "\"ok\""
				: "\"" + PAYMENT_REQUIRED.replace("\"", "\\\"") + "\"");
		given(this.payments.generatePaymentHeader(any(), any())).willReturn(new PaymentHeader("X-PAYMENT", "proof"));

		assertThat(this.paying(tool).call("{}")).isEqualTo("\"ok\"");
	}

	@Test
	void failsWithoutPayingTwiceWhenServerRejectsPayment() {
		RecordingTool tool = new RecordingTool((input) -> PAYMENT_REQUIRED);
		given(this.payments.generatePaymentHeader(any(), any())).willReturn(new PaymentHeader("X-PAYMENT", "proof"));

		assertThatExceptionOfType(ToolExecutionException.class).isThrownBy(() -> this.paying(tool).call("{}"))
			.withMessageContaining("rejected");
		assertThat(tool.inputs).hasSize(2);
		then(this.payments).should().generatePaymentHeader(any(), any());
	}

	@Test
	void reportsPaymentFailureAsToolExecutionException() {
		RecordingTool tool = new RecordingTool((input) -> PAYMENT_REQUIRED);
		given(this.payments.generatePaymentHeader(any(), any()))
			.willThrow(new InsufficientBudgetException("Insufficient budget", null));

		assertThatExceptionOfType(ToolExecutionException.class).isThrownBy(() -> this.paying(tool).call("{}"))
			.withCauseInstanceOf(InsufficientBudgetException.class);
		assertThat(tool.inputs).hasSize(1);
	}

	private PaymentToolCallback paying(ToolCallback tool) {
		return new PaymentToolCallback(tool, this.payments, this.resolver, Duration.ZERO);
	}

	static final class RecordingTool implements ToolCallback {

		final List<String> inputs = new ArrayList<>();

		private final Function<String, String> behavior;

		RecordingTool(Function<String, String> behavior) {
			this.behavior = behavior;
		}

		@Override
		public ToolDefinition getToolDefinition() {
			return ToolDefinition.builder().name("paidTool").description("paid tool").inputSchema("{}").build();
		}

		@Override
		public String call(String toolInput) {
			return this.call(toolInput, null);
		}

		@Override
		public String call(String toolInput, @Nullable ToolContext toolContext) {
			this.inputs.add(toolInput);
			return this.behavior.apply(toolInput);
		}

	}

}
