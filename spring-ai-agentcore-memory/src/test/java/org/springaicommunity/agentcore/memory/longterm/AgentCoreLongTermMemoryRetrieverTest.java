package org.springaicommunity.agentcore.memory.longterm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

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

/**
 * Unit tests for {@link AgentCoreLongTermMemoryRetriever}.
 *
 * @author Yuriy Bezsonov
 */
@ExtendWith(MockitoExtension.class)
class AgentCoreLongTermMemoryRetrieverTest {

	@Mock
	private BedrockAgentCoreClient client;

	private AgentCoreLongTermMemoryRetriever retriever;

	@BeforeEach
	void setUp() {
		retriever = new AgentCoreLongTermMemoryRetriever(client, "test-memory-id");
	}

	@Test
	void shouldSearchMemoriesWithCorrectNamespace() {
		// Given
		var summary = MemoryRecordSummary.builder()
			.memoryRecordId("record-1")
			.content(MemoryContent.builder().text("User likes coffee").build())
			.score(0.95)
			.build();

		when(client.retrieveMemoryRecords(any(RetrieveMemoryRecordsRequest.class)))
			.thenReturn(RetrieveMemoryRecordsResponse.builder().memoryRecordSummaries(summary).build());

		// When
		List<MemoryRecord> records = retriever.searchMemories("strategy-123", "user-456", "coffee preferences", 5);

		// Then
		assertThat(records).hasSize(1);
		assertThat(records.get(0).id()).isEqualTo("record-1");
		assertThat(records.get(0).content()).isEqualTo("User likes coffee");
		assertThat(records.get(0).score()).isEqualTo(0.95);

		ArgumentCaptor<RetrieveMemoryRecordsRequest> captor = ArgumentCaptor
			.forClass(RetrieveMemoryRecordsRequest.class);
		verify(client).retrieveMemoryRecords(captor.capture());

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
			.build();

		when(client.retrieveMemoryRecords(any(RetrieveMemoryRecordsRequest.class)))
			.thenReturn(RetrieveMemoryRecordsResponse.builder().memoryRecordSummaries(summary).build());

		// When
		List<MemoryRecord> records = retriever.searchMemories("strategy-123", "user-456", "session-789", "travel", 3,
				AgentCoreLongTermMemoryNamespace.SESSION.getPattern());

		// Then
		assertThat(records).hasSize(1);
		assertThat(records.get(0).content()).isEqualTo("Discussed travel plans");

		ArgumentCaptor<RetrieveMemoryRecordsRequest> captor = ArgumentCaptor
			.forClass(RetrieveMemoryRecordsRequest.class);
		verify(client).retrieveMemoryRecords(captor.capture());

		assertThat(captor.getValue().namespace())
			.isEqualTo("/strategies/strategy-123/actors/user-456/sessions/session-789");
	}

	@Test
	void shouldListMemoriesWithCorrectNamespace() {
		// Given
		var summary1 = MemoryRecordSummary.builder()
			.memoryRecordId("pref-1")
			.content(MemoryContent.builder().text("Prefers dark mode").build())
			.build();
		var summary2 = MemoryRecordSummary.builder()
			.memoryRecordId("pref-2")
			.content(MemoryContent.builder().text("Uses metric units").build())
			.build();

		when(client.listMemoryRecords(any(ListMemoryRecordsRequest.class)))
			.thenReturn(ListMemoryRecordsResponse.builder().memoryRecordSummaries(summary1, summary2).build());

		// When
		List<MemoryRecord> records = retriever.listMemories("strategy-123", "user-456",
				AgentCoreLongTermMemoryNamespace.ACTOR.getPattern());

		// Then
		assertThat(records).hasSize(2);
		assertThat(records.get(0).content()).isEqualTo("Prefers dark mode");
		assertThat(records.get(1).content()).isEqualTo("Uses metric units");

		ArgumentCaptor<ListMemoryRecordsRequest> captor = ArgumentCaptor.forClass(ListMemoryRecordsRequest.class);
		verify(client).listMemoryRecords(captor.capture());

		assertThat(captor.getValue().memoryId()).isEqualTo("test-memory-id");
		assertThat(captor.getValue().namespace()).isEqualTo("/strategies/strategy-123/actors/user-456");
	}

	@Test
	void shouldThrowExceptionOnApiError() {
		// Given
		when(client.retrieveMemoryRecords(any(RetrieveMemoryRecordsRequest.class)))
			.thenThrow(new RuntimeException("API error"));

		// When/Then
		org.assertj.core.api.Assertions
			.assertThatThrownBy(() -> retriever.searchMemories("strategy-123", "user-456", "query", 5))
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
			.build();

		when(client.retrieveMemoryRecords(any(RetrieveMemoryRecordsRequest.class)))
			.thenReturn(RetrieveMemoryRecordsResponse.builder().memoryRecordSummaries(summary).build());

		// When
		List<MemoryRecord> records = retriever.searchMemories("strategy-123", "user-456", "query", 5);

		// Then
		assertThat(records).hasSize(1);
		assertThat(records.get(0).content()).isEmpty();
		assertThat(records.get(0).score()).isEqualTo(0.0);
	}

}
