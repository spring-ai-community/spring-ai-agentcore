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

import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;

import software.amazon.awssdk.services.bedrockagentcorecontrol.BedrockAgentCoreControlClient;
import software.amazon.awssdk.services.bedrockagentcorecontrol.model.CreateMemoryRequest;
import software.amazon.awssdk.services.bedrockagentcorecontrol.model.DeleteMemoryRequest;
import software.amazon.awssdk.services.bedrockagentcorecontrol.model.EpisodicMemoryStrategyInput;
import software.amazon.awssdk.services.bedrockagentcorecontrol.model.EpisodicReflectionConfigurationInput;
import software.amazon.awssdk.services.bedrockagentcorecontrol.model.GetMemoryRequest;
import software.amazon.awssdk.services.bedrockagentcorecontrol.model.MemoryStatus;
import software.amazon.awssdk.services.bedrockagentcorecontrol.model.MemoryStrategyInput;
import software.amazon.awssdk.services.bedrockagentcorecontrol.model.ModifyMemoryStrategies;
import software.amazon.awssdk.services.bedrockagentcorecontrol.model.SemanticMemoryStrategyInput;
import software.amazon.awssdk.services.bedrockagentcorecontrol.model.SummaryMemoryStrategyInput;
import software.amazon.awssdk.services.bedrockagentcorecontrol.model.UpdateMemoryRequest;
import software.amazon.awssdk.services.bedrockagentcorecontrol.model.UserPreferenceMemoryStrategyInput;

/**
 * End-to-end integration test for AgentCore Memory.
 *
 * <p>
 * Creates ephemeral memory resource with LTM strategies, runs tests from base class, and
 * cleans up. Takes ~5 minutes (memory creation + consolidation wait).
 *
 * <p>
 * Requires: AGENTCORE_IT=true and AWS credentials.
 *
 * @author Yuriy Bezsonov
 */
@EnabledIfEnvironmentVariable(named = "AGENTCORE_IT", matches = "true")
@SpringBootTest(classes = AgentCoreMemoryIT.TestApp.class,
		properties = { "spring.ai.bedrock.converse.chat.options.model=" + AgentCoreMemoryIT.CHAT_MODEL })
@DisplayName("AgentCore Memory E2E Integration Test")
class AgentCoreMemoryE2EIT extends AgentCoreMemoryIT {

	private static final String TEST_ID = generateTestId();

	private static final String MEMORY_NAME = "e2e_test_" + TEST_ID;

	private static BedrockAgentCoreControlClient controlClient;

	@BeforeAll
	static void createMemoryResource() {
		actorId = "e2e-user-" + TEST_ID;
		sessionId = "e2e-session-" + TEST_ID;

		System.out.println("E2E_TEST_ID=" + TEST_ID);
		System.out.println("E2E_ACTOR_ID=" + actorId);
		System.out.println("E2E_SESSION_ID=" + sessionId);

		controlClient = BedrockAgentCoreControlClient.create();

		// Create memory (7 days expiry)
		System.out.println("Creating memory: " + MEMORY_NAME);
		long memoryStartTime = System.currentTimeMillis();
		var createResponse = controlClient
			.createMemory(CreateMemoryRequest.builder().name(MEMORY_NAME).eventExpiryDuration(7).build());
		memoryId = createResponse.memory().id();
		System.out.println(BOLD + "Memory created: " + memoryId + RESET);

		// Wait for ACTIVE
		await().atMost(Duration.ofMinutes(5)).pollInterval(Duration.ofSeconds(15)).until(() -> {
			var status = controlClient.getMemory(GetMemoryRequest.builder().memoryId(memoryId).build())
				.memory()
				.status();
			long elapsed = (System.currentTimeMillis() - memoryStartTime) / 1000;
			System.out.println("Memory status: " + status + " (elapsed: " + (elapsed / 60) + ":"
					+ String.format("%02d", elapsed % 60) + ")");
			return status == MemoryStatus.ACTIVE;
		});

		// Add LTM strategies (all 4)
		System.out.println("Adding LTM strategies...");
		controlClient.updateMemory(UpdateMemoryRequest.builder()
			.memoryId(memoryId)
			.memoryStrategies(ModifyMemoryStrategies.builder()
				.addMemoryStrategies(
						MemoryStrategyInput.builder()
							.semanticMemoryStrategy(SemanticMemoryStrategyInput.builder()
								.name(SEMANTIC_STRATEGY_NAME)
								.namespaces(List.of("/strategies/{memoryStrategyId}/actors/{actorId}"))
								.build())
							.build(),
						MemoryStrategyInput.builder()
							.userPreferenceMemoryStrategy(UserPreferenceMemoryStrategyInput.builder()
								.name(PREFERENCES_STRATEGY_NAME)
								.namespaces(List.of("/strategies/{memoryStrategyId}/actors/{actorId}"))
								.build())
							.build(),
						MemoryStrategyInput.builder()
							.summaryMemoryStrategy(SummaryMemoryStrategyInput.builder()
								.name(SUMMARY_STRATEGY_NAME)
								.namespaces(
										List.of("/strategies/{memoryStrategyId}/actors/{actorId}/sessions/{sessionId}"))
								.build())
							.build(),
						MemoryStrategyInput.builder()
							.episodicMemoryStrategy(EpisodicMemoryStrategyInput.builder()
								.name(EPISODIC_STRATEGY_NAME)
								.namespaces(List.of("/strategies/{memoryStrategyId}/actors/{actorId}"))
								.reflectionConfiguration(EpisodicReflectionConfigurationInput.builder()
									.namespaces(List.of("/strategies/{memoryStrategyId}/actors/{actorId}"))
									.build())
								.build())
							.build())
				.build())
			.build());

		// Wait for strategies to be ACTIVE
		await().atMost(Duration.ofMinutes(2)).pollInterval(Duration.ofSeconds(5)).until(() -> {
			var memory = controlClient.getMemory(GetMemoryRequest.builder().memoryId(memoryId).build()).memory();
			if (memory.strategies().isEmpty()) {
				System.out.println("Strategies status: NONE");
				return false;
			}
			var allActive = memory.strategies().stream().allMatch(s -> s.status().toString().equals("ACTIVE"));
			System.out.println("Strategies status: " + (allActive ? "ACTIVE" : "CREATING"));
			return allActive;
		});

		// Get strategy IDs
		var memory = controlClient.getMemory(GetMemoryRequest.builder().memoryId(memoryId).build()).memory();
		for (var strategy : memory.strategies()) {
			switch (strategy.name()) {
				case SEMANTIC_STRATEGY_NAME -> semanticStrategyId = strategy.strategyId();
				case PREFERENCES_STRATEGY_NAME -> preferencesStrategyId = strategy.strategyId();
				case SUMMARY_STRATEGY_NAME -> summaryStrategyId = strategy.strategyId();
				case EPISODIC_STRATEGY_NAME -> episodicStrategyId = strategy.strategyId();
			}
		}

		assertStrategyIdsNotNull();
		logMemoryInfo("Memory ready: ");
	}

	@AfterAll
	static void deleteMemoryResource() {
		if (memoryId != null && controlClient != null) {
			System.out.println(BOLD + "Deleting memory: " + memoryId + RESET);
			try {
				controlClient.deleteMemory(DeleteMemoryRequest.builder().memoryId(memoryId).build());
				System.out.println("Memory deleted");
			}
			catch (Exception e) {
				System.err.println("Failed to delete memory: " + e.getMessage());
			}
			finally {
				controlClient.close();
			}
		}
	}

}
