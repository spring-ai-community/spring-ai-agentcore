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
import java.time.Instant;
import java.util.List;

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

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.session.EventFilter;
import org.springframework.ai.session.SessionEvent;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for {@link AgentCoreSessionRepository} against real AWS. Self
 * provisions a memory in {@link #beforeAll()} and tears it down in {@link #afterAll()};
 * skipped unless {@code AGENTCORE_IT=true}.
 */
@Tag("integration")
@EnabledIfEnvironmentVariable(named = "AGENTCORE_IT", matches = "true")
class AgentCoreSessionRepositoryIT {

	private static BedrockAgentCoreClient dataClient;

	private static BedrockAgentCoreControlClient controlClient;

	private static String memoryId;

	private static AgentCoreSessionRepository repository;

	@BeforeAll
	static void beforeAll() {
		dataClient = BedrockAgentCoreClient.create();
		controlClient = BedrockAgentCoreControlClient.create();
		String name = "session-it-" + System.currentTimeMillis();
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
	void appendAndFindEventsRoundTrip() {
		String sessionId = "alice-it:conv-" + System.nanoTime();
		SessionEvent user = SessionEvent.builder()
			.sessionId(sessionId)
			.message(UserMessage.builder().text("hello").build())
			.build();
		SessionEvent assistant = SessionEvent.builder()
			.sessionId(sessionId)
			.message(AssistantMessage.builder().content("hi there").build())
			.build();
		repository.appendEvent(user);
		repository.appendEvent(assistant);

		List<SessionEvent> events = repository.findEvents(sessionId, EventFilter.all());
		assertThat(events).hasSize(2);
		assertThat(events.get(0).getMessage().getText()).isEqualTo("hello");
		assertThat(events.get(1).getMessage().getText()).isEqualTo("hi there");

		repository.delete(sessionId);
		assertThat(repository.findEvents(sessionId, EventFilter.all())).isEmpty();
	}

	@Test
	void listSessionsCreatedAtRoundTripsIntoFindById() {
		String sessionId = "alice-it:created-" + System.nanoTime();
		repository.appendEvent(SessionEvent.builder()
			.sessionId(sessionId)
			.message(UserMessage.builder().text("seed").build())
			.build());

		// D3/C9: createdAt must be a real instant from the SessionSummary (or the tail
		// event timestamp), never the EPOCH synthetic sentinel, once events exist.
		var session = repository.findById(sessionId).orElseThrow();
		assertThat(session.createdAt()).isNotNull().isAfter(Instant.EPOCH);

		repository.delete(sessionId);
	}

	@Test
	void branchSwapReplaceEventsIsNonDestructiveAndReadsBranchNative() {
		// D1/D1.1/F8: the empty-payload pointer CreateEvent must be accepted by the live
		// service, and a branch read with includeParentBranches=FALSE must return only
		// the
		// replacement (branch-native) events, not the superseded main-line ones.
		AgentCoreSessionRepository branchSwapRepo = new AgentCoreSessionRepository(memoryId, dataClient, null,
				"default-session", 100, true, false, true, false, false, null);
		String sessionId = "alice-it:branch-" + System.nanoTime();
		branchSwapRepo.appendEvent(SessionEvent.builder()
			.sessionId(sessionId)
			.message(UserMessage.builder().text("original-1").build())
			.build());

		branchSwapRepo.replaceEvents(sessionId,
				List.of(SessionEvent.builder()
					.sessionId(sessionId)
					.message(UserMessage.builder().text("replacement-1").build())
					.build()));

		List<SessionEvent> afterSwap = branchSwapRepo.findEvents(sessionId, EventFilter.all());
		assertThat(afterSwap).hasSize(1);
		assertThat(afterSwap.get(0).getMessage().getText()).isEqualTo("replacement-1");

		branchSwapRepo.delete(sessionId);
		assertThat(branchSwapRepo.findEvents(sessionId, EventFilter.all())).isEmpty();
	}

}
