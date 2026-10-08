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

package org.springaicommunity.agentcore.payments.autoconfiguration;

import java.util.Arrays;

import org.junit.jupiter.api.Test;
import org.springaicommunity.agentcore.payments.client.AgentCorePaymentsClientHttpRequestInterceptor;
import org.springaicommunity.agentcore.payments.core.AgentCorePaymentsTemplate;
import org.springaicommunity.agentcore.payments.core.PaymentContext;
import org.springaicommunity.agentcore.payments.core.PaymentSessionRegistry;
import org.springaicommunity.agentcore.payments.tool.AgentCorePaymentsToolCallbacks;
import org.springaicommunity.agentcore.payments.tool.PaymentContextResolver;
import software.amazon.awssdk.services.bedrockagentcore.BedrockAgentCoreClient;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Tests for {@link AgentCorePaymentsAutoConfiguration}.
 *
 * @author Andrei Shakirin
 */
class AgentCorePaymentsAutoConfigurationTests {

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(AgentCorePaymentsAutoConfiguration.class))
		.withBean(BedrockAgentCoreClient.class, () -> mock(BedrockAgentCoreClient.class));

	@Test
	void isDisabledWithoutPaymentManagerArn() {
		this.contextRunner.run((context) -> assertThat(context).doesNotHaveBean(AgentCorePaymentsTemplate.class));
	}

	@Test
	void createsPaymentBeansAndToolsWhenPaymentManagerArnIsSet() {
		this.contextRunner
			.withPropertyValues("agentcore.payments.payment-manager-arn=arn:aws:bedrock-agentcore:pm",
					"agentcore.payments.user-id=user-1", "agentcore.payments.payment-session-id=session-1")
			.run((context) -> {
				assertThat(context).hasSingleBean(AgentCorePaymentsTemplate.class)
					.hasSingleBean(AgentCorePaymentsToolCallbacks.class)
					.hasSingleBean(AgentCorePaymentsClientHttpRequestInterceptor.class)
					.hasSingleBean(PaymentSessionRegistry.class);
				assertThat(context.getBean(PaymentContextResolver.class).resolve(null))
					.isEqualTo(new PaymentContext("user-1", null, "session-1"));
				ToolCallback[] tools = context.getBean("paymentsToolCallbackProvider", ToolCallbackProvider.class)
					.getToolCallbacks();
				assertThat(Arrays.stream(tools).map((tool) -> tool.getToolDefinition().name())).containsExactly(
						"getPaymentInstrument", "listPaymentInstruments", "getPaymentInstrumentBalance",
						"getPaymentSession", "paidHttpRequest");
			});
	}

	@Test
	void httpToolCanBeDisabledAndResolverReplaced() {
		this.contextRunner
			.withPropertyValues("agentcore.payments.payment-manager-arn=arn:aws:bedrock-agentcore:pm",
					"agentcore.payments.paid-http-tool.enabled=false")
			.withBean(PaymentContextResolver.class, () -> (toolContext) -> new PaymentContext("custom", null, null))
			.run((context) -> {
				assertThat(context.getBean(PaymentContextResolver.class).resolve(null).userId()).isEqualTo("custom");
				ToolCallback[] tools = context.getBean("paymentsToolCallbackProvider", ToolCallbackProvider.class)
					.getToolCallbacks();
				assertThat(Arrays.stream(tools).map((tool) -> tool.getToolDefinition().name()))
					.doesNotContain("paidHttpRequest");
			});
	}

}
