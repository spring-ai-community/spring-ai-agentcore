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

package org.springaicommunity.agentcore.memory.longterm;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springaicommunity.agentcore.memory.AgentCoreMemoryException;
import org.springaicommunity.agentcore.memory.longterm.AgentCoreLongTermMemoryRetriever.MemoryRecord;
import software.amazon.awssdk.services.bedrockagentcore.BedrockAgentCoreClient;
import software.amazon.awssdk.services.bedrockagentcore.model.ListMemoryRecordsRequest;
import software.amazon.awssdk.services.bedrockagentcore.model.ListMemoryRecordsResponse;
import software.amazon.awssdk.services.bedrockagentcore.model.MemoryContent;
import software.amazon.awssdk.services.bedrockagentcore.model.MemoryRecordSummary;
import software.amazon.awssdk.services.bedrockagentcore.model.RetrieveMemoryRecordsRequest;
import software.amazon.awssdk.services.bedrockagentcore.model.RetrieveMemoryRecordsResponse;
import software.amazon.awssdk.services.bedrockagentcore.model.ThrottledException;

import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.times;

/**
 * Unit tests for {@link AgentCoreLongTermMemoryRetriever}.
 *
 * @author Yuriy Bezsonov
 */
@ExtendWith({ MockitoExtension.class, OutputCaptureExtension.class })
class AgentCoreLongTermMemoryRetrieverTests {

	private static final String ACTOR_NAMESPACE = "/strategies/strategy-123/actors/user-456";

	private static final String SESSION_NAMESPACE = ACTOR_NAMESPACE + "/sessions/session-789";

	@Mock
	private BedrockAgentCoreClient client;

	private AgentCoreLongTermMemoryRetriever retriever;

	@BeforeEach
	void setUp() {
		this.retriever = new AgentCoreLongTermMemoryRetriever(this.client, "test-memory-id");
	}

	@Test
	void shouldSearchMemoriesWithCorrectNamespace() {
		// Given
		var summary = MemoryRecordSummary.builder()
			.memoryRecordId("record-1")
			.content(MemoryContent.builder().text("User likes coffee").build())
			.score(0.95)
			.namespaces(ACTOR_NAMESPACE)
			.build();

		given(this.client.retrieveMemoryRecords(any(RetrieveMemoryRecordsRequest.class)))
			.willReturn(RetrieveMemoryRecordsResponse.builder().memoryRecordSummaries(summary).build());

		// When
		List<MemoryRecord> records = this.retriever.searchMemories("strategy-123", "user-456", "coffee preferences", 5);

		// Then
		assertThat(records).hasSize(1);
		assertThat(records.get(0).id()).isEqualTo("record-1");
		assertThat(records.get(0).content()).isEqualTo("User likes coffee");
		assertThat(records.get(0).score()).isEqualTo(0.95);

		ArgumentCaptor<RetrieveMemoryRecordsRequest> captor = ArgumentCaptor
			.forClass(RetrieveMemoryRecordsRequest.class);
		then(this.client).should().retrieveMemoryRecords(captor.capture());

		assertThat(captor.getValue().memoryId()).isEqualTo("test-memory-id");
		assertThat(captor.getValue().namespace()).isEqualTo("/strategies/strategy-123/actors/user-456");
		assertThat(captor.getValue().searchCriteria().searchQuery()).isEqualTo("coffee preferences");
		assertThat(captor.getValue().searchCriteria().topK()).isEqualTo(5);
	}

	@Test
	void shouldSearchSummariesWithSessionNamespace() {
		// Given
		var summary = MemoryRecordSummary.builder()
			.memoryRecordId("summary-1")
			.content(MemoryContent.builder().text("Discussed travel plans").build())
			.score(0.88)
			.namespaces(SESSION_NAMESPACE)
			.build();

		given(this.client.retrieveMemoryRecords(any(RetrieveMemoryRecordsRequest.class)))
			.willReturn(RetrieveMemoryRecordsResponse.builder().memoryRecordSummaries(summary).build());

		// When
		List<MemoryRecord> records = this.retriever.searchMemories("strategy-123", "user-456", "session-789", "travel",
				3, AgentCoreLongTermMemoryNamespace.SESSION.getPattern());

		// Then
		assertThat(records).hasSize(1);
		assertThat(records.get(0).content()).isEqualTo("Discussed travel plans");

		ArgumentCaptor<RetrieveMemoryRecordsRequest> captor = ArgumentCaptor
			.forClass(RetrieveMemoryRecordsRequest.class);
		then(this.client).should().retrieveMemoryRecords(captor.capture());

		assertThat(captor.getValue().namespace())
			.isEqualTo("/strategies/strategy-123/actors/user-456/sessions/session-789");
	}

