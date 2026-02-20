/*
 * Copyright 2025-2025 the original author or authors.
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

package org.springaicommunity.agentcore.browser;

import com.microsoft.playwright.Playwright;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.services.bedrockagentcore.BedrockAgentCoreClient;

/**
 * Shared test configuration for AgentCore Browser integration tests. Provides the common
 * beans needed by both {@link BrowserToolsIT} and {@link BrowserChatFlowIT}.
 *
 * @author Yuriy Bezsonov
 */
@TestConfiguration
class AgentCoreBrowserTestConfiguration {

	@Bean
	BedrockAgentCoreClient bedrockAgentCoreClient() {
		return BedrockAgentCoreClient.create();
	}

	@Bean
	AwsCredentialsProvider awsCredentialsProvider() {
		return DefaultCredentialsProvider.builder().build();
	}

	@Bean(destroyMethod = "close")
	Playwright playwright() {
		return Playwright.create();
	}

	@Bean
	AgentCoreBrowserClient agentCoreBrowserClient(BedrockAgentCoreClient client, AgentCoreBrowserConfiguration config,
			AwsCredentialsProvider credentialsProvider, Playwright playwright) {
		return new AgentCoreBrowserClient(client, config, credentialsProvider, playwright);
	}

}
