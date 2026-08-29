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

import io.micrometer.context.ContextSnapshot;
import io.micrometer.context.ThreadLocalAccessor;
import org.springaicommunity.agentcore.identity.core.AgentCoreIdentityTemplate;
import org.springaicommunity.agentcore.identity.core.WorkloadAccessTokenAccessor;
import org.springaicommunity.agentcore.identity.core.WorkloadAccessTokenCallback;
import org.springaicommunity.agentcore.identity.core.WorkloadAccessTokenHolder;
import reactor.core.publisher.Flux;
import software.amazon.awssdk.services.bedrockagentcore.BedrockAgentCoreClient;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Autoconfiguration for Bedrock AWS SDK client and {@link AgentCoreIdentityTemplate}.
 *
 * @author Matej Nedic
 */
@AutoConfiguration
public class AwsAgentCoreIdentityAutoConfiguration {

	@Bean
	@ConditionalOnMissingBean
	public BedrockAgentCoreClient bedrockAgentCoreClient() {
		return BedrockAgentCoreClient.create();
	}

	@Bean
	@ConditionalOnMissingBean
	public AgentCoreIdentityTemplate agentCoreIdentityTemplate(BedrockAgentCoreClient bedrockAgentCoreClient,
			ObjectProvider<WorkloadAccessTokenHolder> holderProvider) {
		return new AgentCoreIdentityTemplate(bedrockAgentCoreClient, holderProvider.getIfAvailable());
	}

	@Configuration
	@ConditionalOnClass(name = "org.springaicommunity.agentcore.service.AgentCoreInvocationCallback")
	static class WorkloadAccessTokenConfiguration {

		@Bean
		@ConditionalOnMissingBean
		WorkloadAccessTokenHolder workloadAccessTokenHolder() {
			return new WorkloadAccessTokenHolder();
		}

		@Bean
		@ConditionalOnMissingBean
		WorkloadAccessTokenCallback workloadAccessTokenCallback(WorkloadAccessTokenHolder holder) {
			return new WorkloadAccessTokenCallback(holder);
		}

		/**
		 * Creates the accessor used by Identity's application-context-scoped snapshot
		 * factory. It does not register the accessor with Micrometer's global registry.
		 * @param holder the thread-local holder backing the accessor
		 * @return the workload access token accessor
		 */
		@Bean
		@ConditionalOnClass(ThreadLocalAccessor.class)
		@ConditionalOnMissingBean
		WorkloadAccessTokenAccessor workloadAccessTokenAccessor(WorkloadAccessTokenHolder holder) {
			return new WorkloadAccessTokenAccessor(holder);
		}

		@Configuration
		@ConditionalOnClass({ Flux.class, ContextSnapshot.class })
		static class ReactiveContextPropagationConfiguration {

			@Bean
			@ConditionalOnMissingBean
			WorkloadAccessTokenContextPropagationCallback workloadAccessTokenContextPropagationCallback(
					WorkloadAccessTokenAccessor accessor) {
				return new WorkloadAccessTokenContextPropagationCallback(accessor);
			}

		}

	}

}