	@Test
	void shouldListMemoriesWithCorrectNamespace() {
		// Given
		var summary1 = MemoryRecordSummary.builder()
			.memoryRecordId("pref-1")
			.content(MemoryContent.builder().text("Prefers dark mode").build())
			.namespaces(ACTOR_NAMESPACE)
			.build();
		var summary2 = MemoryRecordSummary.builder()
			.memoryRecordId("pref-2")
			.content(MemoryContent.builder().text("Uses metric units").build())
			.namespaces(ACTOR_NAMESPACE)
			.build();

		given(this.client.listMemoryRecords(any(ListMemoryRecordsRequest.class)))
			.willReturn(ListMemoryRecordsResponse.builder().memoryRecordSummaries(summary1, summary2).build());

		// When
		List<MemoryRecord> records = this.retriever.listMemories("strategy-123", "user-456",
				AgentCoreLongTermMemoryNamespace.ACTOR.getPattern());

		// Then
		assertThat(records).hasSize(2);
		assertThat(records.get(0).content()).isEqualTo("Prefers dark mode");
		assertThat(records.get(1).content()).isEqualTo("Uses metric units");

		ArgumentCaptor<ListMemoryRecordsRequest> captor = ArgumentCaptor.forClass(ListMemoryRecordsRequest.class);
		then(this.client).should().listMemoryRecords(captor.capture());

		assertThat(captor.getValue().memoryId()).isEqualTo("test-memory-id");
		assertThat(captor.getValue().namespace()).isEqualTo("/strategies/strategy-123/actors/user-456");
	}

	@Test
	void shouldSendPatternEndingWithSlashAsWritten() {
		// Startup validation gives a pattern the trailing slash of the memory namespace
		given(this.client.retrieveMemoryRecords(any(RetrieveMemoryRecordsRequest.class)))
			.willReturn(RetrieveMemoryRecordsResponse.builder().build());

		// When
		this.retriever.searchMemories("strategy-123", "user-456", null, "query", 5, "/users/{actorId}/");

		// Then
		ArgumentCaptor<RetrieveMemoryRecordsRequest> captor = ArgumentCaptor
			.forClass(RetrieveMemoryRecordsRequest.class);
		then(this.client).should().retrieveMemoryRecords(captor.capture());
		assertThat(captor.getValue().namespace()).isEqualTo("/users/user-456/");
	}

	@Test
	void shouldListMemoriesInSessionScopedNamespace() {
		String sessionNamespace = "/strategies/strategy-123/actors/user-456/sessions/session-1";
		var summary = MemoryRecordSummary.builder()
			.memoryRecordId("pref-1")
			.content(MemoryContent.builder().text("Prefers dark mode").build())
			.namespaces(sessionNamespace)
			.build();
		given(this.client.listMemoryRecords(any(ListMemoryRecordsRequest.class)))
			.willReturn(ListMemoryRecordsResponse.builder().memoryRecordSummaries(summary).build());

		List<MemoryRecord> records = this.retriever.listMemories("strategy-123", "user-456", "session-1",
				AgentCoreLongTermMemoryNamespace.SESSION.getPattern());

		assertThat(records).extracting(MemoryRecord::content).containsExactly("Prefers dark mode");
		ArgumentCaptor<ListMemoryRecordsRequest> captor = ArgumentCaptor.forClass(ListMemoryRecordsRequest.class);
		then(this.client).should().listMemoryRecords(captor.capture());
		assertThat(captor.getValue().namespace()).isEqualTo(sessionNamespace);
	}

	@Test
	void shouldThrowExceptionOnApiError() {
		// Given
		given(this.client.retrieveMemoryRecords(any(RetrieveMemoryRecordsRequest.class)))
			.willThrow(new RuntimeException("API error"));

		// When/Then
		Assertions.assertThatThrownBy(() -> this.retriever.searchMemories("strategy-123", "user-456", "query", 5))
			.isInstanceOf(AgentCoreMemoryException.class)
			.hasMessageContaining("Failed to search memories")
			.hasCauseInstanceOf(RuntimeException.class);
	}

