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
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springaicommunity.agentcore.memory.AgentCoreMemoryException;
import software.amazon.awssdk.core.exception.SdkException;
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
 * The repository is append-only: {@code replaceEvents} always throws
 * {@link UnsupportedOperationException}, synthetic events are never persisted, and reads
 * are bounded via read-windowing ({@code totalEventsLimit}, {@code EventFilter.lastN})
 * with an early pagination stop for plain {@code lastN} queries.
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
		this.repository = AgentCoreSessionRepository.builder()
			.memoryId(MEMORY_ID)
			.client(this.client)
			.defaultSession("default-session")
			.pageSize(100)
			.ignoreUnknownRoles(true)
			.build();
	}

	// ==================== builder ====================

	@Test
	void builderRequiresMemoryIdAndClient() {
		assertThatThrownBy(() -> AgentCoreSessionRepository.builder().client(this.client).build())
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> AgentCoreSessionRepository.builder().memoryId(MEMORY_ID).build())
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void builderRejectsInvalidPageSizeTotalEventsLimitAndBlankDefaultSession() {
		assertThatThrownBy(
				() -> AgentCoreSessionRepository.builder().memoryId(MEMORY_ID).client(this.client).pageSize(0).build())
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("pageSize");
		assertThatThrownBy(() -> AgentCoreSessionRepository.builder()
			.memoryId(MEMORY_ID)
			.client(this.client)
			.totalEventsLimit(0)
			.build()).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("totalEventsLimit");
		assertThatThrownBy(() -> AgentCoreSessionRepository.builder()
			.memoryId(MEMORY_ID)
			.client(this.client)
			.defaultSession("  ")
			.build()).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("defaultSession");
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
		Instant eventTs = Instant.parse("2026-05-01T12:00:00Z");
		Event tail = Event.builder().eventId("evt-1").eventTimestamp(eventTs).build();
		this.givenDataEvents(tail);

		Optional<Session> result = this.repository.findById(SESSION_ID);

		assertThat(result).isPresent();
		Session synthesized = result.get();
		assertThat(synthesized.id()).isEqualTo(SESSION_ID);
		assertThat(synthesized.userId()).isEqualTo(ACTOR);
		// I4: createdAt is the tail event timestamp (already fetched), not a ListSessions
		// lookup and not an EPOCH sentinel.
		assertThat(synthesized.createdAt()).isEqualTo(eventTs);
		assertThat(synthesized.expiresAt()).isNull();
		assertThat(synthesized.metadata()).containsEntry(AgentCoreSessionRepository.ACTOR_ID_METADATA_KEY, ACTOR)
			.containsEntry(AgentCoreSessionRepository.SESSION_METADATA_KEY, SESSION_SUFFIX)
			.containsEntry(AgentCoreSessionRepository.LAST_EVENT_AT_METADATA_KEY, eventTs);

		ListEventsRequest tailReq = this.captureLastListEvents();
		assertThat(tailReq.actorId()).isEqualTo(ACTOR);
		assertThat(tailReq.sessionId()).isEqualTo(SESSION_SUFFIX);
		assertThat(tailReq.memoryId()).isEqualTo(MEMORY_ID);
		assertThat(tailReq.maxResults()).isEqualTo(1);
		assertThat(tailReq.includePayloads()).isFalse();
		// findById applies no branch filter: it is a plain tail read.
		assertThat(tailReq.filter()).isNull();
	}

	@Test
	void findByIdCreatedAtIsTailTimestampAndDoesNotCallListSessions() {
		// I4: findById derives createdAt from the tail event timestamp (a real,
		// non-sentinel value) and never calls ListSessions on the read path.
		Instant eventTs = Instant.parse("2026-06-15T00:00:00Z");
		Event tail = Event.builder().eventId("evt-2").eventTimestamp(eventTs).build();
		this.givenDataEvents(tail);

		Session synthesized = this.repository.findById(SESSION_ID).orElseThrow();
		assertThat(synthesized.createdAt()).isEqualTo(eventTs);
		assertThat(synthesized.createdAt()).isNotEqualTo(Instant.EPOCH);
		assertThat(synthesized.metadata().get(AgentCoreSessionRepository.LAST_EVENT_AT_METADATA_KEY))
			.isEqualTo(eventTs);
		then(this.client).should(never()).listSessions(any(ListSessionsRequest.class));
	}

	@Test
	void findByIdUnknownSessionIdReturnsEmpty() {
		this.givenDataEvents();
		assertThat(this.repository.findById(SESSION_ID)).isEmpty();
	}

	@Test
	void findByIdActorOnlyConversationIdUsesDefaultSession() {
		Event tail = Event.builder().eventId("evt-3").eventTimestamp(Instant.now()).build();
		this.givenDataEvents(tail);

		this.repository.findById("alice");

		ListEventsRequest tailReq = this.captureLastListEvents();
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

	// ==================== sessionId seam validation ====================

	@Test
	void sessionIdSeamRejectsBlankAndEmptySegmentIdsBeforeAnyClientCall() {
		// D7: blank ids and empty actor/session segments are rejected up front.
		assertThatThrownBy(() -> this.repository.findById("   ")).isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("sessionId must not be null or empty");
		assertThatThrownBy(() -> this.repository.findById(":")).isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("empty actor segment");
		assertThatThrownBy(() -> this.repository.findById(":conv")).isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("empty actor segment");
		assertThatThrownBy(() -> this.repository.findById("actor:")).isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("empty session segment");
		then(this.client).shouldHaveNoInteractions();
	}

	@Test
	void sessionIdWithControlCharactersIsRejected() {
		// Control characters in a sessionId are never legitimate and enable log
		// forging (CRLF injection); they are rejected before any client call.
		assertThatThrownBy(() -> this.repository.findById("alice\nB:conv")).isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("control characters");
		then(this.client).shouldHaveNoInteractions();
	}

	@Test
	void findEventsMalformedSeamThrowsIllegalArgumentNotRetrievalException() {
		// The (actor, session) seam is parsed OUTSIDE the try/catch in findEvents:
		// malformed ids surface as the documented IllegalArgumentException and are
		// never swallowed into a RetrievalException.
		assertThatThrownBy(() -> this.repository.findEvents(":conv", EventFilter.all()))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> this.repository.findEvents("actor:", EventFilter.all()))
			.isInstanceOf(IllegalArgumentException.class);
		then(this.client).shouldHaveNoInteractions();
	}

	// ==================== delete ====================

	@Test
	void deletePaginatesAndDeletesAllEvents() {
		List<Event> firstPage = IntStream.range(0, 10).mapToObj((i) -> eventWithId("evt-" + i)).toList();
		List<Event> secondPage = IntStream.range(10, 15).mapToObj((i) -> eventWithId("evt-" + i)).toList();
		given(this.client.listEvents(any(ListEventsRequest.class)))
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
		// No branch is set when the event carries none.
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
	void appendEventNeverPersistsSyntheticEvents() {
		// Synthetic events (framework generated, e.g. compaction summaries) are always
		// skipped; there is no opt-in flag to persist them.
		SessionEvent syntheticEvent = SessionEvent.builder()
			.sessionId(SESSION_ID)
			.message(AssistantMessage.builder().content("summary").build())
			.metadata(SessionEvent.METADATA_SYNTHETIC, true)
			.build();
		this.repository.appendEvent(syntheticEvent);
		then(this.client).should(never()).createEvent(any(CreateEventRequest.class));
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
	void appendEventSkipsBlankTextMessage() {
		SessionEvent event = SessionEvent.builder()
			.sessionId(SESSION_ID)
			.message(UserMessage.builder().text("   ").build())
			.build();
		this.repository.appendEvent(event);
		then(this.client).should(never()).createEvent(any(CreateEventRequest.class));
	}

	@Test
	void appendEventRejectsNullEvent() {
		assertThatThrownBy(() -> this.repository.appendEvent(null)).isInstanceOf(IllegalArgumentException.class);
		then(this.client).shouldHaveNoInteractions();
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

	@Test
	void appendEventPropagatesEventTimestamp() {
		given(this.client.createEvent(any(CreateEventRequest.class)))
			.willReturn(CreateEventResponse.builder().event(Event.builder().eventId("new-1").build()).build());

		Instant fixed = Instant.parse("2026-03-01T10:15:30Z");
		SessionEvent explicit = SessionEvent.builder()
			.sessionId(SESSION_ID)
			.timestamp(fixed)
			.message(UserMessage.builder().text("hi").build())
			.build();
		this.repository.appendEvent(explicit);

		// SessionEvent.Builder defaults the timestamp to now and build() rejects
		// null, so a builder-made event always carries one; either way the request
		// must never go out with a null eventTimestamp.
		SessionEvent defaulted = SessionEvent.builder()
			.sessionId(SESSION_ID)
			.message(UserMessage.builder().text("again").build())
			.build();
		this.repository.appendEvent(defaulted);

		ArgumentCaptor<CreateEventRequest> captor = ArgumentCaptor.forClass(CreateEventRequest.class);
		then(this.client).should(times(2)).createEvent(captor.capture());
		assertThat(captor.getAllValues().get(0).eventTimestamp()).isEqualTo(fixed);
		assertThat(captor.getAllValues().get(1).eventTimestamp()).isNotNull();
	}

	@Test
	void appendEventMapsSessionEventBranchToCreateEventBranch() {
		given(this.client.createEvent(any(CreateEventRequest.class)))
			.willReturn(CreateEventResponse.builder().event(Event.builder().eventId("new-1").build()).build());

		SessionEvent event = SessionEvent.builder()
			.sessionId(SESSION_ID)
			.branch("b7")
			.message(UserMessage.builder().text("hi").build())
			.build();
		this.repository.appendEvent(event);

		ArgumentCaptor<CreateEventRequest> captor = ArgumentCaptor.forClass(CreateEventRequest.class);
		then(this.client).should().createEvent(captor.capture());
		assertThat(captor.getValue().branch()).isNotNull();
		assertThat(captor.getValue().branch().name()).isEqualTo("b7");
	}

	@Test
	void appendEventWrapsSdkExceptionInStorageException() {
		given(this.client.createEvent(any(CreateEventRequest.class)))
			.willThrow(SdkException.builder().message("boom").build());

		SessionEvent event = SessionEvent.builder()
			.sessionId(SESSION_ID)
			.message(UserMessage.builder().text("hi").build())
			.build();
		assertThatThrownBy(() -> this.repository.appendEvent(event))
			.isInstanceOf(AgentCoreMemoryException.StorageException.class)
			.hasCauseInstanceOf(SdkException.class);
	}

	// ==================== replaceEvents (always unsupported) ====================

	@Test
	void replaceEventsThrowsUnsupportedOperationAndNeverTouchesClient() {
		SessionEvent newEvent = SessionEvent.builder()
			.sessionId(SESSION_ID)
			.message(UserMessage.builder().text("new").build())
			.build();
		assertThatThrownBy(() -> this.repository.replaceEvents(SESSION_ID, List.of(newEvent)))
			.isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("replaceEvents is unsupported");
		then(this.client).shouldHaveNoInteractions();
	}

	@Test
	void replaceEventsCasOverloadThrowsUnsupportedOperationAndNeverTouchesClient() {
		SessionEvent newEvent = SessionEvent.builder()
			.sessionId(SESSION_ID)
			.message(UserMessage.builder().text("replacement").build())
			.build();
		assertThatThrownBy(() -> this.repository.replaceEvents(SESSION_ID, List.of(newEvent), 1L))
			.isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("replaceEvents is unsupported");
		then(this.client).shouldHaveNoInteractions();
	}

	// ==================== getEventVersion ====================

	@Test
	void getEventVersionMatchesEventCountAcrossPages() {
		List<Event> firstPage = IntStream.range(0, 3).mapToObj((i) -> eventWithId("e" + i)).toList();
		List<Event> secondPage = IntStream.range(3, 5).mapToObj((i) -> eventWithId("e" + i)).toList();
		given(this.client.listEvents(any(ListEventsRequest.class)))
			.willReturn(ListEventsResponse.builder().events(firstPage).nextToken("next").build())
			.willReturn(ListEventsResponse.builder().events(secondPage).build());

		long version = this.repository.getEventVersion(SESSION_ID);
		assertThat(version).isEqualTo(5L);
	}

	@Test
	void getEventVersionEmptySessionReturnsZero() {
		this.givenDataEvents();
		assertThat(this.repository.getEventVersion(SESSION_ID)).isEqualTo(0L);
	}

	@Test
	void paginationEchoesNextTokenFromPreviousPage() {
		List<Event> firstPage = IntStream.range(0, 3).mapToObj((i) -> eventWithId("e" + i)).toList();
		List<Event> secondPage = IntStream.range(3, 5).mapToObj((i) -> eventWithId("e" + i)).toList();
		given(this.client.listEvents(any(ListEventsRequest.class)))
			.willReturn(ListEventsResponse.builder().events(firstPage).nextToken("page2").build())
			.willReturn(ListEventsResponse.builder().events(secondPage).build());

		this.repository.getEventVersion(SESSION_ID);

		ArgumentCaptor<ListEventsRequest> captor = ArgumentCaptor.forClass(ListEventsRequest.class);
		then(this.client).should(times(2)).listEvents(captor.capture());
		assertThat(captor.getAllValues().get(0).nextToken()).isNull();
		assertThat(captor.getAllValues().get(1).nextToken()).isEqualTo("page2");
	}

	// ==================== findEvents ====================

	@Test
	void findEventsReturnsChronologicalOrder() {
		Event newest = payloadEvent("e-3", "third", Role.USER, Instant.parse("2026-01-03T00:00:00Z"));
		Event middle = payloadEvent("e-2", "second", Role.ASSISTANT, Instant.parse("2026-01-02T00:00:00Z"));
		Event oldest = payloadEvent("e-1", "first", Role.USER, Instant.parse("2026-01-01T00:00:00Z"));
		this.givenDataEvents(newest, middle, oldest);

		List<SessionEvent> events = this.repository.findEvents(SESSION_ID, EventFilter.all());
		assertThat(events).extracting((e) -> e.getMessage().getText()).containsExactly("first", "second", "third");
		assertThat(events).extracting(SessionEvent::getId).containsExactly("e-1", "e-2", "e-3");
		// An unfiltered query carries no server-side filter.
		assertThat(this.captureLastListEvents().filter()).isNull();
	}

	@Test
	void findEventsRespectsLastN() {
		Event e1 = payloadEvent("e-1", "first", Role.USER, Instant.parse("2026-01-01T00:00:00Z"));
		Event e2 = payloadEvent("e-2", "second", Role.USER, Instant.parse("2026-01-02T00:00:00Z"));
		Event e3 = payloadEvent("e-3", "third", Role.USER, Instant.parse("2026-01-03T00:00:00Z"));
		this.givenDataEvents(e3, e2, e1);

		List<SessionEvent> events = this.repository.findEvents(SESSION_ID, EventFilter.lastN(2));
		assertThat(events).extracting((e) -> e.getMessage().getText()).containsExactly("second", "third");
	}

	@Test
	void findEventsLastNStopsPaginatingEarly() {
		// Events arrive newest-first; a plain lastN query (no page/pageSize) must stop
		// requesting pages once lastN matches are collected. The first page already
		// satisfies lastN=2, so the nextToken must never be followed.
		Event newest = payloadEvent("e-3", "third", Role.USER, Instant.parse("2026-01-03T00:00:00Z"));
		Event middle = payloadEvent("e-2", "second", Role.USER, Instant.parse("2026-01-02T00:00:00Z"));
		Event oldest = payloadEvent("e-1", "first", Role.USER, Instant.parse("2026-01-01T00:00:00Z"));
		given(this.client.listEvents(any(ListEventsRequest.class)))
			.willReturn(ListEventsResponse.builder().events(newest, middle).nextToken("page2").build())
			.willReturn(ListEventsResponse.builder().events(oldest).build());

		List<SessionEvent> events = this.repository.findEvents(SESSION_ID, EventFilter.lastN(2));

		assertThat(events).extracting((e) -> e.getMessage().getText()).containsExactly("second", "third");
		then(this.client).should(times(1)).listEvents(any(ListEventsRequest.class));
	}

	@Test
	void findEventsLastNWithMessageTypeFilterStopsPaginatingAtNthMatch() {
		// The early stop counts client-side MATCHES, not fetched events: page 1
		// yields one USER match (the ASSISTANT event does not match), page 2 yields
		// the second match, so page 3 must never be requested.
		Event userNewest = payloadEvent("u-2", "second-user", Role.USER, Instant.parse("2026-01-03T00:00:00Z"));
		Event assistantNoise = payloadEvent("a-1", "noise", Role.ASSISTANT, Instant.parse("2026-01-02T00:00:00Z"));
		Event userOldest = payloadEvent("u-1", "first-user", Role.USER, Instant.parse("2026-01-01T00:00:00Z"));
		given(this.client.listEvents(any(ListEventsRequest.class)))
			.willReturn(ListEventsResponse.builder().events(userNewest, assistantNoise).nextToken("page2").build())
			.willReturn(ListEventsResponse.builder().events(userOldest).nextToken("page3").build())
			.willReturn(emptyPage());

		EventFilter filter = EventFilter.builder().lastN(2).messageTypes(Set.of(MessageType.USER)).build();
		List<SessionEvent> events = this.repository.findEvents(SESSION_ID, filter);

		assertThat(events).extracting((e) -> e.getMessage().getText()).containsExactly("first-user", "second-user");
		then(this.client).should(times(2)).listEvents(any(ListEventsRequest.class));
	}

	@Test
	void findEventsMultiPayloadEventYieldsDistinctIdsInIntraEventOrder() {
		Conversational question = Conversational.builder()
			.role(Role.USER)
			.content(Content.builder().text("question").build())
			.build();
		Conversational answer = Conversational.builder()
			.role(Role.ASSISTANT)
			.content(Content.builder().text("answer").build())
			.build();
		Event multi = Event.builder()
			.memoryId(MEMORY_ID)
			.eventId("e-1")
			.eventTimestamp(Instant.parse("2026-01-01T00:00:00Z"))
			.payload(PayloadType.builder().conversational(question).build(),
					PayloadType.builder().conversational(answer).build())
			.build();
		this.givenDataEvents(multi);

		List<SessionEvent> events = this.repository.findEvents(SESSION_ID, EventFilter.all());

		// SessionEvent equality is (id, sessionId): the messages of one multi-payload
		// AgentCore event need DISTINCT ids ("eventId#i"); the raw eventId stays
		// available under EVENT_ID_METADATA_KEY.
		assertThat(events).extracting((e) -> e.getMessage().getText()).containsExactly("question", "answer");
		assertThat(events).extracting(SessionEvent::getId).containsExactly("e-1#0", "e-1#1");
		assertThat(events).allSatisfy((e) -> assertThat(e.getMetadata())
			.containsEntry(AgentCoreSessionRepository.EVENT_ID_METADATA_KEY, "e-1"));

		// lastN(1) over the single event keeps only its newest (last) message.
		List<SessionEvent> lastOne = this.repository.findEvents(SESSION_ID, EventFilter.lastN(1));
		assertThat(lastOne).extracting((e) -> e.getMessage().getText()).containsExactly("answer");
	}

	@Test
	void findEventsRespectsTotalEventsLimitAcrossPages() {
		AgentCoreSessionRepository limited = AgentCoreSessionRepository.builder()
			.memoryId(MEMORY_ID)
			.client(this.client)
			.defaultSession("default-session")
			.pageSize(2)
			.totalEventsLimit(3)
			.build();
		Event e4 = payloadEvent("e-4", "fourth", Role.USER, Instant.parse("2026-01-04T00:00:00Z"));
		Event e3 = payloadEvent("e-3", "third", Role.USER, Instant.parse("2026-01-03T00:00:00Z"));
		Event e2 = payloadEvent("e-2", "second", Role.USER, Instant.parse("2026-01-02T00:00:00Z"));
		Event e1 = payloadEvent("e-1", "first", Role.USER, Instant.parse("2026-01-01T00:00:00Z"));
		given(this.client.listEvents(any(ListEventsRequest.class)))
			.willReturn(ListEventsResponse.builder().events(e4, e3).nextToken("page2").build())
			.willReturn(ListEventsResponse.builder().events(e2, e1).nextToken("page3").build());

		List<SessionEvent> events = limited.findEvents(SESSION_ID, EventFilter.all());

		// Only the first event of page 2 is consumed (limit 3); the trailing e-1 and
		// page 3 are never read, and maxResults is min(pageSize, totalEventsLimit).
		assertThat(events).extracting((e) -> e.getMessage().getText()).containsExactly("second", "third", "fourth");
		ArgumentCaptor<ListEventsRequest> captor = ArgumentCaptor.forClass(ListEventsRequest.class);
		then(this.client).should(times(2)).listEvents(captor.capture());
		assertThat(captor.getAllValues()).allSatisfy((req) -> assertThat(req.maxResults()).isEqualTo(2));
	}

	@Test
	void findEventsPageAndPageSizeSliceChronologically() {
		Event e5 = payloadEvent("e-5", "fifth", Role.USER, Instant.parse("2026-01-05T00:00:00Z"));
		Event e4 = payloadEvent("e-4", "fourth", Role.USER, Instant.parse("2026-01-04T00:00:00Z"));
		Event e3 = payloadEvent("e-3", "third", Role.USER, Instant.parse("2026-01-03T00:00:00Z"));
		Event e2 = payloadEvent("e-2", "second", Role.USER, Instant.parse("2026-01-02T00:00:00Z"));
		Event e1 = payloadEvent("e-1", "first", Role.USER, Instant.parse("2026-01-01T00:00:00Z"));
		this.givenDataEvents(e5, e4, e3, e2, e1);

		List<SessionEvent> pageZero = this.repository.findEvents(SESSION_ID,
				EventFilter.builder().pageSize(2).page(0).build());
		assertThat(pageZero).extracting((e) -> e.getMessage().getText()).containsExactly("first", "second");

		List<SessionEvent> tailPage = this.repository.findEvents(SESSION_ID,
				EventFilter.builder().pageSize(2).page(2).build());
		assertThat(tailPage).extracting((e) -> e.getMessage().getText()).containsExactly("fifth");

		List<SessionEvent> beyondEnd = this.repository.findEvents(SESSION_ID,
				EventFilter.builder().pageSize(2).page(5).build());
		assertThat(beyondEnd).isEmpty();
	}

	@Test
	void findEventsPushesBranchFilterDownToListEvents() {
		this.givenDataEvents();

		this.repository.findEvents(SESSION_ID, EventFilter.forBranch("b1"));

		ListEventsRequest req = this.captureLastListEvents();
		assertThat(req.filter()).isNotNull();
		assertThat(req.filter().branch()).isNotNull();
		assertThat(req.filter().branch().name()).isEqualTo("b1");
		// true = SPI reference semantics: a branch read includes pre-fork history.
		assertThat(req.filter().branch().includeParentBranches()).isTrue();
	}

	@Test
	void findEventsAppliesInMemoryFilter() {
		Event userEvent = payloadEvent("e-1", "user-text", Role.USER, Instant.parse("2026-01-01T00:00:00Z"));
		Event assistantEvent = payloadEvent("e-2", "assistant-text", Role.ASSISTANT,
				Instant.parse("2026-01-02T00:00:00Z"));
		this.givenDataEvents(assistantEvent, userEvent);

		EventFilter filter = EventFilter.builder().messageTypes(Set.of(MessageType.USER)).build();
		List<SessionEvent> events = this.repository.findEvents(SESSION_ID, filter);
		assertThat(events).hasSize(1);
		assertThat(events.get(0).getMessage().getText()).isEqualTo("user-text");
	}

	@Test
	void findEventsNonExistentSessionReturnsEmptyList() {
		this.givenDataEvents();
		assertThat(this.repository.findEvents(SESSION_ID, EventFilter.all())).isEmpty();
	}

	@Test
	void findEventsWrapsSdkExceptionInRetrievalException() {
		given(this.client.listEvents(any(ListEventsRequest.class)))
			.willThrow(SdkException.builder().message("boom").build());

		assertThatThrownBy(() -> this.repository.findEvents(SESSION_ID, EventFilter.all()))
			.isInstanceOf(AgentCoreMemoryException.RetrievalException.class)
			.hasCauseInstanceOf(SdkException.class);
	}

	// ==================== C1 advisor-integration tests ====================

	@Test
	void findByIdMatchingUserIdContextAppendMessagePasses() {
		Event tail = payloadEvent("e-1", "hi", Role.USER, Instant.parse("2026-01-01T00:00:00Z"));
		given(this.client.listEvents(any(ListEventsRequest.class))).willReturn(emptyPage())
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
		given(this.client.listEvents(any(ListEventsRequest.class))).willReturn(emptyPage())
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

	private void givenDataEvents(Event... events) {
		given(this.client.listEvents(any(ListEventsRequest.class)))
			.willReturn(ListEventsResponse.builder().events(List.of(events)).build());
	}

	private ListEventsRequest captureLastListEvents() {
		ArgumentCaptor<ListEventsRequest> captor = ArgumentCaptor.forClass(ListEventsRequest.class);
		then(this.client).should(Mockito.atLeastOnce()).listEvents(captor.capture());
		return captor.getAllValues().get(captor.getAllValues().size() - 1);
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
