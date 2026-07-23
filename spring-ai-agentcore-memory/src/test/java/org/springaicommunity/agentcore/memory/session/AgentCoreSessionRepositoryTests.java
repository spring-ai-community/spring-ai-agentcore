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

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import software.amazon.awssdk.services.bedrockagentcore.BedrockAgentCoreClient;
import software.amazon.awssdk.services.bedrockagentcore.model.Content;
import software.amazon.awssdk.services.bedrockagentcore.model.Conversational;
import software.amazon.awssdk.services.bedrockagentcore.model.CreateEventRequest;
import software.amazon.awssdk.services.bedrockagentcore.model.CreateEventResponse;
import software.amazon.awssdk.services.bedrockagentcore.model.DeleteEventRequest;
import software.amazon.awssdk.services.bedrockagentcore.model.Event;
import software.amazon.awssdk.services.bedrockagentcore.model.ListEventsRequest;
import software.amazon.awssdk.services.bedrockagentcore.model.ListEventsResponse;
import software.amazon.awssdk.services.bedrockagentcore.model.ListSessionsRequest;
import software.amazon.awssdk.services.bedrockagentcore.model.ListSessionsResponse;
import software.amazon.awssdk.services.bedrockagentcore.model.PayloadType;
import software.amazon.awssdk.services.bedrockagentcore.model.Role;
import software.amazon.awssdk.services.bedrockagentcore.model.SessionSummary;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.session.DefaultSessionService;
import org.springframework.ai.session.EventFilter;
import org.springframework.ai.session.Session;
import org.springframework.ai.session.SessionEvent;
import org.springframework.ai.session.advisor.SessionMemoryAdvisor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

