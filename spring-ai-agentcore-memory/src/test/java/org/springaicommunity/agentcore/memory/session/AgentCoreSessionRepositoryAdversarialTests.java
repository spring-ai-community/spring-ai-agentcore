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
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
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
import software.amazon.awssdk.services.bedrockagentcore.model.Event;
import software.amazon.awssdk.services.bedrockagentcore.model.ListEventsRequest;
import software.amazon.awssdk.services.bedrockagentcore.model.ListEventsResponse;
import software.amazon.awssdk.services.bedrockagentcore.model.PayloadType;
import software.amazon.awssdk.services.bedrockagentcore.model.Role;

import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.session.EventFilter;
import org.springframework.ai.session.SessionEvent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

/**
 * Adversarial / characterization tests for {@link AgentCoreSessionRepository} pure-logic
 * paths (no real AWS; the SDK client is mocked). Focuses on the untrusted-string surface:
 * sessionId validation, the sessionId -> (actor, session) derivation that feeds AgentCore
 * request builders, and the server-event -> {@link SessionEvent} mapping round-trip.
 *
 * <p>
 * Post-v2 (D7/D8): the session repository now HARDENS its own sessionId seam (trim +
 * reject empty segments) and wraps malformed server events. Several tests here that
 * previously characterized the rough edges are flipped to assert the fixed behavior.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AgentCoreSessionRepositoryAdversarialTests {

	private static final String MEMORY_ID = "testMemoryId";

	@Mock
	private BedrockAgentCoreClient client;

	private AgentCoreSessionRepository repository;

	@BeforeEach
	void setUp() {
		this.repository = new AgentCoreSessionRepository(MEMORY_ID, this.client, null, "default-session", 100, true,
				false);
	}

	// ==================== sessionId validation ====================

	@ParameterizedTest
	@ValueSource(strings = { " ", "  ", "\t", "\n", "   \t  " })
	void findByIdRejectsWhitespaceOnlySessionId(String blank) {
		assertThatThrownBy(() -> this.repository.findById(blank)).isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("sessionId must not be null or empty");
	}

	@Test
	void findByIdRejectsNullSessionId() {
		assertThatThrownBy(() -> this.repository.findById(null)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void findByIdRejectsEmptySessionId() {
		assertThatThrownBy(() -> this.repository.findById("")).isInstanceOf(IllegalArgumentException.class);
	}

	// ==================== D7: session-seam empty-segment rejection (FLIPPED) =========

	@Test
	void colonOnlySessionIdIsRejectedAtSeamAndSendsNothingToAws() {
		// FLIP of roughEdgeColonOnlySessionIdPassesValidationAndSendsBlankActorToAws.
		// D7: ":" splits into an EMPTY actor and EMPTY session; the session seam now
		// rejects it up front, before any AWS call, with a clear message naming the id.
		assertThatThrownBy(() -> this.repository.findById(":")).isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("empty actor segment");
		then(this.client).should(never()).listEvents(any(ListEventsRequest.class));
	}

	@Test
	void leadingColonSessionIdIsRejectedAtSeamWithClearMessage() {
		// FLIP of
		// roughEdgeLeadingColonSessionIdWithExistingEventsThrowsConfusingLowLevelError.
		// D7/F6: ":realSession" derives an EMPTY actor. The seam now rejects it with a
		// clear IllegalArgumentException naming the offending sessionId, BEFORE any
		// Session.builder() call, so the opaque deep spring-ai error never surfaces.
		assertThatThrownBy(() -> this.repository.findById(":realSession")).isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining(":realSession")
			.hasMessageContaining("empty actor segment");
		then(this.client).should(never()).listEvents(any(ListEventsRequest.class));
	}

	@Test
	void trailingColonSessionIdWithEmptySessionSegmentIsRejectedAtSeam() {
		// D7: "actor:" splits into a non-empty actor and EMPTY session -> rejected.
		assertThatThrownBy(() -> this.repository.findById("actor:")).isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("empty session segment");
		then(this.client).should(never()).listEvents(any(ListEventsRequest.class));
	}

	@Test
	void sessionSeamTrimsActorAndSessionSegments() {
		// D7: " alice : conv " trims to actor "alice", session "conv" in the AWS request.
		given(this.client.listEvents(any(ListEventsRequest.class)))
			.willReturn(ListEventsResponse.builder().events(List.of()).build());

		this.repository.findById("  alice : conv  ");

		ArgumentCaptor<ListEventsRequest> captor = ArgumentCaptor.forClass(ListEventsRequest.class);
		then(this.client).should(Mockito.atLeastOnce()).listEvents(captor.capture());
		ListEventsRequest dataReq = captor.getAllValues()
			.stream()
			.filter((r) -> r.filter() == null || r.filter().eventMetadata() == null)
			.reduce((a, b) -> b)
			.orElseThrow();
		assertThat(dataReq.actorId()).isEqualTo("alice");
		assertThat(dataReq.sessionId()).isEqualTo("conv");
	}

	// ============ D8: malformed server event -> skip-with-WARN, no raw throwable ======

	@Test
	void findEventsNullContentOnConversationalPayloadIsSkippedNotThrown() {
		// FLIP of findEventsNullContentOnConversationalPayloadThrowsRawNpe.
		// D8: a conversational payload with null content is a malformed server event;
		// mapPayloadsToMessages now null-guards content/text and SKIPS it with a WARN
		// rather than throwing a raw NPE. The whole findEvents does not fail.
		Conversational convNoContent = Conversational.builder().role(Role.USER).build();
		Event bad = Event.builder()
			.memoryId(MEMORY_ID)
			.eventId("e-bad")
			.eventTimestamp(Instant.parse("2026-01-01T00:00:00Z"))
			.payload(PayloadType.builder().conversational(convNoContent).build())
			.build();
		given(this.client.listEvents(any(ListEventsRequest.class)))
			.willReturn(ListEventsResponse.builder().events(bad).build());

		List<SessionEvent> events = this.repository.findEvents("alice:conv", EventFilter.all());
		assertThat(events).isEmpty();
	}

	@Test
	void findEventsNullTextInContentIsSkippedNotThrown() {
		// FLIP of findEventsNullTextInContentThrowsUnwrappedIllegalArgument.
		// D8: content present but text() null -> skipped with WARN, not an unwrapped IAE.
		Content emptyContent = Content.builder().build();
		Conversational conv = Conversational.builder().role(Role.USER).content(emptyContent).build();
		Event bad = Event.builder()
			.memoryId(MEMORY_ID)
			.eventId("e-bad2")
			.eventTimestamp(Instant.parse("2026-01-01T00:00:00Z"))
			.payload(PayloadType.builder().conversational(conv).build())
			.build();
		given(this.client.listEvents(any(ListEventsRequest.class)))
			.willReturn(ListEventsResponse.builder().events(bad).build());

		List<SessionEvent> events = this.repository.findEvents("alice:conv", EventFilter.all());
		assertThat(events).isEmpty();
	}

	// ==================== round-trip: append text survives mapping ==================

	@ParameterizedTest
	@ValueSource(strings = { "hi", "  padded  ", "line1\nline2", "会话", "😀emoji", "tab\tseparated", "colon:in:text" })
	void appendThenMapRoundTripsMessageText(String text) {
		given(this.client.createEvent(any(CreateEventRequest.class)))
			.willReturn(CreateEventResponse.builder().event(Event.builder().eventId("new-1").build()).build());
		SessionEvent event = SessionEvent.builder()
			.sessionId("alice:conv")
			.message(UserMessage.builder().text(text).build())
			.build();
		assertThatCode(() -> this.repository.appendEvent(event)).doesNotThrowAnyException();

		Conversational conv = Conversational.builder()
			.role(Role.USER)
			.content(Content.builder().text(text).build())
			.build();
		Event stored = Event.builder()
			.memoryId(MEMORY_ID)
			.eventId("e-1")
			.eventTimestamp(Instant.parse("2026-01-01T00:00:00Z"))
			.payload(PayloadType.builder().conversational(conv).build())
			.build();
		given(this.client.listEvents(any(ListEventsRequest.class)))
			.willReturn(ListEventsResponse.builder().events(stored).build());

		List<SessionEvent> mapped = this.repository.findEvents("alice:conv", EventFilter.all());
		assertThat(mapped).hasSize(1);
		assertThat(mapped.get(0).getMessage().getText()).isEqualTo(text);
	}

	// ==================== null-arg guards ====================

	@Test
	void appendEventRejectsNull() {
		assertThatThrownBy(() -> this.repository.appendEvent(null)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void findEventsRejectsNullFilter() {
		assertThatThrownBy(() -> this.repository.findEvents("alice:conv", null))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void replaceEventsRejectsNullEvents() {
		assertThatThrownBy(() -> this.repository.replaceEvents("alice:conv", null))
			.isInstanceOf(IllegalArgumentException.class);
	}

}
