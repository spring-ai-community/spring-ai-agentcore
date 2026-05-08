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

package org.springaicommunity.agentcore.observability.realbedrock;

import java.time.Duration;
import org.springframework.ai.model.bedrock.autoconfigure.BedrockAwsConnectionProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.regions.providers.AwsRegionProvider;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeAsyncClient;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;

/**
 * Supplies AWS SDK v2 clients as Spring-managed beans with
 * {@code destroyMethod = "close"} so the Apache HTTP client's idle-connection reaper
 * shuts down cleanly with the application context (see Spring AI Bedrock Converse
 * auto-configuration, which passes these through when present).
 */
@Configuration(proxyBeanMethods = false)
public class RealBedrockAwsClientsConfiguration {

	@Bean(destroyMethod = "close")
	@ConditionalOnMissingBean
	BedrockRuntimeClient bedrockRuntimeClient(AwsCredentialsProvider credentialsProvider,
			AwsRegionProvider regionProvider, BedrockAwsConnectionProperties connectionProperties) {
		var builder = BedrockRuntimeClient.builder()
			.credentialsProvider(credentialsProvider)
			.region(regionProvider.getRegion());
		Duration timeout = connectionProperties.getTimeout();
		if (timeout != null) {
			builder.overrideConfiguration(c -> c.apiCallTimeout(timeout).apiCallAttemptTimeout(timeout));
		}
		return builder.build();
	}

	@Bean(destroyMethod = "close")
	@ConditionalOnMissingBean
	BedrockRuntimeAsyncClient bedrockRuntimeAsyncClient(AwsCredentialsProvider credentialsProvider,
			AwsRegionProvider regionProvider, BedrockAwsConnectionProperties connectionProperties) {
		var builder = BedrockRuntimeAsyncClient.builder()
			.credentialsProvider(credentialsProvider)
			.region(regionProvider.getRegion());
		Duration timeout = connectionProperties.getTimeout();
		if (timeout != null) {
			builder.overrideConfiguration(c -> c.apiCallTimeout(timeout).apiCallAttemptTimeout(timeout));
		}
		return builder.build();
	}

}
