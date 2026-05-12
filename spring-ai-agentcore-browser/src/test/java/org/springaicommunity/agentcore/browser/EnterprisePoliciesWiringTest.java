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

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import software.amazon.awssdk.services.bedrockagentcore.BedrockAgentCoreClient;
import software.amazon.awssdk.services.bedrockagentcore.model.AutomationStream;
import software.amazon.awssdk.services.bedrockagentcore.model.BrowserEnterprisePolicy;
import software.amazon.awssdk.services.bedrockagentcore.model.BrowserSessionStream;
import software.amazon.awssdk.services.bedrockagentcore.model.StartBrowserSessionRequest;
import software.amazon.awssdk.services.bedrockagentcore.model.StartBrowserSessionResponse;
import software.amazon.awssdk.services.bedrockagentcore.model.StopBrowserSessionRequest;
import software.amazon.awssdk.services.bedrockagentcore.model.StopBrowserSessionResponse;
import software.amazon.awssdk.services.bedrockagentcore.BedrockAgentCoreServiceClientConfiguration;
import software.amazon.awssdk.regions.Region;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests that enterprise policies are correctly wired through to
 * StartBrowserSessionRequest.
 */
class EnterprisePoliciesWiringTest {

	@Test
	@DisplayName("Should pass enterprise policies to StartBrowserSessionRequest")
	void shouldPassEnterprisePolicies() {
		// Given
		BedrockAgentCoreClient mockClient = mock(BedrockAgentCoreClient.class);
		BedrockAgentCoreServiceClientConfiguration mockServiceConfig = mock(
				BedrockAgentCoreServiceClientConfiguration.class);
		when(mockClient.serviceClientConfiguration()).thenReturn(mockServiceConfig);
		when(mockServiceConfig.region()).thenReturn(Region.US_EAST_1);

		when(mockClient.startBrowserSession(any(StartBrowserSessionRequest.class)))
			.thenReturn(StartBrowserSessionResponse.builder()
				.sessionId("test-session-id")
				.streams(BrowserSessionStream.builder()
					.automationStream(AutomationStream.builder().streamEndpoint("wss://example.com/ws").build())
					.build())
				.build());

		when(mockClient.stopBrowserSession(any(StopBrowserSessionRequest.class)))
			.thenReturn(StopBrowserSessionResponse.builder().build());

		AgentCoreBrowserConfiguration config = new AgentCoreBrowserConfiguration("agentcore", null, null, null, null,
				null, null, null, null, null, null, null, null,
				List.of(new AgentCoreBrowserConfiguration.EnterprisePolicyRef(
						new AgentCoreBrowserConfiguration.S3Ref("my-bucket", "policies/block.json", null),
						"RECOMMENDED")));

		// When — the client will fail on Playwright connect, but we can still capture
		// the request
		try {
			AgentCoreBrowserClient browserClient = new AgentCoreBrowserClient(mockClient, config,
					mock(software.amazon.awssdk.auth.credentials.AwsCredentialsProvider.class),
					mock(com.microsoft.playwright.Playwright.class));
			browserClient.browseAndExtract("https://example.com");
		}
		catch (Exception ignored) {
			// Expected — Playwright mock won't connect
		}

		// Then — verify the StartBrowserSessionRequest had enterprise policies
		ArgumentCaptor<StartBrowserSessionRequest> captor = ArgumentCaptor.forClass(StartBrowserSessionRequest.class);
		verify(mockClient).startBrowserSession(captor.capture());

		StartBrowserSessionRequest captured = captor.getValue();
		assertThat(captured.hasEnterprisePolicies()).isTrue();

		List<BrowserEnterprisePolicy> policies = captured.enterprisePolicies();
		assertThat(policies).hasSize(1);
		assertThat(policies.get(0).location().s3().bucket()).isEqualTo("my-bucket");
		assertThat(policies.get(0).location().s3().prefix()).isEqualTo("policies/block.json");
		assertThat(policies.get(0).typeAsString()).isEqualTo("RECOMMENDED");
	}

