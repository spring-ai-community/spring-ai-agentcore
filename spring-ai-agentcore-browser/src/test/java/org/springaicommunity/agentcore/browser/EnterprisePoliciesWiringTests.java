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

package org.springaicommunity.agentcore.browser;

import java.util.List;

import com.microsoft.playwright.Playwright;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.bedrockagentcore.BedrockAgentCoreClient;
import software.amazon.awssdk.services.bedrockagentcore.BedrockAgentCoreServiceClientConfiguration;
import software.amazon.awssdk.services.bedrockagentcore.model.BrowserEnterprisePolicy;
import software.amazon.awssdk.services.bedrockagentcore.model.StartBrowserSessionRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/**
 * Tests that enterprise policies are correctly wired through to
 * StartBrowserSessionRequest.
 */
class EnterprisePoliciesWiringTests {

	@Test
	@DisplayName("Should pass enterprise policies to StartBrowserSessionRequest")
	void shouldPassEnterprisePolicies() {
		BedrockAgentCoreClient mockClient = mock(BedrockAgentCoreClient.class);
		BedrockAgentCoreServiceClientConfiguration mockServiceConfig = mock(
				BedrockAgentCoreServiceClientConfiguration.class);
		given(mockClient.serviceClientConfiguration()).willReturn(mockServiceConfig);
		given(mockServiceConfig.region()).willReturn(Region.US_EAST_1);
		AgentCoreBrowserConfiguration config = new AgentCoreBrowserConfiguration("agentcore", null, null, null, null,
				null, null, null, null, null, null, null, null,
				List.of(new AgentCoreBrowserConfiguration.EnterprisePolicyRef(
						new AgentCoreBrowserConfiguration.S3Ref("my-bucket", "policies/block.json", null),
						"RECOMMENDED")));
		AgentCoreBrowserClient browserClient = new AgentCoreBrowserClient(mockClient, config,
				mock(AwsCredentialsProvider.class), mock(Playwright.class));
		StartBrowserSessionRequest captured = browserClient.buildStartSessionRequest("test-session");
		assertThat(captured.hasEnterprisePolicies()).isTrue();

		List<BrowserEnterprisePolicy> policies = captured.enterprisePolicies();
		assertThat(policies).hasSize(1);
		assertThat(policies.getFirst().location().s3().bucket()).isEqualTo("my-bucket");
		assertThat(policies.getFirst().location().s3().prefix()).isEqualTo("policies/block.json");
		assertThat(policies.getFirst().typeAsString()).isEqualTo("RECOMMENDED");
	}

	@Test
	@DisplayName("Should not include enterprise policies when config is empty")
	void shouldNotIncludePoliciesWhenEmpty() {
		BedrockAgentCoreClient mockClient = mock(BedrockAgentCoreClient.class);
		BedrockAgentCoreServiceClientConfiguration mockServiceConfig = mock(
				BedrockAgentCoreServiceClientConfiguration.class);
		given(mockClient.serviceClientConfiguration()).willReturn(mockServiceConfig);
		given(mockServiceConfig.region()).willReturn(Region.US_EAST_1);
		AgentCoreBrowserConfiguration config = new AgentCoreBrowserConfiguration("agentcore", null, null, null, null,
				null, null, null, null, null, null, null, null, null);
		AgentCoreBrowserClient browserClient = new AgentCoreBrowserClient(mockClient, config,
				mock(AwsCredentialsProvider.class), mock(Playwright.class));
		StartBrowserSessionRequest captured = browserClient.buildStartSessionRequest("test-session");
		assertThat(captured.hasEnterprisePolicies()).isFalse();
	}

	@Test
	@DisplayName("Should propagate versionId through to S3Location")
	void shouldPropagateVersionId() {
		BedrockAgentCoreClient mockClient = mock(BedrockAgentCoreClient.class);
		BedrockAgentCoreServiceClientConfiguration mockServiceConfig = mock(
				BedrockAgentCoreServiceClientConfiguration.class);
		given(mockClient.serviceClientConfiguration()).willReturn(mockServiceConfig);
		given(mockServiceConfig.region()).willReturn(Region.US_EAST_1);
		AgentCoreBrowserConfiguration config = new AgentCoreBrowserConfiguration("agentcore", null, null, null, null,
				null, null, null, null, null, null, null, null,
				List.of(new AgentCoreBrowserConfiguration.EnterprisePolicyRef(
						new AgentCoreBrowserConfiguration.S3Ref("my-bucket", "policies/v2.json", "abc123"),
						"RECOMMENDED")));
		AgentCoreBrowserClient browserClient = new AgentCoreBrowserClient(mockClient, config,
				mock(AwsCredentialsProvider.class), mock(Playwright.class));
		StartBrowserSessionRequest captured = browserClient.buildStartSessionRequest("test-session");
		assertThat(captured.enterprisePolicies().getFirst().location().s3().versionId()).isEqualTo("abc123");
	}

}
