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
import java.util.List;

import io.modelcontextprotocol.server.McpServerFeatures;
import org.junit.jupiter.api.Test;
import org.springaicommunity.agentcore.payments.client.AgentCorePaymentsClientHttpRequestInterceptor;
import org.springaicommunity.agentcore.payments.core.AgentCorePaymentsTemplate;
import org.springaicommunity.agentcore.payments.core.PaymentContext;
import org.springaicommunity.agentcore.payments.core.PaymentSessionRegistry;
import org.springaicommunity.agentcore.payments.tool.AgentCorePaymentsTools;
import org.springaicommunity.agentcore.payments.tool.PaymentContextResolver;
import software.amazon.awssdk.services.bedrockagentcore.BedrockAgentCoreClient;

import org.springframework.ai.mcp.server.common.autoconfigure.ToolCallbackConverterAutoConfiguration;
import org.springframework.ai.mcp.server.common.autoconfigure.properties.McpServerProperties;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Tests for {@link AgentCorePaymentsAutoConfiguration}.
 *
 * @author Andrei Shakirin
 */
class AgentCorePaymentsAutoConfigurationTests {

	private static final String ARN = "agentcore.payments.payment-manager-arn=arn:aws:bedrock-agentcore:pm";

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(AgentCorePaymentsAutoConfiguration.class))
		.withBean(BedrockAgentCoreClient.class, () -> mock(BedrockAgentCoreClient.class));

	@Test
	void isDisabledWithoutPaymentManagerArn() {
		this.contextRunner.run((context) -> assertThat(context).doesNotHaveBean(AgentCorePaymentsTemplate.class));
	}

	@Test
	void createsPaymentBeansWithQueryToolsOnlyByDefault() {
		this.contextRunner
			.withPropertyValues(ARN, "agentcore.payments.user-id=user-1",
					"agentcore.payments.payment-session-id=session-1")
			.run((context) -> {
				assertThat(context).hasSingleBean(AgentCorePaymentsTemplate.class)
					.hasSingleBean(AgentCorePaymentsClientHttpRequestInterceptor.class)
					.hasSingleBean(PaymentSessionRegistry.class)
					.hasSingleBean(AgentCorePaymentsTools.class);
				assertThat(context.getBean(PaymentContextResolver.class).resolve(null))
					.isEqualTo(new PaymentContext("user-1", null, "session-1"));
				assertThat(toolNames(context.getBean(AgentCorePaymentsTools.class))).containsExactly(
						"getPaymentInstrument", "listPaymentInstruments", "getPaymentInstrumentBalance",
						"getPaymentSession");
			});
	}

	@Test
	void registersPaidHttpToolOnlyWithAllowedHosts() {
		this.contextRunner
			.withPropertyValues(ARN, "agentcore.payments.paid-http-tool.enabled=true",
					"agentcore.payments.paid-http-tool.allowed-hosts=api.example.com,*.merchant.io")
			.run((context) -> assertThat(toolNames(context.getBean(AgentCorePaymentsTools.class)))
				.contains("paidHttpRequest"));
		this.contextRunner.withPropertyValues(ARN, "agentcore.payments.paid-http-tool.enabled=true")
			.run((context) -> assertThat(context).hasFailed()
				.getFailure()
				.rootCause()
				.hasMessageContaining("agentcore.payments.paid-http-tool.allowed-hosts"));
	}

	@Test
	void doesNotPublishPaymentToolsToMcpServer() {
		this.contextRunner.withConfiguration(AutoConfigurations.of(ToolCallbackConverterAutoConfiguration.class))
			.withUserConfiguration(McpServerPropertiesConfiguration.class)
			.withPropertyValues(ARN, "agentcore.payments.paid-http-tool.enabled=true",
					"agentcore.payments.paid-http-tool.allowed-hosts=api.example.com")
			.run((context) -> {
				assertThat(context).doesNotHaveBean(ToolCallbackProvider.class).doesNotHaveBean(ToolCallback.class);
				@SuppressWarnings("unchecked")
				List<McpServerFeatures.SyncToolSpecification> published = context.getBean("syncTools", List.class);
				assertThat(published).isEmpty();
			});
	}

	private static List<String> toolNames(AgentCorePaymentsTools tools) {
		return Arrays.stream(tools.toolCallbacks()).map((tool) -> tool.getToolDefinition().name()).toList();
	}

	@Configuration(proxyBeanMethods = false)
	@EnableConfigurationProperties(McpServerProperties.class)
	static class McpServerPropertiesConfiguration {

	}

}
