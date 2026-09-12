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

package org.springaicommunity.agentcore.identity.autoconfiguration;

import java.net.URI;

import org.junit.jupiter.api.Test;
import org.springaicommunity.agentcore.identity.core.AgentCoreIdentityTemplate;
import org.springaicommunity.agentcore.identity.core.WorkloadAccessTokenHolder;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.bedrockagentcore.BedrockAgentCoreClient;

import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class AwsAgentCoreIdentityAutoConfigurationTests {

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(AwsAgentCoreIdentityAutoConfiguration.class));

	@Test
	void createsBedrockAgentCoreClientUsingDefaultRegionProviderChain() {
		this.contextRunner.withSystemProperties("aws.region=eu-west-1").run((context) -> {
			assertThat(context).hasSingleBean(BedrockAgentCoreClient.class);
			assertThat(context.getBean(BedrockAgentCoreClient.class).serviceClientConfiguration().region())
				.isEqualTo(Region.EU_WEST_1);
		});
	}

	@Test
	void createsBedrockAgentCoreClient() {
		this.contextRunner
			.withBean(BedrockAgentCoreClient.class,
					() -> BedrockAgentCoreClient.builder()
						.region(Region.US_EAST_1)
						.credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("a", "b")))
						.endpointOverride(URI.create("http://localhost:4566"))
						.build())
			.run((context) -> assertThat(context).hasSingleBean(BedrockAgentCoreClient.class));
	}

	@Test
	void createsAgentCoreIdentityTemplateWithoutRuntimeStarter() {
		this.contextRunner.withClassLoader(new FilteredClassLoader("org.springaicommunity.agentcore.service"))
			.withBean(BedrockAgentCoreClient.class, () -> mock(BedrockAgentCoreClient.class))
			.run((context) -> {
				assertThat(context).hasSingleBean(AgentCoreIdentityTemplate.class);
				assertThat(context).doesNotHaveBean(WorkloadAccessTokenHolder.class);
			});
	}

	@Test
	void createsAgentCoreIdentityTemplate() {
		this.contextRunner
			.withBean(BedrockAgentCoreClient.class,
					() -> BedrockAgentCoreClient.builder()
						.region(Region.US_EAST_1)
						.credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("a", "b")))
						.endpointOverride(URI.create("http://localhost:4566"))
						.build())
			.run((context) -> assertThat(context).hasSingleBean(AgentCoreIdentityTemplate.class));
	}

	@Test
	void doesNotOverrideExistingBedrockAgentCoreClientBean() {
		this.contextRunner
			.withBean(BedrockAgentCoreClient.class,
					() -> BedrockAgentCoreClient.builder()
						.region(Region.US_EAST_1)
						.credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("a", "b")))
						.endpointOverride(URI.create("http://localhost:4566"))
						.build())
			.run((context) -> assertThat(context).hasSingleBean(BedrockAgentCoreClient.class));
	}

}
