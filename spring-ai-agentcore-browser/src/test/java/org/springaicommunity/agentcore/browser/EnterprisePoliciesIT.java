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
import java.util.UUID;

import com.microsoft.playwright.Playwright;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.bedrockagentcore.BedrockAgentCoreClient;
import software.amazon.awssdk.services.s3.S3Client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * End-to-end integration tests for browser enterprise policies. Uploads a
 * {@code RECOMMENDED} Chromium {@code URLBlocklist} policy to S3 and verifies it is
 * actually enforced by the AgentCore Browser session (not merely accepted).
 *
 * @author Yuriy Bezsonov
 */
@EnabledIfEnvironmentVariable(named = "AGENTCORE_IT", matches = "true")
@DisplayName("Enterprise Policies Integration Tests")
class EnterprisePoliciesIT {

	private static final String POLICY_KEY = "policies/blocklist.json";

	private static final String POLICY_JSON = "{\"URLBlocklist\": [\"example.com\", \"*://*.example.com/*\"]}";

	private static final String BLOCKED_URL = "https://example.com";

	private static final String ALLOWED_URL = "https://docs.aws.amazon.com";

	private static BedrockAgentCoreClient agentCoreClient;

	private static AwsCredentialsProvider credentialsProvider;

	private static Playwright playwright;

	private static S3Client s3;

	private static String bucket;

	@BeforeAll
	static void setUp() {
		agentCoreClient = BedrockAgentCoreClient.create();
		Region region = agentCoreClient.serviceClientConfiguration().region();
		credentialsProvider = DefaultCredentialsProvider.builder().build();
		playwright = Playwright.create();
		s3 = S3Client.builder().region(region).build();
		bucket = "agentcore-it-ent-policies-" + UUID.randomUUID().toString().substring(0, 12);
		s3.createBucket((b) -> b.bucket(bucket).createBucketConfiguration((c) -> {
			if (!Region.US_EAST_1.equals(region)) {
				c.locationConstraint(region.id());
			}
		}));
		s3.putObject((b) -> b.bucket(bucket).key(POLICY_KEY), RequestBody.fromString(POLICY_JSON));
	}

	@AfterAll
	static void tearDown() {
		if (s3 != null && bucket != null) {
			s3.deleteObject((b) -> b.bucket(bucket).key(POLICY_KEY));
			s3.deleteBucket((b) -> b.bucket(bucket));
			s3.close();
		}
		if (playwright != null) {
			playwright.close();
		}
		if (agentCoreClient != null) {
			agentCoreClient.close();
		}
	}

	private AgentCoreBrowserClient clientWithPolicy(String prefix) {
		AgentCoreBrowserConfiguration config = new AgentCoreBrowserConfiguration("agentcore", null, null, null, null,
				null, null, null, null, null, null, null, null,
				List.of(new AgentCoreBrowserConfiguration.EnterprisePolicyRef(
						new AgentCoreBrowserConfiguration.S3Ref(bucket, prefix, null), "RECOMMENDED")));
		return new AgentCoreBrowserClient(agentCoreClient, config, credentialsProvider, playwright);
	}

	@Test
	@DisplayName("Should enforce a recommended URLBlocklist policy in the browser session")
	void shouldEnforceUrlBlocklistPolicy() {
		AgentCoreBrowserClient client = this.clientWithPolicy(POLICY_KEY);

		// Blocked host is refused by Chromium with the enterprise-policy error.
		assertThatThrownBy(() -> client.browseAndExtract(BLOCKED_URL)).isInstanceOf(BrowserOperationException.class)
			.hasMessageContaining("ERR_BLOCKED_BY_ADMINISTRATOR");

		// A non-blocked host still loads, proving the policy is scoped, not a hard
		// failure.
		assertThat(client.browseAndExtract(ALLOWED_URL)).containsIgnoringCase("aws");
	}

	@Test
	@DisplayName("Should fail to start session when the enterprise policy S3 object is missing")
	void shouldFailWhenPolicyObjectMissing() {
		AgentCoreBrowserClient client = this.clientWithPolicy("policies/does-not-exist.json");

		assertThatThrownBy(() -> client.browseAndExtract(ALLOWED_URL)).isInstanceOf(BrowserOperationException.class);
	}

}
