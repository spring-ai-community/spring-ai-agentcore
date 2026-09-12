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

package org.springaicommunity.agentcore.identity.core;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariables;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.bedrockagentcore.BedrockAgentCoreClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Live AgentCore Identity data-plane smoke test. Enable with
 * {@code AGENTCORE_IDENTITY_IT=true} and a workload identity name. Credentials are
 * resolved through the standard AWS SDK provider chain.
 *
 * @author Matej Nedic
 */
@Tag("integration")
@EnabledIfEnvironmentVariables({ @EnabledIfEnvironmentVariable(named = "AGENTCORE_IDENTITY_IT", matches = "true"),
		@EnabledIfEnvironmentVariable(named = "AGENTCORE_IDENTITY_WORKLOAD_NAME", matches = ".+") })
class AgentCoreIdentityIT {

	@Test
	void retrievesWorkloadAccessTokenForUserId() {
		String region = System.getenv().getOrDefault("AGENTCORE_IDENTITY_REGION", "us-east-1");
		String workloadName = System.getenv("AGENTCORE_IDENTITY_WORKLOAD_NAME");
		try (BedrockAgentCoreClient client = BedrockAgentCoreClient.builder().region(Region.of(region)).build()) {
			AgentCoreIdentityTemplate identity = new AgentCoreIdentityTemplate(client);
			String token = identity.getWorkloadAccessTokenForUserId("spring-ai-agentcore-integration-test",
					workloadName);
			assertThat(token).isNotBlank();
		}
	}

}