	@Test
	void shouldHandleNullContent() {
		// Given
		var summary = MemoryRecordSummary.builder()
			.memoryRecordId("record-1")
			.content((MemoryContent) null)
			.score(null)
			.namespaces(ACTOR_NAMESPACE)
			.build();

		given(this.client.retrieveMemoryRecords(any(RetrieveMemoryRecordsRequest.class)))
			.willReturn(RetrieveMemoryRecordsResponse.builder().memoryRecordSummaries(summary).build());

		// When
		List<MemoryRecord> records = this.retriever.searchMemories("strategy-123", "user-456", "query", 5);

		// Then
		assertThat(records).hasSize(1);
		assertThat(records.get(0).content()).isEmpty();
		assertThat(records.get(0).score()).isEqualTo(0.0);
	}

	@Test
	void shouldDropSearchResultsFromActorWhoseIdSharesThePrefix() {
		// Given: the service matches namespaces by prefix, so user-4567 records come back
		var own = record("own", ACTOR_NAMESPACE);
		var otherActor = record("other", "/strategies/strategy-123/actors/user-4567");

		given(this.client.retrieveMemoryRecords(any(RetrieveMemoryRecordsRequest.class)))
			.willReturn(RetrieveMemoryRecordsResponse.builder().memoryRecordSummaries(own, otherActor).build());

		// When
		List<MemoryRecord> records = this.retriever.searchMemories("strategy-123", "user-456", "query", 5);

		// Then
		assertThat(records).extracting(MemoryRecord::id).containsExactly("own");
	}

	@Test
	void shouldKeepActorSearchResultsFromOwnSessionNamespaces() {
		// Given
		var sessionRecord = record("session-record", SESSION_NAMESPACE);

		given(this.client.retrieveMemoryRecords(any(RetrieveMemoryRecordsRequest.class)))
			.willReturn(RetrieveMemoryRecordsResponse.builder().memoryRecordSummaries(sessionRecord).build());

		// When
		List<MemoryRecord> records = this.retriever.searchMemories("strategy-123", "user-456", "query", 5);

		// Then
		assertThat(records).extracting(MemoryRecord::id).containsExactly("session-record");
	}

	@Test
	void shouldDropSearchResultsFromSessionWhoseIdSharesThePrefix() {
		// Given
		var own = record("own", SESSION_NAMESPACE);
		var otherSession = record("other", SESSION_NAMESPACE + "0");

		given(this.client.retrieveMemoryRecords(any(RetrieveMemoryRecordsRequest.class)))
			.willReturn(RetrieveMemoryRecordsResponse.builder().memoryRecordSummaries(own, otherSession).build());

		// When
		List<MemoryRecord> records = this.retriever.searchMemories("strategy-123", "user-456", "session-789", "query",
				5, AgentCoreLongTermMemoryNamespace.SESSION.getPattern());

		// Then
		assertThat(records).extracting(MemoryRecord::id).containsExactly("own");
	}

	@Test
	void shouldDropListedRecordsFromActorWhoseIdSharesThePrefix() {
		// Given
		var own = record("own", ACTOR_NAMESPACE);
		var otherActor = record("other", "/strategies/strategy-123/actors/user-456-admin");

		given(this.client.listMemoryRecords(any(ListMemoryRecordsRequest.class)))
			.willReturn(ListMemoryRecordsResponse.builder().memoryRecordSummaries(own, otherActor).build());

		// When
		List<MemoryRecord> records = this.retriever.listMemories("strategy-123", "user-456",
				AgentCoreLongTermMemoryNamespace.ACTOR.getPattern());

		// Then
		assertThat(records).extracting(MemoryRecord::id).containsExactly("own");
	}

