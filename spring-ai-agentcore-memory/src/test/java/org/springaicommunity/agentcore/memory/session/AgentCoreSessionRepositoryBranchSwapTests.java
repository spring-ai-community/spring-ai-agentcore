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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springaicommunity.agentcore.memory.AgentCoreMemoryException;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.bedrockagentcore.BedrockAgentCoreClient;
import software.amazon.awssdk.services.bedrockagentcore.model.Content;
import software.amazon.awssdk.services.bedrockagentcore.model.Conversational;
import software.amazon.awssdk.services.bedrockagentcore.model.CreateEventRequest;
import software.amazon.awssdk.services.bedrockagentcore.model.CreateEventResponse;
import software.amazon.awssdk.services.bedrockagentcore.model.DeleteEventRequest;
import software.amazon.awssdk.services.bedrockagentcore.model.Event;
import software.amazon.awssdk.services.bedrockagentcore.model.ListEventsRequest;
import software.amazon.awssdk.services.bedrockagentcore.model.ListEventsResponse;
import software.amazon.awssdk.services.bedrockagentcore.model.MetadataValue;
import software.amazon.awssdk.services.bedrockagentcore.model.PayloadType;
import software.amazon.awssdk.services.bedrockagentcore.model.Role;

import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.session.EventFilter;
import org.springframework.ai.session.SessionEvent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

