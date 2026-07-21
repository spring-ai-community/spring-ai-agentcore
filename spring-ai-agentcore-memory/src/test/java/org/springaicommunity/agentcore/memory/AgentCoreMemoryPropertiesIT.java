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

package org.springaicommunity.agentcore.memory;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springaicommunity.agentcore.memory.shortterm.AgentCoreShortTermMemoryRepository;
import org.springaicommunity.agentcore.memory.shortterm.AgentCoreShortTermMemoryRepositoryAutoConfiguration;
import software.amazon.awssdk.services.bedrockagentcore.BedrockAgentCoreClient;
import software.amazon.awssdk.services.bedrockagentcorecontrol.BedrockAgentCoreControlClient;
import software.amazon.awssdk.services.bedrockagentcorecontrol.model.CreateMemoryRequest;
import software.amazon.awssdk.services.bedrockagentcorecontrol.model.DeleteMemoryRequest;
import software.amazon.awssdk.services.bedrockagentcorecontrol.model.GetMemoryRequest;
import software.amazon.awssdk.services.bedrockagentcorecontrol.model.MemoryStatus;

import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Integration test that verifies the auto-configuration wires the
 * {@link AgentCoreShortTermMemoryRepository} correctly against a real AgentCore Memory
 * resource for both the new {@code agentcore.memory.short-term.*} property namespace and
 * the legacy STM fallback at {@code agentcore.memory.*}. Also asserts that the
 * deprecation warning is emitted only when the legacy properties are in use.
 *
 * <p>
 * Creates a single AgentCore memory in {@code @BeforeAll} and reuses it across both
 * scenarios; deletes it in {@code @AfterAll}.
 *
 * @author Yuriy Bezsonov
 */
@Tag("integration")
@EnabledIfEnvironmentVariable(named = "AGENTCORE_IT", matches = "true")
@ExtendWith(OutputCaptureExtension.class)
@DisplayName("AgentCore Memory Properties Integration Test - exercises new and deprecated property namespaces")
class AgentCoreMemoryPropertiesIT {

	private static final BedrockAgentCoreControlClient CONTROL_CLIENT = BedrockAgentCoreControlClient.create();

	private static String memoryId;

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(AgentCoreShortTermMemoryRepositoryAutoConfiguration.class));

	@BeforeAll
	static void createMemoryResource() {
		var createMemoryRequest = CreateMemoryRequest.builder()
			.name("properties_it_" + System.currentTimeMillis())
			.eventExpiryDuration(100)
			.build();
		memoryId = CONTROL_CLIENT.createMemory(createMemoryRequest).memory().id();
		System.out.println("Created memory: " + memoryId);

		await().atMost(Duration.ofMinutes(5)).pollInterval(Duration.ofSeconds(3)).until(() -> {
			var getMemoryRequest = GetMemoryRequest.builder().memoryId(memoryId).build();
			return CONTROL_CLIENT.getMemory(getMemoryRequest).memory().status() == MemoryStatus.ACTIVE;
		});
	}

	@AfterAll
	static void deleteMemoryResource() {
		if (memoryId != null) {
			CONTROL_CLIENT.deleteMemory(DeleteMemoryRequest.builder().memoryId(memoryId).build());
			System.out.println("Deleted memory: " + memoryId);
		}
	}

	@Test
	@DisplayName("Should bind agentcore.memory.short-term.* and not log deprecation warnings")
	void shouldBindNewShortTermNamespace(CapturedOutput output) {
		this.contextRunner.withPropertyValues("agentcore.memory.memory-id=" + memoryId,
				"agentcore.memory.short-term.total-events-limit=50",
				"agentcore.memory.short-term.default-session=stm-session", "agentcore.memory.short-term.page-size=20")
			.run((context) -> {
				assertThat(context).hasSingleBean(AgentCoreShortTermMemoryRepository.class);
				assertThat(context).hasSingleBean(BedrockAgentCoreClient.class);
				exerciseRepository(context.getBean(AgentCoreShortTermMemoryRepository.class));
				assertThat(output.getOut()).doesNotContain("is deprecated");
			});
	}

	@Test
	@DisplayName("Should fall back to legacy STM properties at agentcore.memory.* and emit deprecation warnings (#49, #109)")
	void shouldFallBackToLegacyStmProperties(CapturedOutput output) {
		this.contextRunner
			.withPropertyValues("agentcore.memory.memory-id=" + memoryId, "agentcore.memory.total-events-limit=50",
					"agentcore.memory.default-session=legacy-session", "agentcore.memory.page-size=20",
					"agentcore.memory.ignore-unknown-roles=true")
			.run((context) -> {
				assertThat(context).hasSingleBean(AgentCoreShortTermMemoryRepository.class);
				exerciseRepository(context.getBean(AgentCoreShortTermMemoryRepository.class));
				// Deprecation warnings: one per legacy STM key (#49 namespace
				// migration), plus the #109 warning because ignore-unknown-roles is
				// explicitly set.
				assertThat(output.getOut()).contains("Property 'agentcore.memory.total-events-limit' is deprecated",
						"Use 'agentcore.memory.short-term.total-events-limit' instead",
						"Property 'agentcore.memory.default-session' is deprecated",
						"Use 'agentcore.memory.short-term.default-session' instead",
						"Property 'agentcore.memory.page-size' is deprecated",
						"Use 'agentcore.memory.short-term.page-size' instead",
						"Property 'agentcore.memory.ignore-unknown-roles' is deprecated",
						"Use 'agentcore.memory.short-term.ignore-unknown-roles' instead",
						"Property 'ignore-unknown-roles' is deprecated",
						"https://github.com/spring-ai-community/spring-ai-agentcore/issues/109");
			});
	}

	private static void exerciseRepository(AgentCoreShortTermMemoryRepository repository) {
		// Round-trip a conversation against AgentCore Memory; this proves the configured
		// repository actually talks to the service with the resolved settings.
		String conversationId = "props-it-actor-" + System.nanoTime();
		repository.saveAll(conversationId, List.of(UserMessage.builder().text("hello from properties IT").build()));
		var messages = repository.findByConversationId(conversationId);
		assertThat(messages).hasSize(1);
		assertThat(messages.get(0).getText()).isEqualTo("hello from properties IT");
		repository.deleteByConversationId(conversationId);
	}

}