	@Test
	void shouldListMemoriesAcrossAllPages() {
		// Given
		given(this.client.listMemoryRecords(any(ListMemoryRecordsRequest.class))).willReturn(
				page("token-2", record("pref-1", ACTOR_NAMESPACE), record("pref-2", ACTOR_NAMESPACE)),
				page(null, record("pref-3", ACTOR_NAMESPACE)));

		// When
		List<MemoryRecord> records = this.retriever.listMemories("strategy-123", "user-456",
				AgentCoreLongTermMemoryNamespace.ACTOR.getPattern());

		// Then
		assertThat(records).extracting(MemoryRecord::id).containsExactly("pref-1", "pref-2", "pref-3");

		ArgumentCaptor<ListMemoryRecordsRequest> captor = ArgumentCaptor.forClass(ListMemoryRecordsRequest.class);
		then(this.client).should(times(2)).listMemoryRecords(captor.capture());

		assertThat(captor.getAllValues()).extracting(ListMemoryRecordsRequest::nextToken)
			.containsExactly(null, "token-2");
		assertThat(captor.getAllValues()).allSatisfy((request) -> {
			assertThat(request.memoryId()).isEqualTo("test-memory-id");
			assertThat(request.namespace()).isEqualTo(ACTOR_NAMESPACE);
			assertThat(request.memoryStrategyId()).isEqualTo("strategy-123");
			assertThat(request.maxResults()).isEqualTo(100);
		});
	}

	@Test
	void shouldFilterListedRecordsOnEveryPage() {
		// Given: the first page holds only records of a prefix-sharing actor
		var otherActor = "/strategies/strategy-123/actors/user-4567";
		given(this.client.listMemoryRecords(any(ListMemoryRecordsRequest.class))).willReturn(
				page("token-2", record("other-1", otherActor), record("other-2", otherActor)),
				page(null, record("other-3", otherActor), record("own-1", ACTOR_NAMESPACE),
						record("own-2", SESSION_NAMESPACE)));

		// When
		List<MemoryRecord> records = this.retriever.listMemories("strategy-123", "user-456",
				AgentCoreLongTermMemoryNamespace.ACTOR.getPattern());

		// Then: a page that the filter empties does not end the listing
		assertThat(records).extracting(MemoryRecord::id).containsExactly("own-1", "own-2");
		then(this.client).should(times(2)).listMemoryRecords(any(ListMemoryRecordsRequest.class));
	}

	@Test
	void shouldStopListingAfterMaxPages(CapturedOutput output) {
		// Given: the service holds more pages than the retriever reads
		this.stubListPages(AgentCoreLongTermMemoryRetriever.MAX_LIST_PAGES + 2);

		// When
		List<MemoryRecord> records = this.retriever.listMemories("strategy-123", "user-456",
				AgentCoreLongTermMemoryNamespace.ACTOR.getPattern());

		// Then
		assertThat(records).hasSize(AgentCoreLongTermMemoryRetriever.MAX_LIST_PAGES);
		then(this.client).should(times(AgentCoreLongTermMemoryRetriever.MAX_LIST_PAGES))
			.listMemoryRecords(any(ListMemoryRecordsRequest.class));
		// One record per stubbed page
		assertThat(output).contains("Stopped listing memories after " + AgentCoreLongTermMemoryRetriever.MAX_LIST_PAGES
				+ " pages (" + AgentCoreLongTermMemoryRetriever.MAX_LIST_PAGES + " records); more remain");
	}

	@Test
	void shouldNotWarnWhenLastPageIsTheMaxPage(CapturedOutput output) {
		// Given
		this.stubListPages(AgentCoreLongTermMemoryRetriever.MAX_LIST_PAGES);

		// When
		List<MemoryRecord> records = this.retriever.listMemories("strategy-123", "user-456",
				AgentCoreLongTermMemoryNamespace.ACTOR.getPattern());

		// Then
		assertThat(records).hasSize(AgentCoreLongTermMemoryRetriever.MAX_LIST_PAGES);
		assertThat(output).doesNotContain("Stopped listing memories");
	}

	@Test
	void shouldThrowExceptionWhenLaterListPageFails() {
		// Given
		given(this.client.listMemoryRecords(any(ListMemoryRecordsRequest.class)))
			.willReturn(page("token-2", record("pref-1", ACTOR_NAMESPACE)))
			.willThrow(ThrottledException.builder().message("Rate exceeded").build());

		// When/Then
		Assertions
			.assertThatThrownBy(() -> this.retriever.listMemories("strategy-123", "user-456",
					AgentCoreLongTermMemoryNamespace.ACTOR.getPattern()))
			.isInstanceOf(AgentCoreMemoryException.RetrievalException.class)
			.hasMessageContaining("Failed to list memories")
			.hasCauseInstanceOf(ThrottledException.class);
	}

