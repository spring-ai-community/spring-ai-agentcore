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

package org.springaicommunity.agentcore.evaluations;

import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import io.micrometer.core.instrument.MeterRegistry;
import org.springaicommunity.agentcore.evaluations.client.AgentCoreEvaluationClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.bedrockagentcore.BedrockAgentCoreClient;

import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Auto-configuration for AgentCore Evaluations.
 *
 * <p>
 * Configures the evaluation client, metrics, and advisor when enabled via properties.
 *
 * @author Andrei Shakirin
 */
@AutoConfiguration(
		afterName = "org.springframework.boot.actuate.autoconfigure.metrics.CompositeMeterRegistryAutoConfiguration")
@ConditionalOnClass(BedrockAgentCoreClient.class)
@ConditionalOnProperty(prefix = AgentCoreEvaluationProperties.CONFIG_PREFIX, name = "enabled", havingValue = "true")
@EnableConfigurationProperties(AgentCoreEvaluationProperties.class)
public class AgentCoreEvaluationAutoConfiguration {

	/**
	 * bean name for the evaluation executor service.
	 */
	public static final String EXECUTOR_BEAN_NAME = "agentCoreEvaluationExecutor";

	@Bean(destroyMethod = "close")
	@ConditionalOnMissingBean
	public BedrockAgentCoreClient bedrockAgentCoreClient(AgentCoreEvaluationProperties properties) {
		var builder = BedrockAgentCoreClient.builder();
		if (properties.region() != null && !properties.region().isEmpty()) {
			builder.region(Region.of(properties.region()));
		}
		return builder.build();
	}

	/**
	 * Default executor for asynchronous evaluations. Uses a virtual-thread-per-task
	 * executor because evaluation work blocks on remote Bedrock calls; virtual threads
	 * are cheap to park on I/O.
	 * @return the evaluation executor service
	 */
	@Bean(name = EXECUTOR_BEAN_NAME, destroyMethod = "close")
	@ConditionalOnMissingBean(name = EXECUTOR_BEAN_NAME)
	public ExecutorService agentCoreEvaluationExecutor() {
		return Executors.newVirtualThreadPerTaskExecutor();
	}

	@Bean
	@ConditionalOnMissingBean
	public AgentCoreEvaluationClient agentCoreEvaluationClient(BedrockAgentCoreClient client) {
		return new AgentCoreEvaluationClient(client);
	}

	@Bean
	@ConditionalOnMissingBean
	@ConditionalOnClass(MeterRegistry.class)
	@ConditionalOnBean(MeterRegistry.class)
	@ConditionalOnProperty(prefix = AgentCoreEvaluationProperties.CONFIG_PREFIX, name = "metrics-enabled",
			havingValue = "true", matchIfMissing = true)
	public AgentCoreEvaluationMetrics agentCoreEvaluationMetrics(MeterRegistry registry) {
		return new AgentCoreEvaluationMetrics(registry);
	}

	@Bean
	@ConditionalOnMissingBean
	@ConditionalOnClass(CallAdvisor.class)
	public AgentCoreEvaluationAdvisor agentCoreEvaluationAdvisor(AgentCoreEvaluationClient client,
			AgentCoreEvaluationProperties properties, Optional<AgentCoreEvaluationMetrics> metrics,
			@Qualifier(EXECUTOR_BEAN_NAME) ExecutorService executor) {
		var builder = AgentCoreEvaluationAdvisor.builder(client)
			.evaluatorIds(properties.evaluatorIds())
			.async(properties.async())
			.sampleRate(properties.sampleRate())
			.includeHistory(properties.includeHistory())
			.executor(executor);

		metrics.ifPresent(builder::metrics);

		return builder.build();
	}

}
