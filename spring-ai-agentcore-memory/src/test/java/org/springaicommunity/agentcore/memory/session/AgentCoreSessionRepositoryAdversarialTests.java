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

/**
 * Adversarial / characterization tests for {@link AgentCoreSessionRepository} pure-logic
 * paths (no real AWS; the SDK client is mocked). Focuses on the untrusted-string surface:
 * sessionId validation, the sessionId -> (actor, session) derivation that feeds AgentCore
 * request builders, and the server-event -> {@link SessionEvent} mapping round-trip.
 *
 * <p>
 * These lock in CURRENT behavior. Assertions tagged FINDING document rough edges to be
 * reviewed by kl-architect (see kl-fuzzer-152.md). The mapping tests (F5) guard against a
 * malformed AgentCore event producing a raw {@link NullPointerException} instead of a
 * domain exception.
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

	// ==================== FINDING F1: ":" passes validation, sends blank actorId =====

	@Test
	void roughEdgeColonOnlySessionIdPassesValidationAndSendsBlankActorToAws() {
		// FINDING F1 (wrong-behavior): validateSessionId uses trim().isEmpty(), so ":"
		// (length 2, non-blank) is accepted, but the parser then splits it into an EMPTY
		// actorId and EMPTY sessionId. The repository forwards those blanks straight to
		// AgentCore. Garbage is accepted locally and only fails later at the AWS boundary
		// (or worse, silently queries the wrong/blank actor). It is NOT caught by the
		// stricter validateSessionId guard.
		given(this.client.listEvents(any(ListEventsRequest.class)))
			.willReturn(ListEventsResponse.builder().events(List.of()).build());

		this.repository.findById(":");

		ArgumentCaptor<ListEventsRequest> captor = ArgumentCaptor.forClass(ListEventsRequest.class);
		org.mockito.BDDMockito.then(this.client).should().listEvents(captor.capture());
		assertThat(captor.getValue().actorId()).isEmpty();
		assertThat(captor.getValue().sessionId()).isEmpty();
	}

	@Test
	void roughEdgeLeadingColonSessionIdWithExistingEventsThrowsConfusingLowLevelError() {
		// FINDING F6 (wrong-behavior): ":realSession" derives an EMPTY actor. When the
		// session has events, findById tries to synthesize Session.builder().userId(""),
		// and Spring's Session.builder().build() rejects it with a bare
		// IllegalArgumentException("userId must not be null or empty") thrown from deep
		// in
		// spring-ai (Session.java:129), NOT wrapped in an AgentCoreMemoryException and
		// with
		// no mention of the offending sessionId. The user sees an opaque low-level error
		// for what is really a malformed-sessionId input. The empty-actor case is never
		// rejected up front by the parser or validateSessionId.
		Event tail = Event.builder().eventId("e1").eventTimestamp(Instant.now()).build();
		given(this.client.listEvents(any(ListEventsRequest.class)))
			.willReturn(ListEventsResponse.builder().events(tail).build());

		assertThatThrownBy(() -> this.repository.findById(":realSession")).isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("userId must not be null or empty");
	}

	// ============ FINDING F5: malformed server event -> unwrapped exception ===========

	@Test
	void findEventsNullContentOnConversationalPayloadThrowsRawNpe() {
		// FINDING F5 (rough-edge / robustness): mapPayloadsToMessages calls
		// payload.conversational().content().text() with no null guard on content().
		// A conversational payload whose content is null makes findEvents throw a bare
		// NullPointerException, NOT the AgentCoreMemoryException.RetrievalException the
		// class contract implies for read failures (only SdkException is caught and
		// wrapped). A malformed server event thus surfaces as an opaque NPE.
		Conversational convNoContent = Conversational.builder().role(Role.USER).build();
		Event bad = Event.builder()
			.memoryId(MEMORY_ID)
			.eventId("e-bad")
			.eventTimestamp(Instant.parse("2026-01-01T00:00:00Z"))
			.payload(PayloadType.builder().conversational(convNoContent).build())
			.build();
		given(this.client.listEvents(any(ListEventsRequest.class)))
			.willReturn(ListEventsResponse.builder().events(bad).build());

		// Characterizes the CURRENT behavior: a raw NPE escapes unwrapped.
		assertThatThrownBy(() -> this.repository.findEvents("alice:conv", EventFilter.all()))
			.isInstanceOf(NullPointerException.class);
	}

	@Test
	void findEventsNullTextInContentThrowsUnwrappedIllegalArgument() {
		// FINDING F5 continued: content present but text() null. Spring's UserMessage
		// builder rejects null text with IllegalArgumentException ("Content must not be
		// null for SYSTEM or USER messages"), which again escapes findEvents unwrapped
		// rather than as an AgentCoreMemoryException.RetrievalException.
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

		assertThatThrownBy(() -> this.repository.findEvents("alice:conv", EventFilter.all()))
			.isInstanceOf(IllegalArgumentException.class);
	}

	// ==================== round-trip: append text survives mapping ==================

	@ParameterizedTest
	@ValueSource(strings = { "hi", "  padded  ", "line1\nline2", "会话", "😀emoji", "tab\tseparated", "colon:in:text" })
	void appendThenMapRoundTripsMessageText(String text) {
		// The message text is untrusted; appending then mapping back must preserve it and
		// never crash the mapper. (Two independent single-hop checks with a shared mock.)
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