	@Test
	void shouldKeepOwnRecordsForRequestEndingWithSlash() {
		// Given: the memory's template ends with '/', and so does the validated pattern
		var own = record("own", ACTOR_NAMESPACE + "/");
		var ownSession = record("own-session", SESSION_NAMESPACE + "/");
		var otherActor = record("other", "/strategies/strategy-123/actors/user-4567/");

		given(this.client.retrieveMemoryRecords(any(RetrieveMemoryRecordsRequest.class))).willReturn(
				RetrieveMemoryRecordsResponse.builder().memoryRecordSummaries(own, ownSession, otherActor).build());

		// When
		List<MemoryRecord> records = this.retriever.searchMemories("strategy-123", "user-456", null, "query", 5,
				AgentCoreLongTermMemoryNamespace.ACTOR.getPattern() + "/");

		// Then
		assertThat(records).extracting(MemoryRecord::id).containsExactly("own", "own-session");
	}

	@Test
	void shouldKeepRecordWhenAnyOfItsNamespacesIsInScope() {
		// Given
		var shared = record("shared", "/strategies/strategy-123/actors/user-4567", ACTOR_NAMESPACE);

		given(this.client.retrieveMemoryRecords(any(RetrieveMemoryRecordsRequest.class)))
			.willReturn(RetrieveMemoryRecordsResponse.builder().memoryRecordSummaries(shared).build());

		// When
		List<MemoryRecord> records = this.retriever.searchMemories("strategy-123", "user-456", "query", 5);

		// Then
		assertThat(records).extracting(MemoryRecord::id).containsExactly("shared");
	}

	@Test
	void shouldDropRecordsWithoutNamespaces() {
		// Given
		var unscoped = MemoryRecordSummary.builder()
			.memoryRecordId("unscoped")
			.content(MemoryContent.builder().text("no namespace").build())
			.build();

		given(this.client.retrieveMemoryRecords(any(RetrieveMemoryRecordsRequest.class)))
			.willReturn(RetrieveMemoryRecordsResponse.builder().memoryRecordSummaries(unscoped).build());

		// When
		List<MemoryRecord> records = this.retriever.searchMemories("strategy-123", "user-456", "query", 5);

		// Then
		assertThat(records).isEmpty();
	}

	@Test
	void shouldRejectActorIdWithSlashBeforeCallingService() {
		// "user-456/x" would resolve beneath actor "user-456" and pass the namespace
		// filter
		Assertions.assertThatThrownBy(() -> this.retriever.searchMemories("strategy-123", "user-456/x", "query", 5))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("actorId");
		Assertions
			.assertThatThrownBy(() -> this.retriever.listMemories("strategy-123", "user-456/x",
					AgentCoreLongTermMemoryNamespace.ACTOR.getPattern()))
			.isInstanceOf(IllegalArgumentException.class);

		then(this.client).shouldHaveNoInteractions();
	}

	@Test
	void shouldRejectActorIdWithWildcardBeforeCallingService() {
		Assertions.assertThatThrownBy(() -> this.retriever.searchMemories("strategy-123", "*", "query", 5))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("actorId");

		then(this.client).shouldHaveNoInteractions();
	}

	private static MemoryRecordSummary record(String id, String... namespaces) {
		return MemoryRecordSummary.builder()
			.memoryRecordId(id)
			.content(MemoryContent.builder().text(id).build())
			.score(0.5)
			.namespaces(namespaces)
			.build();
	}

	private static ListMemoryRecordsResponse page(String nextToken, MemoryRecordSummary... summaries) {
		return ListMemoryRecordsResponse.builder().memoryRecordSummaries(summaries).nextToken(nextToken).build();
	}

	// Stubs a listing of pageCount pages holding one record each
	private void stubListPages(int pageCount) {
		var calls = new AtomicInteger();
		given(this.client.listMemoryRecords(any(ListMemoryRecordsRequest.class))).willAnswer((invocation) -> {
			int call = calls.incrementAndGet();
			String nextToken = (call < pageCount) ? "token-" + (call + 1) : null;
			return page(nextToken, record("pref-" + call, ACTOR_NAMESPACE));
		});
	}

}
