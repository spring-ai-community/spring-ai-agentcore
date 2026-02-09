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

package org.springaicommunity.agentcore.memory;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariables;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Integration test for AgentCore Memory using pre-existing memory from environment
 * variables.
 *
 * <p>
 * Requires environment variables:
 * <ul>
 * <li>AGENTCORE_MEMORY_MEMORY_ID</li>
 * <li>AGENTCORE_MEMORY_LONG_TERM_SEMANTIC_FACTS_STRATEGY_ID</li>
 * <li>AGENTCORE_MEMORY_LONG_TERM_USER_PREFERENCES_STRATEGY_ID</li>
 * <li>AGENTCORE_MEMORY_LONG_TERM_SUMMARY_STRATEGY_ID</li>
 * <li>AGENTCORE_MEMORY_LONG_TERM_EPISODIC_STRATEGY_ID</li>
 * <li>AWS_ACCESS_KEY_ID (or other AWS credentials)</li>
 * </ul>
 *
 * @author Yuriy Bezsonov
 */
@EnabledIfEnvironmentVariables({ @EnabledIfEnvironmentVariable(named = "AGENTCORE_MEMORY_MEMORY_ID", matches = ".+"),
		@EnabledIfEnvironmentVariable(named = "AGENTCORE_MEMORY_LONG_TERM_SEMANTIC_FACTS_STRATEGY_ID", matches = ".+"),
		@EnabledIfEnvironmentVariable(named = "AGENTCORE_MEMORY_LONG_TERM_USER_PREFERENCES_STRATEGY_ID",
				matches = ".+"),
		@EnabledIfEnvironmentVariable(named = "AGENTCORE_MEMORY_LONG_TERM_SUMMARY_STRATEGY_ID", matches = ".+"),
		@EnabledIfEnvironmentVariable(named = "AGENTCORE_MEMORY_LONG_TERM_EPISODIC_STRATEGY_ID", matches = ".+"),
		@EnabledIfEnvironmentVariable(named = "AWS_ACCESS_KEY_ID", matches = ".+") })
@SpringBootTest(classes = AgentCoreMemoryIT.TestApp.class,
		properties = { "spring.ai.bedrock.converse.chat.options.model=" + AgentCoreMemoryIT.CHAT_MODEL })
@DisplayName("AgentCore Memory Integration Test (Env)")
class AgentCoreMemoryEnvIT extends AgentCoreMemoryIT {

	@BeforeAll
	static void loadFromEnv() {
		memoryId = System.getenv("AGENTCORE_MEMORY_MEMORY_ID");
		semanticStrategyId = System.getenv("AGENTCORE_MEMORY_LONG_TERM_SEMANTIC_FACTS_STRATEGY_ID");
		preferencesStrategyId = System.getenv("AGENTCORE_MEMORY_LONG_TERM_USER_PREFERENCES_STRATEGY_ID");
		summaryStrategyId = System.getenv("AGENTCORE_MEMORY_LONG_TERM_SUMMARY_STRATEGY_ID");
		episodicStrategyId = System.getenv("AGENTCORE_MEMORY_LONG_TERM_EPISODIC_STRATEGY_ID");

		String testId = generateTestId();
		actorId = "env-user-" + testId;
		sessionId = "env-session-" + testId;

		assertStrategyIdsNotNull();
		logMemoryInfo("");
		System.out.println("Actor ID: " + actorId);
		System.out.println("Session ID: " + sessionId);
	}

}