/**
 * Tests for the v2 branch-swap {@code replaceEvents} path, ledger discovery / compaction,
 * the feature-flag off behavior, and the per-instance resolution cache. Complements the
 * base {@code AgentCoreSessionRepositoryTests}, which exercises the legacy (flag-off,
 * true v1) paths.
 *
 * <p>
 * Discovery scans (metadata EXISTS on {@code agentcore.pointer}) are distinguished from
 * branch-filtered data reads via {@link #isLedgerScan(ListEventsRequest)} and
 * {@link #isBranchScan(ListEventsRequest, String)}; pointer-marker writes are told apart
 * from data writes via {@link #isPointerCreate(CreateEventRequest)}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AgentCoreSessionRepositoryBranchSwapTests {

	private static final String MEMORY_ID = "testMemoryId";

	private static final String SESSION_ID = "alice:conv-1";

	@Mock
	private BedrockAgentCoreClient client;

	private AgentCoreSessionRepository branchSwapRepository() {
		return new AgentCoreSessionRepository(MEMORY_ID, this.client, null, "default-session", 100, true, false, true,
				false, false, null);
	}

	// ==================== T-D1 branch-swap replaceEvents ====================

	@Test
	void branchSwapOnMainLineWritesGenZeroBranchAndPointerWithoutDeletes() {
		givenNoLedgerMarkers();
		given(this.client.createEvent(any(CreateEventRequest.class)))
			.willReturn(CreateEventResponse.builder().event(Event.builder().eventId("new-1").build()).build());

		this.branchSwapRepository().replaceEvents(SESSION_ID, List.of(userEvent("hello")));

		ArgumentCaptor<CreateEventRequest> captor = ArgumentCaptor.forClass(CreateEventRequest.class);
		then(this.client).should(times(2)).createEvent(captor.capture());
		CreateEventRequest dataWrite = captor.getAllValues()
			.stream()
			.filter((r) -> r.branch() != null)
			.findFirst()
			.orElseThrow();
		assertThat(dataWrite.branch().name()).matches("gen-00000-[0-9a-f]{8}");
		CreateEventRequest pointer = captor.getAllValues()
			.stream()
			.filter(this::isPointerCreate)
			.findFirst()
			.orElseThrow();
		assertThat(pointer.metadata()).containsEntry(AgentCoreSessionRepository.POINTER_MARKER_METADATA_KEY,
				MetadataValue.fromStringValue("true"));
		assertThat(pointer.metadata()).containsEntry(AgentCoreSessionRepository.GENERATION_METADATA_KEY,
				MetadataValue.fromStringValue("00000"));
		// Non-destructive: old timeline is not deleted.
		then(this.client).should(never()).deleteEvent(any(DeleteEventRequest.class));
	}

	@Test
	void branchSwapOnAlreadyBranchedSessionIncrementsGeneration() {
		givenLedgerMarkers(markerEvent("m3", 3, "gen-00003-aaaaaaaa"));
		given(this.client.createEvent(any(CreateEventRequest.class)))
			.willReturn(CreateEventResponse.builder().event(Event.builder().eventId("new-1").build()).build());
		// Branch reads for the superseded gen-3 branch during compaction return empty.
		given(this.client.listEvents(argThat((ListEventsRequest r) -> isBranchScan(r, "gen-00003-aaaaaaaa"))))
			.willReturn(emptyPage());

		this.branchSwapRepository().replaceEvents(SESSION_ID, List.of(userEvent("v4")));

		ArgumentCaptor<CreateEventRequest> captor = ArgumentCaptor.forClass(CreateEventRequest.class);
		then(this.client).should(Mockito.atLeastOnce()).createEvent(captor.capture());
		CreateEventRequest dataWrite = captor.getAllValues()
			.stream()
			.filter((r) -> r.branch() != null)
			.findFirst()
			.orElseThrow();
		assertThat(dataWrite.branch().name()).matches("gen-00004-[0-9a-f]{8}");
		CreateEventRequest pointer = captor.getAllValues()
			.stream()
			.filter(this::isPointerCreate)
			.findFirst()
			.orElseThrow();
		assertThat(pointer.metadata()).containsEntry(AgentCoreSessionRepository.GENERATION_METADATA_KEY,
				MetadataValue.fromStringValue("00004"));
	}

	@Test
	void branchSwapMidWriteFailureThrowsAndWritesNoPointer() {
		givenNoLedgerMarkers();
		given(this.client.createEvent(any(CreateEventRequest.class)))
			.willReturn(CreateEventResponse.builder().event(Event.builder().eventId("new-1").build()).build())
			.willReturn(CreateEventResponse.builder().event(Event.builder().eventId("new-2").build()).build())
			.willThrow(SdkClientException.create("boom on third write"));

		List<SessionEvent> events = List.of(userEvent("a"), userEvent("b"), userEvent("c"));
		assertThatThrownBy(() -> this.branchSwapRepository().replaceEvents(SESSION_ID, events))
			.isInstanceOf(AgentCoreMemoryException.StorageException.class);

		// The pointer is never written, so the old timeline stays current; no deletes.
		then(this.client).should(never()).createEvent(argThat((CreateEventRequest r) -> isPointerCreate(r)));
		then(this.client).should(never()).deleteEvent(any(DeleteEventRequest.class));
	}

	@Test
	void deleteSupersededBranchDeletesPriorBranchAfterSwap() {
		AgentCoreSessionRepository repo = new AgentCoreSessionRepository(MEMORY_ID, this.client, null,
				"default-session", 100, true, false, true, true, false, null);
		givenLedgerMarkers(markerEvent("m3", 3, "gen-00003-aaaaaaaa"));
		given(this.client.createEvent(any(CreateEventRequest.class)))
			.willReturn(CreateEventResponse.builder().event(Event.builder().eventId("new-1").build()).build());
		given(this.client.listEvents(argThat((ListEventsRequest r) -> isBranchScan(r, "gen-00003-aaaaaaaa"))))
			.willReturn(ListEventsResponse.builder().events(eventWithId("old-branch-evt")).build());

		repo.replaceEvents(SESSION_ID, List.of(userEvent("v4")));

		ArgumentCaptor<DeleteEventRequest> deletes = ArgumentCaptor.forClass(DeleteEventRequest.class);
		then(this.client).should(Mockito.atLeastOnce()).deleteEvent(deletes.capture());
		assertThat(deletes.getAllValues()).extracting(DeleteEventRequest::eventId).contains("old-branch-evt");
	}

	// ==================== T-D1.3 appendEvent branch awareness ====================

	@Test
	void appendOnBranchedSessionCarriesCurrentBranch() {
		givenLedgerMarkers(markerEvent("m4", 4, "gen-00004-abababab"));
		given(this.client.createEvent(any(CreateEventRequest.class)))
			.willReturn(CreateEventResponse.builder().event(Event.builder().eventId("app-1").build()).build());

		this.branchSwapRepository().appendEvent(userSessionEvent("on-branch"));

		ArgumentCaptor<CreateEventRequest> captor = ArgumentCaptor.forClass(CreateEventRequest.class);
		then(this.client).should().createEvent(captor.capture());
		assertThat(captor.getValue().branch()).isNotNull();
		assertThat(captor.getValue().branch().name()).isEqualTo("gen-00004-abababab");
	}

	@Test
	void appendOnMainLineSessionCarriesNoBranch() {
		givenNoLedgerMarkers();
		given(this.client.createEvent(any(CreateEventRequest.class)))
			.willReturn(CreateEventResponse.builder().event(Event.builder().eventId("app-1").build()).build());

		this.branchSwapRepository().appendEvent(userSessionEvent("main-line"));

		ArgumentCaptor<CreateEventRequest> captor = ArgumentCaptor.forClass(CreateEventRequest.class);
		then(this.client).should().createEvent(captor.capture());
		assertThat(captor.getValue().branch()).isNull();
	}

	// ==================== T-BC v1 back-compat reads ====================

	@Test
	void findEventsOnNeverReplacedSessionUsesNoBranchFilter() {
		givenNoLedgerMarkers();
		given(this.client.listEvents(argThat((ListEventsRequest r) -> !isLedgerScan(r))))
			.willReturn(ListEventsResponse.builder().events(payloadEvent("e-1", "hi")).build());

		this.branchSwapRepository().findEvents(SESSION_ID, EventFilter.all());

		ArgumentCaptor<ListEventsRequest> captor = ArgumentCaptor.forClass(ListEventsRequest.class);
		then(this.client).should(Mockito.atLeastOnce()).listEvents(captor.capture());
		ListEventsRequest dataRead = captor.getAllValues()
			.stream()
			.filter((r) -> !isLedgerScan(r))
			.findFirst()
			.orElseThrow();
		assertThat(dataRead.filter()).isNull();
	}

	// ==================== T-delete branch-mode ====================

	@Test
	void deleteInBranchModeDeletesBranchEventsMarkersAndTail() {
		givenLedgerMarkers(markerEvent("m4", 4, "gen-00004-abababab"));
		given(this.client.listEvents(argThat((ListEventsRequest r) -> isBranchScan(r, "gen-00004-abababab"))))
			.willReturn(ListEventsResponse.builder().events(eventWithId("branch-evt")).build());
		// The residual main-line tail sweep (no filter) returns one legacy event.
		given(this.client.listEvents(argThat((ListEventsRequest r) -> isMainLineDataScan(r))))
			.willReturn(ListEventsResponse.builder().events(eventWithId("tail-evt")).build());

		this.branchSwapRepository().delete(SESSION_ID);

		ArgumentCaptor<DeleteEventRequest> deletes = ArgumentCaptor.forClass(DeleteEventRequest.class);
		then(this.client).should(Mockito.atLeastOnce()).deleteEvent(deletes.capture());
		assertThat(deletes.getAllValues()).extracting(DeleteEventRequest::eventId)
			.contains("branch-evt", "m4", "tail-evt");
	}

	// ==================== T-D1.9 feature-flag OFF ====================

	@Test
	void replaceEventsFlagOffOnMigratedSessionRefusesWithZeroWrites() {
		// Default constructor: branch-swap DISABLED. A migrated session (marker present)
		// must be refused, not run the destructive legacy delete against the main line.
		AgentCoreSessionRepository legacyRepo = new AgentCoreSessionRepository(MEMORY_ID, this.client, null,
				"default-session", 100, true, false);
		givenLedgerMarkers(markerEvent("m2", 2, "gen-00002-cccccccc"));

		assertThatThrownBy(() -> legacyRepo.replaceEvents(SESSION_ID, List.of(userEvent("x"))))
			.isInstanceOf(AgentCoreMemoryException.StorageException.class)
			.hasMessageContaining("branch-swap-enabled");

		then(this.client).should(never()).createEvent(any(CreateEventRequest.class));
		then(this.client).should(never()).deleteEvent(any(DeleteEventRequest.class));
	}

	@Test
	void readsResolveCurrentBranchEvenWhenFlagOff() {
		// The flag gates WRITES only; a migrated session's reads still follow the branch.
		AgentCoreSessionRepository legacyRepo = new AgentCoreSessionRepository(MEMORY_ID, this.client, null,
				"default-session", 100, true, false);
		givenLedgerMarkers(markerEvent("m2", 2, "gen-00002-cccccccc"));
		given(this.client.listEvents(argThat((ListEventsRequest r) -> isBranchScan(r, "gen-00002-cccccccc"))))
			.willReturn(ListEventsResponse.builder().events(payloadEvent("e-1", "migrated")).build());

		List<SessionEvent> events = legacyRepo.findEvents(SESSION_ID, EventFilter.all());
		assertThat(events).extracting((e) -> e.getMessage().getText()).containsExactly("migrated");
	}

	// ==================== T-N2 compaction + resolution cache ====================

	@Test
	void compactionDeletesSupersededBranchEventsAndMarkerKeepingMax() {
		givenLedgerMarkers(markerEvent("m3", 3, "gen-00003-aaaaaaaa"));
		given(this.client.createEvent(any(CreateEventRequest.class)))
			.willReturn(CreateEventResponse.builder().event(Event.builder().eventId("new-1").build()).build());
		given(this.client.listEvents(argThat((ListEventsRequest r) -> isBranchScan(r, "gen-00003-aaaaaaaa"))))
			.willReturn(ListEventsResponse.builder().events(eventWithId("g3-evt")).build());

		this.branchSwapRepository().replaceEvents(SESSION_ID, List.of(userEvent("v4")));

		ArgumentCaptor<DeleteEventRequest> deletes = ArgumentCaptor.forClass(DeleteEventRequest.class);
		then(this.client).should(Mockito.atLeastOnce()).deleteEvent(deletes.capture());
		// gen-3 branch event AND its marker are reaped; coupling holds.
		assertThat(deletes.getAllValues()).extracting(DeleteEventRequest::eventId).contains("g3-evt", "m3");
	}

	@Test
	void compactionKeepsMarkerWhenBranchEventDeleteFails() {
		givenLedgerMarkers(markerEvent("m3", 3, "gen-00003-aaaaaaaa"));
		given(this.client.createEvent(any(CreateEventRequest.class)))
			.willReturn(CreateEventResponse.builder().event(Event.builder().eventId("new-1").build()).build());
		// Branch-event listing fails -> deleteBranchEvents returns false -> marker kept.
		given(this.client.listEvents(argThat((ListEventsRequest r) -> isBranchScan(r, "gen-00003-aaaaaaaa"))))
			.willThrow(SdkClientException.create("branch scan failed"));

		this.branchSwapRepository().replaceEvents(SESSION_ID, List.of(userEvent("v4")));

		// The coupling: marker m3 is NOT deleted when its branch could not be purged.
		then(this.client).should(never()).deleteEvent(argThat((DeleteEventRequest r) -> "m3".equals(r.eventId())));
	}

	@Test
	void resolutionCacheServesSecondAppendWithoutRescan() {
		AgentCoreSessionRepository cachedRepo = new AgentCoreSessionRepository(MEMORY_ID, this.client, null,
				"default-session", 100, true, false, true, false, true, null);
		givenNoLedgerMarkers();
		given(this.client.createEvent(any(CreateEventRequest.class)))
			.willReturn(CreateEventResponse.builder().event(Event.builder().eventId("app").build()).build());

		cachedRepo.appendEvent(userSessionEvent("first"));
		cachedRepo.appendEvent(userSessionEvent("second"));

		// The second append hits the warm cache: only one ledger discovery scan happens.
		then(this.client).should(times(1)).listEvents(argThat((ListEventsRequest r) -> isLedgerScan(r)));
	}

	@Test
	void resolutionCacheIsPerInstance() {
		givenNoLedgerMarkers();
		given(this.client.createEvent(any(CreateEventRequest.class)))
			.willReturn(CreateEventResponse.builder().event(Event.builder().eventId("app").build()).build());
		AgentCoreSessionRepository repoA = new AgentCoreSessionRepository(MEMORY_ID, this.client, null,
				"default-session", 100, true, false, true, false, true, null);
		AgentCoreSessionRepository repoB = new AgentCoreSessionRepository(MEMORY_ID, this.client, null,
				"default-session", 100, true, false, true, false, true, null);

		repoA.appendEvent(userSessionEvent("a"));
		repoB.appendEvent(userSessionEvent("b"));

		// Each instance scans independently; the cache is not shared across instances.
		then(this.client).should(times(2)).listEvents(argThat((ListEventsRequest r) -> isLedgerScan(r)));
	}

	// ==================== T-same-generation tiebreak ====================

	@Test
	void resolveCurrentBranchIsHighestGenWithLexicographicTiebreakIndependentOfOrder() {
		AgentCoreSessionRepository repo = this.branchSwapRepository();
		var actorAndSession = repo.actorAndSession(SESSION_ID);
		Event low = markerEvent("m4a", 4, "gen-00004-aaaaaaaa");
		Event high = markerEvent("m4z", 4, "gen-00004-zzzzzzzz");

		given(this.client.listEvents(argThat((ListEventsRequest r) -> isLedgerScan(r))))
			.willReturn(ListEventsResponse.builder().events(low, high).build());
		assertThat(repo.resolveCurrentBranch(actorAndSession)).isEqualTo("gen-00004-zzzzzzzz");

		// Reverse the ListEvents order: the deterministic winner does not change.
		given(this.client.listEvents(argThat((ListEventsRequest r) -> isLedgerScan(r))))
			.willReturn(ListEventsResponse.builder().events(high, low).build());
		assertThat(repo.resolveCurrentBranch(actorAndSession)).isEqualTo("gen-00004-zzzzzzzz");
	}

	@Test
	void resolveCurrentBranchPicksHighestGeneration() {
		AgentCoreSessionRepository repo = this.branchSwapRepository();
		var actorAndSession = repo.actorAndSession(SESSION_ID);
		given(this.client.listEvents(argThat((ListEventsRequest r) -> isLedgerScan(r))))
			.willReturn(ListEventsResponse.builder()
				.events(markerEvent("m1", 1, "gen-00001-aaaaaaaa"), markerEvent("m7", 7, "gen-00007-bbbbbbbb"),
						markerEvent("m3", 3, "gen-00003-cccccccc"))
				.build());

		assertThat(repo.resolveCurrentBranch(actorAndSession)).isEqualTo("gen-00007-bbbbbbbb");
	}

	// ==================== matchers / fixtures ====================

	static boolean isLedgerScan(ListEventsRequest req) {
		return req != null && req.filter() != null && req.filter().eventMetadata() != null;
	}

	static boolean isBranchScan(ListEventsRequest req, String branchName) {
		return req != null && req.filter() != null && req.filter().branch() != null
				&& branchName.equals(req.filter().branch().name());
	}

	static boolean isMainLineDataScan(ListEventsRequest req) {
		return req != null && req.filter() == null;
	}

	private boolean isPointerCreate(CreateEventRequest req) {
		return req.metadata() != null && !req.metadata().isEmpty();
	}

	private void givenNoLedgerMarkers() {
		given(this.client.listEvents(argThat((ListEventsRequest r) -> isLedgerScan(r)))).willReturn(emptyPage());
	}

	private void givenLedgerMarkers(Event... markers) {
		given(this.client.listEvents(argThat((ListEventsRequest r) -> isLedgerScan(r))))
			.willReturn(ListEventsResponse.builder().events(List.of(markers)).build());
	}

	private static Event markerEvent(String eventId, long gen, String branchName) {
		Map<String, MetadataValue> metadata = new HashMap<>();
		metadata.put(AgentCoreSessionRepository.POINTER_MARKER_METADATA_KEY, MetadataValue.fromStringValue("true"));
		metadata.put(AgentCoreSessionRepository.CURRENT_BRANCH_METADATA_KEY, MetadataValue.fromStringValue(branchName));
		metadata.put(AgentCoreSessionRepository.GENERATION_METADATA_KEY,
				MetadataValue.fromStringValue(String.format("%05d", gen)));
		return Event.builder().memoryId(MEMORY_ID).eventId(eventId).metadata(metadata).build();
	}

	private static Event eventWithId(String id) {
		return Event.builder().memoryId(MEMORY_ID).eventId(id).build();
	}

	private static Event payloadEvent(String id, String text) {
		Conversational conv = Conversational.builder()
			.role(Role.USER)
			.content(Content.builder().text(text).build())
			.build();
		return Event.builder()
			.memoryId(MEMORY_ID)
			.eventId(id)
			.eventTimestamp(Instant.parse("2026-01-01T00:00:00Z"))
			.payload(PayloadType.builder().conversational(conv).build())
			.build();
	}

	private static SessionEvent userEvent(String text) {
		return SessionEvent.builder().sessionId(SESSION_ID).message(UserMessage.builder().text(text).build()).build();
	}

	private static SessionEvent userSessionEvent(String text) {
		return userEvent(text);
	}

	private static ListEventsResponse emptyPage() {
		return ListEventsResponse.builder().events(List.of()).build();
	}

}