	@Test
	@DisplayName("Should not include enterprise policies when config is empty")
	void shouldNotIncludePoliciesWhenEmpty() {
		// Given
		BedrockAgentCoreClient mockClient = mock(BedrockAgentCoreClient.class);
		BedrockAgentCoreServiceClientConfiguration mockServiceConfig = mock(
				BedrockAgentCoreServiceClientConfiguration.class);
		when(mockClient.serviceClientConfiguration()).thenReturn(mockServiceConfig);
		when(mockServiceConfig.region()).thenReturn(Region.US_EAST_1);

		when(mockClient.startBrowserSession(any(StartBrowserSessionRequest.class)))
			.thenReturn(StartBrowserSessionResponse.builder()
				.sessionId("test-session-id")
				.streams(BrowserSessionStream.builder()
					.automationStream(AutomationStream.builder().streamEndpoint("wss://example.com/ws").build())
					.build())
				.build());

		when(mockClient.stopBrowserSession(any(StopBrowserSessionRequest.class)))
			.thenReturn(StopBrowserSessionResponse.builder().build());

		AgentCoreBrowserConfiguration config = new AgentCoreBrowserConfiguration("agentcore", null, null, null, null,
				null, null, null, null, null, null, null, null, null);

		// When
		try {
			AgentCoreBrowserClient browserClient = new AgentCoreBrowserClient(mockClient, config,
					mock(software.amazon.awssdk.auth.credentials.AwsCredentialsProvider.class),
					mock(com.microsoft.playwright.Playwright.class));
			browserClient.browseAndExtract("https://example.com");
		}
		catch (Exception ignored) {
			// Expected — Playwright mock won't connect
		}

		// Then
		ArgumentCaptor<StartBrowserSessionRequest> captor = ArgumentCaptor.forClass(StartBrowserSessionRequest.class);
		verify(mockClient).startBrowserSession(captor.capture());

		StartBrowserSessionRequest captured = captor.getValue();
		assertThat(captured.hasEnterprisePolicies()).isFalse();
	}

	@Test
	@DisplayName("Should propagate versionId through to S3Location")
	void shouldPropagateVersionId() {
		// Given
		BedrockAgentCoreClient mockClient = mock(BedrockAgentCoreClient.class);
		BedrockAgentCoreServiceClientConfiguration mockServiceConfig = mock(
				BedrockAgentCoreServiceClientConfiguration.class);
		when(mockClient.serviceClientConfiguration()).thenReturn(mockServiceConfig);
		when(mockServiceConfig.region()).thenReturn(Region.US_EAST_1);

		when(mockClient.startBrowserSession(any(StartBrowserSessionRequest.class)))
			.thenReturn(StartBrowserSessionResponse.builder()
				.sessionId("test-session-id")
				.streams(BrowserSessionStream.builder()
					.automationStream(AutomationStream.builder().streamEndpoint("wss://example.com/ws").build())
					.build())
				.build());

		when(mockClient.stopBrowserSession(any(StopBrowserSessionRequest.class)))
			.thenReturn(StopBrowserSessionResponse.builder().build());

		AgentCoreBrowserConfiguration config = new AgentCoreBrowserConfiguration("agentcore", null, null, null, null,
				null, null, null, null, null, null, null, null,
				List.of(new AgentCoreBrowserConfiguration.EnterprisePolicyRef(
						new AgentCoreBrowserConfiguration.S3Ref("my-bucket", "policies/v2.json", "abc123"),
						"RECOMMENDED")));

		// When
		try {
			AgentCoreBrowserClient browserClient = new AgentCoreBrowserClient(mockClient, config,
					mock(software.amazon.awssdk.auth.credentials.AwsCredentialsProvider.class),
					mock(com.microsoft.playwright.Playwright.class));
			browserClient.browseAndExtract("https://example.com");
		}
		catch (Exception ignored) {
			// Expected
		}

		// Then
		ArgumentCaptor<StartBrowserSessionRequest> captor = ArgumentCaptor.forClass(StartBrowserSessionRequest.class);
		verify(mockClient).startBrowserSession(captor.capture());

		StartBrowserSessionRequest captured = captor.getValue();
		assertThat(captured.enterprisePolicies().get(0).location().s3().versionId()).isEqualTo("abc123");
	}

}
