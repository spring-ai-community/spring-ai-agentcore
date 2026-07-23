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

package org.springaicommunity.agentcore.memory.session;

import java.time.Duration;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import software.amazon.awssdk.services.bedrockagentcore.BedrockAgentCoreClient;
import software.amazon.awssdk.services.bedrockagentcorecontrol.BedrockAgentCoreControlClient;
import software.amazon.awssdk.services.bedrockagentcorecontrol.model.CreateMemoryRequest;
import software.amazon.awssdk.services.bedrockagentcorecontrol.model.DeleteMemoryRequest;
import software.amazon.awssdk.services.bedrockagentcorecontrol.model.GetMemoryRequest;
import software.amazon.awssdk.services.bedrockagentcorecontrol.model.MemoryStatus;

import org.springframework.ai.bedrock.converse.BedrockProxyChatModel;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.session.DefaultSessionService;
import org.springframework.ai.session.SessionService;
import org.springframework.ai.session.advisor.SessionMemoryAdvisor;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end integration test that wires a real {@link ChatClient} with
 * {@link SessionMemoryAdvisor} backed by AgentCore and verifies name recall across turns.
 * Skipped unless {@code AGENTCORE_IT=true}.
 */
@Tag("integration")
@EnabledIfEnvironmentVariable(named = "AGENTCORE_IT", matches = "true")
class EndToEndSpringAISessionChatClientIntegrationTests {

	private static BedrockAgentCoreClient dataClient;

	private static BedrockAgentCoreControlClient controlClient;

	private static String memoryId;

	private static AgentCoreSessionRepository repository;

	@BeforeAll
	static void beforeAll() {
		dataClient = BedrockAgentCoreClient.create();
		controlClient = BedrockAgentCoreControlClient.create();
		String name = "session-e2e-" + System.currentTimeMillis();
		var created = controlClient
			.createMemory(CreateMemoryRequest.builder().name(name).eventExpiryDuration(90).build());
		memoryId = created.memory().id();
		Awaitility.await()
			.atMost(Duration.ofMinutes(5))
			.pollInterval(Duration.ofSeconds(10))
			.until(() -> controlClient.getMemory(GetMemoryRequest.builder().memoryId(memoryId).build())
				.memory()
				.status() == MemoryStatus.ACTIVE);
		repository = new AgentCoreSessionRepository(memoryId, dataClient, null, "default-session", 100, true, false);
	}

	@AfterAll
	static void afterAll() {
		if (memoryId != null && controlClient != null) {
			controlClient.deleteMemory(DeleteMemoryRequest.builder().memoryId(memoryId).build());
		}
		if (dataClient != null) {
			dataClient.close();
		}
		if (controlClient != null) {
			controlClient.close();
		}
	}

	@Test
	void chatClientRemembersNameAcrossTurns() {
		String sessionId = "alice:e2e-" + System.nanoTime();
		SessionService sessionService = DefaultSessionService.builder().sessionRepository(repository).build();
		SessionMemoryAdvisor advisor = SessionMemoryAdvisor.builder(sessionService).build();

		BedrockProxyChatModel model = BedrockProxyChatModel.builder().build();
		ChatClient client = ChatClient.builder(model).defaultAdvisors(advisor).build();

		client.prompt()
			.user("My name is Diego. Remember it.")
			.advisors((a) -> a.param(SessionMemoryAdvisor.SESSION_ID_CONTEXT_KEY, sessionId)
				.param(SessionMemoryAdvisor.USER_ID_CONTEXT_KEY, "alice"))
			.call()
			.content();

		String reply = client.prompt()
			.user("What is my name?")
			.advisors((a) -> a.param(SessionMemoryAdvisor.SESSION_ID_CONTEXT_KEY, sessionId)
				.param(SessionMemoryAdvisor.USER_ID_CONTEXT_KEY, "alice"))
			.call()
			.content();

		assertThat(reply).containsIgnoringCase("Diego");
	}

}
