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

package org.springaicommunity.agentcore.observability;

import org.springaicommunity.agentcore.observability.realbedrock.RealBedrockAwsClientsConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

/**
 * Live Bedrock tests need exactly one {@code @AgentCoreInvocation} handler. A second
 * {@code @ComponentScan} on the same class does not merge with
 * {@code @SpringBootApplication}'s scan, so we narrow the scan to the real Bedrock agent
 * package only (excluding the synthetic sample under {@code ...sample}).
 * {@link org.springaicommunity.agentcore.observability.testsupport.OtelInMemorySpanExporterTestConfig}
 * is {@code @Import}ed by tests rather than component-scanned.
 */
@SpringBootApplication(scanBasePackages = "org.springaicommunity.agentcore.observability.realbedrock")
@Import(RealBedrockAwsClientsConfiguration.class)
public class RealBedrockTestApplication {

	public static void main(String[] args) {
		SpringApplication.run(RealBedrockTestApplication.class, args);
	}

}
