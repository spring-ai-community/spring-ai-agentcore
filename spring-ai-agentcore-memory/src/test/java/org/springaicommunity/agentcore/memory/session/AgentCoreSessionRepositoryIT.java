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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.bedrockagentcore.BedrockAgentCoreClient;
import software.amazon.awssdk.services.bedrockagentcorecontrol.BedrockAgentCoreControlClient;
import software.amazon.awssdk.services.bedrockagentcorecontrol.model.CreateMemoryRequest;
import software.amazon.awssdk.services.bedrockagentcorecontrol.model.DeleteMemoryRequest;
import software.amazon.awssdk.services.bedrockagentcorecontrol.model.GetMemoryRequest;
import software.amazon.awssdk.services.bedrockagentcorecontrol.model.MemoryStatus;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.session.DefaultSessionService;
import org.springframework.ai.session.EventFilter;
import org.springframework.ai.session.SessionEvent;
import org.springframework.ai.session.advisor.IdempotentSessionEventIdGenerator;
import org.springframework.ai.session.advisor.SessionMemoryAdvisor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration test for {@link AgentCoreSessionRepository} against real AWS. Self
 * provisions a memory in {@link #beforeAll()} and tears it down in {@link #afterAll()};
 * skipped unless {@code AGENTCORE_IT=true}.
 */
@Tag("integration")
@EnabledIfEnvironmentVariable(named = "AGENTCORE_IT", matches = "true")
class AgentCoreSessionRepositoryIT {

	private static final Logger logger = LoggerFactory.getLogger(AgentCoreSessionRepositoryIT.class);

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
		repository = AgentCoreSessionRepository.builder()
			.memoryId(memoryId)
			.client(dataClient)
			.defaultSession("default-session")
			.pageSize(100)
			.ignoreUnknownRoles(true)
			.build();
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
	void findByIdSynthesizesCreatedAtFromTailEvent() {
		String sessionId = "alice-it:created-" + System.nanoTime();
		repository.appendEvent(SessionEvent.builder()
			.sessionId(sessionId)
			.message(UserMessage.builder().text("seed").build())
			.build());

		// createdAt must be a real instant taken from the tail event timestamp, never
		// the EPOCH synthetic sentinel, once events exist.
		var session = repository.findById(sessionId);
		assertThat(session).isNotNull();
		assertThat(session.createdAt()).isNotNull().isAfter(Instant.EPOCH);

		repository.delete(sessionId);
	}

	@Test
	void findByIdUnknownSessionReturnsNull() {
		assertThat(repository.findById("alice-it:missing-" + System.nanoTime())).isNull();
	}

	@Test
	void compactEventsIsUnsupported() {
		// compactEvents throws before any AWS call: AgentCore events are immutable and
		// the log has no compare-and-set, so the repository refuses to rewrite the log.
		String sessionId = "alice-it:compact-" + System.nanoTime();
		List<SessionEvent> retained = List.of(SessionEvent.builder()
			.sessionId(sessionId)
			.message(UserMessage.builder().text("summary-1").build())
			.build());

		assertThatThrownBy(() -> repository.compactEvents(sessionId, List.of(), retained, 1L))
			.isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("compactEvents is unsupported");
		assertThat(repository.findEvents(sessionId, EventFilter.all())).isEmpty();
	}

	// ==================== appendEvent idempotency (clientToken) ====================
	// These pin the AgentCore behavior that the appendEvent Javadoc and the README
	// divergence table describe. A failure here means the documentation is wrong, not
	// only the code: update both together.

	@Test
	void retriedAppendWithTheSameIdStoresOneEvent() {
		// A retry rebuilds the event: fresh Message, later timestamp, same id.
		String sessionId = "alice-it:retry-" + System.nanoTime();
		Instant first = Instant.now().minusSeconds(10);
		UserMessage original = UserMessage.builder().text("hello").build();
		UserMessage retried = UserMessage.builder().text("hello").build();

		repository.appendEvent(event("evt-retry", sessionId, first, original));
		repository.appendEvent(event("evt-retry", sessionId, first.plusSeconds(5), retried));

		List<SessionEvent> events = repository.findEvents(sessionId, EventFilter.all());
		logger.info("Retried append: stored {} event(s); original eventId {}, retried eventId {}", events.size(),
				original.getMetadata().get(AgentCoreSessionRepository.EVENT_ID_METADATA_KEY),
				retried.getMetadata().get(AgentCoreSessionRepository.EVENT_ID_METADATA_KEY));
		assertThat(events).hasSize(1);
		assertThat(events.get(0).getMessage().getText()).isEqualTo("hello");

		repository.delete(sessionId);
	}

	@Test
	void sameIdWithDifferentTextIsIgnoredWithoutAnError() {
		// The token deliberately excludes the payload, and the CreateEvent reference says
		// a repeated token is ignored without an error. If AgentCore instead rejects a
		// mismatched payload, appendEvent throws a StorageException here and the
		// "Idempotency (best effort)" Javadoc must say so.
		String sessionId = "alice-it:mismatch-" + System.nanoTime();
		Instant first = Instant.now().minusSeconds(10);
		repository.appendEvent(event("evt-mismatch", sessionId, first, UserMessage.builder().text("hello").build()));

		repository.appendEvent(
				event("evt-mismatch", sessionId, first.plusSeconds(5), UserMessage.builder().text("goodbye").build()));

		assertThat(repository.findEvents(sessionId, EventFilter.all())).extracting((e) -> e.getMessage().getText())
			.containsExactly("hello");

		repository.delete(sessionId);
	}

	@Test
	void reappendAfterDeleteWithTheSameIdIsIgnored() {
		// Documented divergence: AgentCore still remembers the token after the event is
		// deleted, so reusing a sessionId with the same event ids loses those messages.
		String sessionId = "alice-it:reuse-" + System.nanoTime();
		repository.appendEvent(event("evt-reuse", sessionId, null, UserMessage.builder().text("hello").build()));
		repository.delete(sessionId);
		assertThat(repository.findEvents(sessionId, EventFilter.all())).isEmpty();

		repository.appendEvent(event("evt-reuse", sessionId, null, UserMessage.builder().text("hello").build()));

		List<SessionEvent> events = repository.findEvents(sessionId, EventFilter.all());
		logger.info("Re-append after delete: stored {} event(s)", events.size());
		assertThat(events).isEmpty();
	}

	@Test
	void idempotentAdvisorRetryStoresOneUserEvent() {
		String sessionId = "alice-it:advisor-" + System.nanoTime();
		IdempotentSessionEventIdGenerator ids = new IdempotentSessionEventIdGenerator();
		SessionMemoryAdvisor advisor = SessionMemoryAdvisor
			.builder(DefaultSessionService.builder().sessionRepository(repository).build())
			.requestEventIdGenerator(ids)
			.responseEventIdGenerator(ids)
			.build();

		advisor.before(userTurn(sessionId, "book a table"), null);
		advisor.before(userTurn(sessionId, "book a table"), null);

		assertThat(repository.findEvents(sessionId, EventFilter.all())).extracting((e) -> e.getMessage().getText())
			.containsExactly("book a table");

		repository.delete(sessionId);
	}

	private static SessionEvent event(String id, String sessionId, Instant timestamp, UserMessage message) {
		SessionEvent.Builder builder = SessionEvent.builder().id(id).sessionId(sessionId).message(message);
		if (timestamp != null) {
			builder.timestamp(timestamp);
		}
		return builder.build();
	}

	private static ChatClientRequest userTurn(String sessionId, String text) {
		return ChatClientRequest.builder()
			.prompt(new Prompt(List.of(new UserMessage(text))))
			.context(SessionMemoryAdvisor.SESSION_ID_CONTEXT_KEY, sessionId)
			.build();
	}

}