/**
 * Unit tests for {@link AgentCoreSessionRepository}. Includes the two advisor-integration
 * tests that exercise the C1 hard precondition end-to-end through the real
 * {@link SessionMemoryAdvisor#before}.
 *
 * <p>
 * Since the branch-swap (v2) rework, every read/write path first runs a pointer-ledger
 * discovery scan (a metadata-filtered {@code listEvents}). Tests stub that discovery scan
 * separately from data reads via the {@link #isLedgerScan(ListEventsRequest)} matcher, so
 * a session with no pointer markers resolves to the main line (v1 behavior).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AgentCoreSessionRepositoryTests {

	private static final String MEMORY_ID = "testMemoryId";

	private static final String SESSION_ID = "alice:conv-1";

	private static final String ACTOR = "alice";

	private static final String SESSION_SUFFIX = "conv-1";

	@Mock
	private BedrockAgentCoreClient client;

	private AgentCoreSessionRepository repository;

	@BeforeEach
	void setUp() {
		this.repository = new AgentCoreSessionRepository(MEMORY_ID, this.client, null, "default-session", 100, true,
				false);
		// Default: no pointer markers -> main-line (v1) session for every read path.
		givenNoLedgerMarkers();
	}

	// ==================== save / findById / findByUserId / findExpiredSessionIds ==

	@Test
	void saveReturnsSameSessionAndDoesNotCallClient() {
		Session session = Session.builder().id(SESSION_ID).userId(ACTOR).build();
		Session saved = this.repository.save(session);
		assertThat(saved).isSameAs(session);
		then(this.client).shouldHaveNoInteractions();
	}

	@Test
	void findByIdExistingSessionIdReturnsSynthesizedSession() {
		Instant summaryTs = Instant.parse("2026-04-01T08:00:00Z");
		Instant eventTs = Instant.parse("2026-05-01T12:00:00Z");
		Event tail = Event.builder().eventId("evt-1").eventTimestamp(eventTs).build();
		givenDataEvents(tail);
		givenSessionSummary(SESSION_SUFFIX, summaryTs);

		Optional<Session> result = this.repository.findById(SESSION_ID);

		assertThat(result).isPresent();
		Session synthesized = result.get();
		assertThat(synthesized.id()).isEqualTo(SESSION_ID);
		assertThat(synthesized.userId()).isEqualTo(ACTOR);
		// D3: createdAt is the real SessionSummary.createdAt, not an EPOCH sentinel.
		assertThat(synthesized.createdAt()).isEqualTo(summaryTs);
		assertThat(synthesized.expiresAt()).isNull();
		assertThat(synthesized.metadata()).containsEntry(AgentCoreSessionRepository.ACTOR_ID_METADATA_KEY, ACTOR)
			.containsEntry(AgentCoreSessionRepository.SESSION_METADATA_KEY, SESSION_SUFFIX)
			.containsEntry(AgentCoreSessionRepository.LAST_EVENT_AT_METADATA_KEY, eventTs);

		ListEventsRequest tailReq = captureDataListEvents();
		assertThat(tailReq.actorId()).isEqualTo(ACTOR);
		assertThat(tailReq.sessionId()).isEqualTo(SESSION_SUFFIX);
		assertThat(tailReq.memoryId()).isEqualTo(MEMORY_ID);
		assertThat(tailReq.maxResults()).isEqualTo(1);
		assertThat(tailReq.includePayloads()).isFalse();
	}

	@Test
	void findByIdCreatedAtFallsBackToTailTimestampWhenNoSummary() {
		// D3: no matching SessionSummary -> createdAt falls back to the tail event
		// timestamp (a real, non-sentinel value), never Instant.EPOCH.
		Instant eventTs = Instant.parse("2026-06-15T00:00:00Z");
		Event tail = Event.builder().eventId("evt-2").eventTimestamp(eventTs).build();
		givenDataEvents(tail);
		given(this.client.listSessions(any(ListSessionsRequest.class)))
			.willReturn(ListSessionsResponse.builder().sessionSummaries(List.of()).build());

		Session synthesized = this.repository.findById(SESSION_ID).orElseThrow();
		assertThat(synthesized.createdAt()).isEqualTo(eventTs);
		assertThat(synthesized.createdAt()).isNotEqualTo(Instant.EPOCH);
		assertThat(synthesized.metadata().get(AgentCoreSessionRepository.LAST_EVENT_AT_METADATA_KEY))
			.isEqualTo(eventTs);
	}

	@Test
	void findByIdUnknownSessionIdReturnsEmpty() {
		givenDataEvents();
		assertThat(this.repository.findById(SESSION_ID)).isEmpty();
	}

	@Test
	void findByIdActorOnlyConversationIdUsesDefaultSession() {
		Event tail = Event.builder().eventId("evt-3").eventTimestamp(Instant.now()).build();
		givenDataEvents(tail);

		this.repository.findById("alice");

		ListEventsRequest tailReq = captureDataListEvents();
		assertThat(tailReq.actorId()).isEqualTo("alice");
		assertThat(tailReq.sessionId()).isEqualTo("default-session");
	}

	@Test
	void findByUserIdReturnsSessionsFromPaginatedListSessions() {
		// D2: findByUserId maps userId -> actor and paginates listSessions.
		Instant t1 = Instant.parse("2026-01-01T00:00:00Z");
		Instant t2 = Instant.parse("2026-02-01T00:00:00Z");
		SessionSummary s1 = SessionSummary.builder().sessionId("conv-1").actorId(ACTOR).createdAt(t1).build();
		SessionSummary s2 = SessionSummary.builder().sessionId("conv-2").actorId(ACTOR).createdAt(t2).build();
		given(this.client.listSessions(any(ListSessionsRequest.class)))
			.willReturn(ListSessionsResponse.builder().sessionSummaries(s1).nextToken("p2").build())
			.willReturn(ListSessionsResponse.builder().sessionSummaries(s2).build());

		List<Session> sessions = this.repository.findByUserId(ACTOR);

		assertThat(sessions).hasSize(2);
		assertThat(sessions).extracting(Session::id).containsExactly("alice:conv-1", "alice:conv-2");
		assertThat(sessions).allSatisfy((s) -> assertThat(s.userId()).isEqualTo(ACTOR));
		assertThat(sessions.get(0).createdAt()).isEqualTo(t1);
		assertThat(sessions.get(1).createdAt()).isEqualTo(t2);
	}

	@Test
	void findByUserIdEmptyResultReturnsEmptyList() {
		given(this.client.listSessions(any(ListSessionsRequest.class)))
			.willReturn(ListSessionsResponse.builder().sessionSummaries(List.of()).build());
		assertThat(this.repository.findByUserId("nobody")).isEmpty();
	}

	@Test
	void findByUserIdRejectsBlankUserId() {
		assertThatThrownBy(() -> this.repository.findByUserId("  ")).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void findExpiredSessionIdsThrowsUnsupportedWithHelpfulMessage() {
		assertThatThrownBy(() -> this.repository.findExpiredSessionIds(Instant.now()))
			.isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("eventExpiryDuration")
			.hasMessageContaining("findByUserId");
	}

	// ==================== delete ====================

	@Test
	void deletePaginatesAndDeletes() {
		List<Event> firstPage = IntStream.range(0, 10).mapToObj((i) -> eventWithId("evt-" + i)).toList();
		List<Event> secondPage = IntStream.range(10, 15).mapToObj((i) -> eventWithId("evt-" + i)).toList();
		given(this.client.listEvents(argThat((ListEventsRequest r) -> !isLedgerScan(r))))
			.willReturn(ListEventsResponse.builder().events(firstPage).nextToken("page2").build())
			.willReturn(ListEventsResponse.builder().events(secondPage).build());

		this.repository.delete(SESSION_ID);

		ArgumentCaptor<DeleteEventRequest> deleteCaptor = ArgumentCaptor.forClass(DeleteEventRequest.class);
		then(this.client).should(times(15)).deleteEvent(deleteCaptor.capture());
		List<String> deletedIds = deleteCaptor.getAllValues().stream().map(DeleteEventRequest::eventId).toList();
		assertThat(deletedIds)
			.containsExactlyInAnyOrderElementsOf(IntStream.range(0, 15).mapToObj((i) -> "evt-" + i).toList());
	}

	// ==================== appendEvent ====================

	@Test
	void appendEventWritesEventWithPayloadFromMessage() {
		CreateEventResponse response = CreateEventResponse.builder()
			.event(Event.builder().eventId("new-1").build())
			.build();
		given(this.client.createEvent(any(CreateEventRequest.class))).willReturn(response);

		SessionEvent event = SessionEvent.builder()
			.sessionId(SESSION_ID)
			.message(UserMessage.builder().text("hi").build())
			.build();
		this.repository.appendEvent(event);

		ArgumentCaptor<CreateEventRequest> captor = ArgumentCaptor.forClass(CreateEventRequest.class);
		then(this.client).should().createEvent(captor.capture());
		CreateEventRequest req = captor.getValue();
		assertThat(req.actorId()).isEqualTo(ACTOR);
		assertThat(req.sessionId()).isEqualTo(SESSION_SUFFIX);
		assertThat(req.memoryId()).isEqualTo(MEMORY_ID);
		assertThat(req.payload()).hasSize(1);
		assertThat(req.payload().get(0).conversational().role()).isEqualTo(Role.USER);
		assertThat(req.payload().get(0).conversational().content().text()).isEqualTo("hi");
		// D1.3: main-line/v1 session appends with NO branch set.
		assertThat(req.branch()).isNull();
		assertThat(req.clientToken()).isNotBlank();
	}

	@Test
	void appendEventUnknownSessionDoesNotThrow() {
		// AgentCore has no notion of session existence separate from events; unlike the
		// SessionRepository SPI Javadoc, appendEvent does NOT throw on unknown sessions.
		CreateEventResponse response = CreateEventResponse.builder()
			.event(Event.builder().eventId("new-1").build())
			.build();
		given(this.client.createEvent(any(CreateEventRequest.class))).willReturn(response);

		SessionEvent event = SessionEvent.builder()
			.sessionId(SESSION_ID)
			.message(UserMessage.builder().text("first turn").build())
			.build();
		this.repository.appendEvent(event);

		then(this.client).should(times(1)).createEvent(any(CreateEventRequest.class));
	}

	@Test
	void appendEventSkipsSyntheticByDefault() {
		SessionEvent syntheticEvent = SessionEvent.builder()
			.sessionId(SESSION_ID)
			.message(AssistantMessage.builder().content("summary").build())
			.metadata(SessionEvent.METADATA_SYNTHETIC, true)
			.build();
		this.repository.appendEvent(syntheticEvent);
		then(this.client).should(never()).createEvent(any(CreateEventRequest.class));
	}

	@Test
	void appendEventPersistsSyntheticWhenEnabled() {
		AgentCoreSessionRepository persistingRepo = new AgentCoreSessionRepository(MEMORY_ID, this.client, null,
				"default-session", 100, true, true);
		CreateEventResponse response = CreateEventResponse.builder()
			.event(Event.builder().eventId("syn-1").build())
			.build();
		given(this.client.createEvent(any(CreateEventRequest.class))).willReturn(response);

		SessionEvent syntheticEvent = SessionEvent.builder()
			.sessionId(SESSION_ID)
			.message(AssistantMessage.builder().content("summary").build())
			.metadata(SessionEvent.METADATA_SYNTHETIC, true)
			.build();
		persistingRepo.appendEvent(syntheticEvent);

		then(this.client).should(times(1)).createEvent(any(CreateEventRequest.class));
	}

	@Test
	void appendEventSkipsAlreadyStamped() {
		Map<String, Object> stamped = new HashMap<>();
		stamped.put(AgentCoreSessionRepository.EVENT_ID_METADATA_KEY, "already");
		UserMessage message = UserMessage.builder().text("hi").metadata(stamped).build();
		SessionEvent event = SessionEvent.builder().sessionId(SESSION_ID).message(message).build();

		this.repository.appendEvent(event);
		then(this.client).should(never()).createEvent(any(CreateEventRequest.class));
	}

	@Test
	void appendEventStampsEventIdOnMessageMetadata() {
		CreateEventResponse response = CreateEventResponse.builder()
			.event(Event.builder().eventId("stamped-eventId").build())
			.build();
		given(this.client.createEvent(any(CreateEventRequest.class))).willReturn(response);

		UserMessage message = UserMessage.builder().text("hi").build();
		SessionEvent event = SessionEvent.builder().sessionId(SESSION_ID).message(message).build();
		this.repository.appendEvent(event);

		assertThat(message.getMetadata().get(AgentCoreSessionRepository.EVENT_ID_METADATA_KEY))
			.isEqualTo("stamped-eventId");
	}

	// ==================== replaceEvents (legacy, branch-swap disabled default) =========

	@Test
	void replaceEventsDeletesThenCreatesOrdered() {
		// Default repo has branch-swap DISABLED; a true v1 session (no markers) takes the
		// legacy delete-then-recreate path.
		Event existing = eventWithId("old-1");
		given(this.client.listEvents(argThat((ListEventsRequest r) -> !isLedgerScan(r))))
			.willReturn(ListEventsResponse.builder().events(existing).build());
		given(this.client.createEvent(any(CreateEventRequest.class)))
			.willReturn(CreateEventResponse.builder().event(Event.builder().eventId("new-1").build()).build());

		SessionEvent newEvent = SessionEvent.builder()
			.sessionId(SESSION_ID)
			.message(UserMessage.builder().text("new").build())
			.build();
		this.repository.replaceEvents(SESSION_ID, List.of(newEvent));

		InOrder inOrder = Mockito.inOrder(this.client);
		inOrder.verify(this.client).deleteEvent(any(DeleteEventRequest.class));
		inOrder.verify(this.client).createEvent(any(CreateEventRequest.class));
	}

	// ==================== replaceEvents (CAS overload) ====================

	@Test
	void replaceEventsVersionMatchReturnsTrue() {
		Event existing = eventWithId("old-1");
		given(this.client.listEvents(argThat((ListEventsRequest r) -> !isLedgerScan(r))))
			.willReturn(ListEventsResponse.builder().events(existing).build());
		given(this.client.createEvent(any(CreateEventRequest.class)))
			.willReturn(CreateEventResponse.builder().event(Event.builder().eventId("new-1").build()).build());

		SessionEvent newEvent = SessionEvent.builder()
			.sessionId(SESSION_ID)
			.message(UserMessage.builder().text("replacement").build())
			.build();
		boolean result = this.repository.replaceEvents(SESSION_ID, List.of(newEvent), 1L);
		assertThat(result).isTrue();
		then(this.client).should().deleteEvent(any(DeleteEventRequest.class));
		then(this.client).should().createEvent(any(CreateEventRequest.class));
	}

	@Test
	void replaceEventsVersionMismatchReturnsFalseNoWrites() {
		List<Event> threeEvents = List.of(eventWithId("e1"), eventWithId("e2"), eventWithId("e3"));
		given(this.client.listEvents(argThat((ListEventsRequest r) -> !isLedgerScan(r))))
			.willReturn(ListEventsResponse.builder().events(threeEvents).build());

		SessionEvent newEvent = SessionEvent.builder()
			.sessionId(SESSION_ID)
			.message(UserMessage.builder().text("replacement").build())
			.build();
		boolean result = this.repository.replaceEvents(SESSION_ID, List.of(newEvent), 2L);
		assertThat(result).isFalse();
		then(this.client).should(never()).deleteEvent(any(DeleteEventRequest.class));
		then(this.client).should(never()).createEvent(any(CreateEventRequest.class));
	}

	// ==================== getEventVersion ====================

	@Test
	void getEventVersionMatchesEventCount() {
		List<Event> firstPage = IntStream.range(0, 3).mapToObj((i) -> eventWithId("e" + i)).toList();
		List<Event> secondPage = IntStream.range(3, 5).mapToObj((i) -> eventWithId("e" + i)).toList();
		given(this.client.listEvents(argThat((ListEventsRequest r) -> !isLedgerScan(r))))
			.willReturn(ListEventsResponse.builder().events(firstPage).nextToken("next").build())
			.willReturn(ListEventsResponse.builder().events(secondPage).build());

		long version = this.repository.getEventVersion(SESSION_ID);
		assertThat(version).isEqualTo(5L);
	}

	@Test
	void getEventVersionEmptySessionReturnsZero() {
		givenDataEvents();
		assertThat(this.repository.getEventVersion(SESSION_ID)).isEqualTo(0L);
	}

	@Test
	void getEventVersionAfterReplaceEventsMatchesNewSize() {
		List<Event> initial = List.of(eventWithId("old-1"), eventWithId("old-2"), eventWithId("old-3"));
		List<Event> afterReplace = List.of(eventWithId("new-1"), eventWithId("new-2"));
		given(this.client.listEvents(argThat((ListEventsRequest r) -> !isLedgerScan(r))))
			.willReturn(ListEventsResponse.builder().events(initial).build())
			.willReturn(ListEventsResponse.builder().events(afterReplace).build());
		given(this.client.createEvent(any(CreateEventRequest.class)))
			.willReturn(CreateEventResponse.builder().event(Event.builder().eventId("stamp").build()).build());

		SessionEvent e1 = SessionEvent.builder()
			.sessionId(SESSION_ID)
			.message(UserMessage.builder().text("a").build())
			.build();
		SessionEvent e2 = SessionEvent.builder()
			.sessionId(SESSION_ID)
			.message(UserMessage.builder().text("b").build())
			.build();
		this.repository.replaceEvents(SESSION_ID, List.of(e1, e2));

		long postVersion = this.repository.getEventVersion(SESSION_ID);
		assertThat(postVersion).isEqualTo(2L);
	}

	// ==================== findEvents ====================

	@Test
	void findEventsReturnsChronologicalOrder() {
		Event newest = payloadEvent("e-3", "third", Role.USER, Instant.parse("2026-01-03T00:00:00Z"));
		Event middle = payloadEvent("e-2", "second", Role.ASSISTANT, Instant.parse("2026-01-02T00:00:00Z"));
		Event oldest = payloadEvent("e-1", "first", Role.USER, Instant.parse("2026-01-01T00:00:00Z"));
		givenDataEvents(newest, middle, oldest);

		List<SessionEvent> events = this.repository.findEvents(SESSION_ID, EventFilter.all());
		assertThat(events).extracting((e) -> e.getMessage().getText()).containsExactly("first", "second", "third");
		assertThat(events).extracting(SessionEvent::getId).containsExactly("e-1", "e-2", "e-3");
	}

	@Test
	void findEventsRespectsLastN() {
		Event e1 = payloadEvent("e-1", "first", Role.USER, Instant.parse("2026-01-01T00:00:00Z"));
		Event e2 = payloadEvent("e-2", "second", Role.USER, Instant.parse("2026-01-02T00:00:00Z"));
		Event e3 = payloadEvent("e-3", "third", Role.USER, Instant.parse("2026-01-03T00:00:00Z"));
		givenDataEvents(e3, e2, e1);

		List<SessionEvent> events = this.repository.findEvents(SESSION_ID, EventFilter.lastN(2));
		assertThat(events).extracting((e) -> e.getMessage().getText()).containsExactly("second", "third");
	}

	@Test
	void findEventsAppliesInMemoryFilter() {
		Event userEvent = payloadEvent("e-1", "user-text", Role.USER, Instant.parse("2026-01-01T00:00:00Z"));
		Event assistantEvent = payloadEvent("e-2", "assistant-text", Role.ASSISTANT,
				Instant.parse("2026-01-02T00:00:00Z"));
		givenDataEvents(assistantEvent, userEvent);

		EventFilter filter = EventFilter.builder().messageTypes(Set.of(MessageType.USER)).build();
		List<SessionEvent> events = this.repository.findEvents(SESSION_ID, filter);
		assertThat(events).hasSize(1);
		assertThat(events.get(0).getMessage().getText()).isEqualTo("user-text");
	}

	@Test
	void findEventsNonExistentSessionReturnsEmptyList() {
		givenDataEvents();
		assertThat(this.repository.findEvents(SESSION_ID, EventFilter.all())).isEmpty();
	}

	// ==================== C1 advisor-integration tests ====================

	@Test
	void findByIdMatchingUserIdContextAppendMessagePasses() {
		Event tail = payloadEvent("e-1", "hi", Role.USER, Instant.parse("2026-01-01T00:00:00Z"));
		given(this.client.listEvents(argThat((ListEventsRequest r) -> !isLedgerScan(r)))).willReturn(emptyPage())
			.willReturn(ListEventsResponse.builder().events(tail).build());
		given(this.client.createEvent(any(CreateEventRequest.class)))
			.willReturn(CreateEventResponse.builder().event(Event.builder().eventId("stamp").build()).build());

		DefaultSessionService service = DefaultSessionService.builder().sessionRepository(this.repository).build();
		SessionMemoryAdvisor advisor = SessionMemoryAdvisor.builder(service).build();

		ChatClientRequest turn1 = ChatClientRequest.builder()
			.prompt(new Prompt(List.of(new UserMessage("first"))))
			.context(SessionMemoryAdvisor.SESSION_ID_CONTEXT_KEY, SESSION_ID)
			.context(SessionMemoryAdvisor.USER_ID_CONTEXT_KEY, ACTOR)
			.build();
		advisor.before(turn1, null);

		ChatClientRequest turn2 = ChatClientRequest.builder()
			.prompt(new Prompt(List.of(new UserMessage("second"))))
			.context(SessionMemoryAdvisor.SESSION_ID_CONTEXT_KEY, SESSION_ID)
			.context(SessionMemoryAdvisor.USER_ID_CONTEXT_KEY, ACTOR)
			.build();
		advisor.before(turn2, null);

		then(this.client).should(Mockito.atLeastOnce()).listEvents(any(ListEventsRequest.class));
	}

	@Test
	void findByIdMismatchedUserIdContextAdvisorThrowsIllegalStateException() {
		Event tail = payloadEvent("e-1", "hi", Role.USER, Instant.parse("2026-01-01T00:00:00Z"));
		given(this.client.listEvents(argThat((ListEventsRequest r) -> !isLedgerScan(r)))).willReturn(emptyPage())
			.willReturn(ListEventsResponse.builder().events(tail).build());
		given(this.client.createEvent(any(CreateEventRequest.class)))
			.willReturn(CreateEventResponse.builder().event(Event.builder().eventId("stamp").build()).build());

		DefaultSessionService service = DefaultSessionService.builder().sessionRepository(this.repository).build();
		SessionMemoryAdvisor advisor = SessionMemoryAdvisor.builder(service).build();

		ChatClientRequest turn1 = ChatClientRequest.builder()
			.prompt(new Prompt(List.of(new UserMessage("first"))))
			.context(SessionMemoryAdvisor.SESSION_ID_CONTEXT_KEY, SESSION_ID)
			.context(SessionMemoryAdvisor.USER_ID_CONTEXT_KEY, "bob")
			.build();
		advisor.before(turn1, null);

		ChatClientRequest turn2 = ChatClientRequest.builder()
			.prompt(new Prompt(List.of(new UserMessage("second"))))
			.context(SessionMemoryAdvisor.SESSION_ID_CONTEXT_KEY, SESSION_ID)
			.context(SessionMemoryAdvisor.USER_ID_CONTEXT_KEY, "bob")
			.build();
		assertThatThrownBy(() -> advisor.before(turn2, null)).isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("does not belong to user 'bob'");
	}

	// ==================== Helpers ====================

	/** A pointer-ledger discovery scan carries a metadata (EXISTS) filter. */
	static boolean isLedgerScan(ListEventsRequest req) {
		return req != null && req.filter() != null && req.filter().eventMetadata() != null;
	}

	private void givenNoLedgerMarkers() {
		given(this.client.listEvents(argThat((ListEventsRequest r) -> isLedgerScan(r)))).willReturn(emptyPage());
	}

	private void givenDataEvents(Event... events) {
		given(this.client.listEvents(argThat((ListEventsRequest r) -> !isLedgerScan(r))))
			.willReturn(ListEventsResponse.builder().events(List.of(events)).build());
	}

	private void givenSessionSummary(String sessionSuffix, Instant createdAt) {
		SessionSummary summary = SessionSummary.builder()
			.sessionId(sessionSuffix)
			.actorId(ACTOR)
			.createdAt(createdAt)
			.build();
		given(this.client.listSessions(any(ListSessionsRequest.class)))
			.willReturn(ListSessionsResponse.builder().sessionSummaries(summary).build());
	}

	private ListEventsRequest captureDataListEvents() {
		ArgumentCaptor<ListEventsRequest> captor = ArgumentCaptor.forClass(ListEventsRequest.class);
		then(this.client).should(Mockito.atLeastOnce()).listEvents(captor.capture());
		return captor.getAllValues().stream().filter((r) -> !isLedgerScan(r)).reduce((a, b) -> b).orElseThrow();
	}

	private static Event eventWithId(String id) {
		return Event.builder().memoryId(MEMORY_ID).eventId(id).build();
	}

	private static Event payloadEvent(String id, String text, Role role, Instant timestamp) {
		Conversational conv = Conversational.builder().role(role).content(Content.builder().text(text).build()).build();
		return Event.builder()
			.memoryId(MEMORY_ID)
			.eventId(id)
			.eventTimestamp(timestamp)
			.payload(PayloadType.builder().conversational(conv).build())
			.build();
	}

	private static ListEventsResponse emptyPage() {
		return ListEventsResponse.builder().events(List.of()).build();
	}

}
