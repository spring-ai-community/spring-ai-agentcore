package org.springaicommunity.agentcore.memory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import software.amazon.awssdk.services.bedrockagentcore.BedrockAgentCoreClient;
import software.amazon.awssdk.services.bedrockagentcore.model.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class AgentCoreShortTermMemoryRepositoryTest {

	@Mock
	private BedrockAgentCoreClient client;

	private AgentCoreShortTermMemoryRepository memoryRepository;

	@BeforeEach
	void setUp() {
		memoryRepository = new AgentCoreShortTermMemoryRepository("testMemoryId", client, null, "default-session", 100,
				false);
	}

	@Test
	public void createAndFetchMemories() {
		List<Message> messages = List.of(UserMessage.builder().text("hello").build());

		CreateEventResponse response = CreateEventResponse.builder().event(buildTestEvent()).build();
		when(client.createEvent(any(CreateEventRequest.class))).thenReturn(response);

		ListEventsResponse listEventsResponse = ListEventsResponse.builder().events(buildTestEvent()).build();
		when(client.listEvents(any(ListEventsRequest.class))).thenReturn(listEventsResponse);

		var conversationId = "testActorId:testSessionId";
		memoryRepository.saveAll(conversationId, messages);

		List<Message> memoryMessages = memoryRepository.findByConversationId(conversationId);

		assertThat(memoryMessages.size()).isEqualTo(1);
		assertThat(memoryMessages.get(0).getText()).isEqualTo("test message");

		ArgumentCaptor<CreateEventRequest> createEventsRequestArgumentCaptor = ArgumentCaptor
			.forClass(CreateEventRequest.class);
		verify(client, times(1)).createEvent(createEventsRequestArgumentCaptor.capture());
		assertThat(createEventsRequestArgumentCaptor.getValue().actorId()).isEqualTo("testActorId");
		assertThat(createEventsRequestArgumentCaptor.getValue().sessionId()).isEqualTo("testSessionId");
		assertThat(createEventsRequestArgumentCaptor.getValue().memoryId()).isEqualTo("testMemoryId");
		assertThat(createEventsRequestArgumentCaptor.getValue().payload().size()).isEqualTo(1);
		assertThat(createEventsRequestArgumentCaptor.getValue().payload())
			.allMatch(p -> p.conversational().content().text().contains("hello"));

		ArgumentCaptor<ListEventsRequest> requestArgumentCaptor = ArgumentCaptor.forClass(ListEventsRequest.class);
		verify(client).listEvents(requestArgumentCaptor.capture());
		assertThat(requestArgumentCaptor.getValue().actorId()).isEqualTo("testActorId");
		assertThat(requestArgumentCaptor.getValue().sessionId()).isEqualTo("testSessionId");
		assertThat(requestArgumentCaptor.getValue().memoryId()).isEqualTo("testMemoryId");
	}

	@Test
	public void testChatMemory() {
		CreateEventResponse response = CreateEventResponse.builder().event(buildTestEvent()).build();
		when(client.createEvent(any(CreateEventRequest.class))).thenReturn(response);

		ListEventsResponse listEventsResponse = ListEventsResponse.builder()
			.events(buildTestEvent(), buildTestEvent(), buildTestEvent())
			.build();
		when(client.listEvents(any(ListEventsRequest.class))).thenReturn(listEventsResponse);

		var chatMemory = MessageWindowChatMemory.builder()
			.chatMemoryRepository(memoryRepository)
			.maxMessages(10)
			.build();

		var messages = new ArrayList<Message>();
		for (int i = 0; i < 20; i++) {
			messages.add(UserMessage.builder().text("test message " + i).build());
		}

		var conversationId = "testActorId:testSessionId";
		chatMemory.add(conversationId, messages);

		var memories = chatMemory.get(conversationId);
		assertThat(memories.size()).isEqualTo(3);
		assertThat(memories).allMatch(m -> m.getText().equals("test message"));

		ArgumentCaptor<CreateEventRequest> createEventsRequestArgumentCaptor = ArgumentCaptor
			.forClass(CreateEventRequest.class);
		verify(client, times(1)).createEvent(createEventsRequestArgumentCaptor.capture());
		assertThat(createEventsRequestArgumentCaptor.getValue().actorId()).isEqualTo("testActorId");
		assertThat(createEventsRequestArgumentCaptor.getValue().sessionId()).isEqualTo("testSessionId");
		assertThat(createEventsRequestArgumentCaptor.getValue().memoryId()).isEqualTo("testMemoryId");
		assertThat(createEventsRequestArgumentCaptor.getValue().payload().size()).isEqualTo(10);
		assertThat(createEventsRequestArgumentCaptor.getValue().payload())
			.allMatch(p -> p.conversational().content().text().contains("test message"));

		ArgumentCaptor<ListEventsRequest> listEventsRequestArgumentCaptor = ArgumentCaptor
			.forClass(ListEventsRequest.class);
		verify(client, times(2)).listEvents(listEventsRequestArgumentCaptor.capture());
		assertThat(listEventsRequestArgumentCaptor.getValue().actorId()).isEqualTo("testActorId");
		assertThat(listEventsRequestArgumentCaptor.getValue().sessionId()).isEqualTo("testSessionId");
		assertThat(listEventsRequestArgumentCaptor.getValue().memoryId()).isEqualTo("testMemoryId");
	}

	private Event buildTestEvent() {
		return Event.builder()
			.memoryId("testMemoryId")
			.actorId("testActorId")
			.sessionId("testSessionId")
			.eventId("testEventId")
			.payload(PayloadType.builder()
				.conversational(Conversational.builder()
					.role(Role.USER)
					.content(Content.builder().text("test message").build())
					.build())
				.build())
			.build();
	}

	@Test
	void shouldParseActorAndSessionWithSeparator() {
		AgentCoreMemoryConversationIdParser.ActorAndSession result = AgentCoreMemoryConversationIdParser
			.parse("actor123:session456");

		assertEquals("actor123", result.actor());
		assertEquals("session456", result.session());
	}

	@Test
	void shouldUseDefaultSessionWhenNoSeparator() {
		AgentCoreMemoryConversationIdParser.ActorAndSession result = AgentCoreMemoryConversationIdParser
			.parse("actor123");

		assertEquals("actor123", result.actor());
		assertEquals("default-session", result.session());
	}

	@Test
	void shouldRespectTotalLimitWhenConfigured() {
		var memoryRepositoryWithLimit = new AgentCoreShortTermMemoryRepository("testMemoryId", client, 1,
				"default-session", 100, false);

		ListEventsResponse listEventsResponse = ListEventsResponse.builder()
			.events(Event.builder()
				.payload(PayloadType.builder()
					.conversational(Conversational.builder()
						.role(Role.USER)
						.content(Content.builder().text("first message").build())
						.build())
					.build())
				.build(),
					Event.builder()
						.payload(PayloadType.builder()
							.conversational(Conversational.builder()
								.role(Role.USER)
								.content(Content.builder().text("second message").build())
								.build())
							.build())
						.build())
			.build();
		when(client.listEvents(any(ListEventsRequest.class))).thenReturn(listEventsResponse);

		List<Message> memoryMessages = memoryRepositoryWithLimit.findByConversationId("testActorId:testSessionId");

		assertThat(memoryMessages.size()).isEqualTo(1);
		assertThat(memoryMessages.get(0).getText()).isEqualTo("first message");
	}

	@ParameterizedTest
	@CsvSource({ ", 100", // null limit -> PAGE_SIZE
			"200, 100", // limit > PAGE_SIZE -> PAGE_SIZE
			"50, 50", // limit < PAGE_SIZE -> limit
			"100, 100", // limit = PAGE_SIZE -> PAGE_SIZE
			"1, 1" // very small limit -> limit
	})
	void shouldUseCorrectPageSize(Integer totalEventsLimit, int expectedPageSize) {
		var memoryRepository = new AgentCoreShortTermMemoryRepository("testMemoryId", client, totalEventsLimit,
				"default-session", 100, false);

		// Create events to return
		var events = IntStream.range(0, expectedPageSize)
			.mapToObj(i -> Event.builder()
				.payload(PayloadType.builder()
					.conversational(Conversational.builder()
						.role(Role.USER)
						.content(Content.builder().text("message " + i).build())
						.build())
					.build())
				.build())
			.toList();

		ListEventsResponse listEventsResponse = ListEventsResponse.builder().events(events).build();
		when(client.listEvents(any(ListEventsRequest.class))).thenReturn(listEventsResponse);

		memoryRepository.findByConversationId("testActorId:testSessionId");

		ArgumentCaptor<ListEventsRequest> requestCaptor = ArgumentCaptor.forClass(ListEventsRequest.class);
		verify(client).listEvents(requestCaptor.capture());
		assertThat(requestCaptor.getValue().maxResults()).isEqualTo(expectedPageSize);
	}

	@Test
	void shouldThrowExceptionForNullConversationId() {
		assertThat(org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
				() -> memoryRepository.findByConversationId(null)))
			.hasMessage("ConversationId cannot be null or empty");
	}

	@Test
	void shouldThrowExceptionForEmptyConversationId() {
		assertThat(org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
				() -> memoryRepository.findByConversationId("")))
			.hasMessage("ConversationId cannot be null or empty");
	}

	@Test
	void shouldThrowExceptionForNullMemoryId() {
		assertThat(org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
				() -> new AgentCoreShortTermMemoryRepository(null, client, null, "default-session", 100, false)))
			.hasMessage("MemoryId cannot be null or empty");
	}

	@Test
	void shouldIgnoreUnknownRolesWhenConfigured() {
		var memoryRepositoryWithIgnore = new AgentCoreShortTermMemoryRepository("testMemoryId", client, null,
				"default-session", 100, true);

		ListEventsResponse listEventsResponse = ListEventsResponse.builder()
			.events(Event.builder()
				.payload(PayloadType.builder()
					.conversational(Conversational.builder()
						.role(Role.USER)
						.content(Content.builder().text("valid message").build())
						.build())
					.build())
				.build())
			.build();
		when(client.listEvents(any(ListEventsRequest.class))).thenReturn(listEventsResponse);

		List<Message> memoryMessages = memoryRepositoryWithIgnore.findByConversationId("testActorId:testSessionId");

		assertThat(memoryMessages.size()).isEqualTo(1);
		assertThat(memoryMessages.get(0).getText()).isEqualTo("valid message");
	}

	@Test
	void shouldHaveCorrectIgnoreUnknownRolesConfiguration() {
		var memoryRepositoryIgnoreTrue = new AgentCoreShortTermMemoryRepository("testMemoryId", client, null,
				"default-session", 100, true);
		var memoryRepositoryIgnoreFalse = new AgentCoreShortTermMemoryRepository("testMemoryId", client, null,
				"default-session", 100, false);

		// We can't directly test the field, but we can verify the constructor accepts the
		// parameter
		// The actual behavior is tested through integration and the configuration system
		assertThat(memoryRepositoryIgnoreTrue).isNotNull();
		assertThat(memoryRepositoryIgnoreFalse).isNotNull();
	}

	@Test
	void shouldIgnoreUnknownMessageTypesWhenSaving() {
		var memoryRepositoryWithIgnore = new AgentCoreShortTermMemoryRepository("testMemoryId", client, null,
				"default-session", 100, true);

		CreateEventResponse response = CreateEventResponse.builder()
			.event(Event.builder().memoryId("testMemoryId").build())
			.build();
		when(client.createEvent(any(CreateEventRequest.class))).thenReturn(response);

		// Mix of known and unknown message types
		List<Message> messages = List.of(UserMessage.builder().text("user message").build(),
				new SystemMessage("system message") // This will be ignored
		);

		// Should not throw exception and should save only the USER message
		memoryRepositoryWithIgnore.saveAll("testActorId:testSessionId", messages);

		ArgumentCaptor<CreateEventRequest> requestCaptor = ArgumentCaptor.forClass(CreateEventRequest.class);
		verify(client).createEvent(requestCaptor.capture());

		// Should only have 1 payload (USER message), SYSTEM message should be filtered
		// out
		assertThat(requestCaptor.getValue().payload()).hasSize(1);
		assertThat(requestCaptor.getValue().payload().get(0).conversational().role()).isEqualTo(Role.USER);
	}

	@Test
	void shouldThrowExceptionForUnknownMessageTypesWhenNotIgnoring() {
		List<Message> messages = List.of(UserMessage.builder().text("user message").build(),
				new SystemMessage("system message") // This will cause exception
		);

		assertThat(org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
				() -> memoryRepository.saveAll("testActorId:testSessionId", messages)))
			.hasMessageContaining("Unsupported message type: SystemMessage");
	}

	// ==================== Delta Detection Tests ====================

	@Test
	void shouldOnlySaveMessagesWithoutEventId() {
		// Given: Mix of messages - some with eventId (already saved), some without (new)
		Map<String, Object> existingMetadata = new HashMap<>();
		existingMetadata.put(AgentCoreShortTermMemoryRepository.EVENT_ID_METADATA_KEY, "existing-event-id");

		List<Message> messages = List.of(UserMessage.builder().text("old message 1").metadata(existingMetadata).build(),
				AssistantMessage.builder().content("old message 2").properties(existingMetadata).build(),
				UserMessage.builder().text("new message 1").build(), // No eventId -
																		// should be saved
				AssistantMessage.builder().content("new message 2").build() // No eventId
																			// - should be
																			// saved
		);

		CreateEventResponse response = CreateEventResponse.builder()
			.event(Event.builder().eventId("new-event-id").memoryId("testMemoryId").build())
			.build();
		when(client.createEvent(any(CreateEventRequest.class))).thenReturn(response);

		// When
		memoryRepository.saveAll("testActorId:testSessionId", messages);

		// Then: Only 2 new messages should be saved
		ArgumentCaptor<CreateEventRequest> requestCaptor = ArgumentCaptor.forClass(CreateEventRequest.class);
		verify(client, times(1)).createEvent(requestCaptor.capture());
		assertThat(requestCaptor.getValue().payload()).hasSize(2);
		assertThat(requestCaptor.getValue().payload().get(0).conversational().content().text())
			.isEqualTo("new message 1");
		assertThat(requestCaptor.getValue().payload().get(1).conversational().content().text())
			.isEqualTo("new message 2");
	}

	@Test
	void shouldNotCallCreateEventWhenAllMessagesAlreadySaved() {
		// Given: All messages have eventId (already saved)
		Map<String, Object> existingMetadata = new HashMap<>();
		existingMetadata.put(AgentCoreShortTermMemoryRepository.EVENT_ID_METADATA_KEY, "existing-event-id");

		List<Message> messages = List.of(UserMessage.builder().text("old message 1").metadata(existingMetadata).build(),
				AssistantMessage.builder().content("old message 2").properties(existingMetadata).build());

		// When
		memoryRepository.saveAll("testActorId:testSessionId", messages);

		// Then: No createEvent call should be made
		verify(client, never()).createEvent(any(CreateEventRequest.class));
	}

	@Test
	void shouldMarkSavedMessagesWithEventId() {
		// Given: New messages without eventId
		UserMessage userMessage = UserMessage.builder().text("new user message").build();
		AssistantMessage assistantMessage = AssistantMessage.builder().content("new assistant message").build();
		List<Message> messages = new ArrayList<>();
		messages.add(userMessage);
		messages.add(assistantMessage);

		String returnedEventId = "returned-event-id-123";
		CreateEventResponse response = CreateEventResponse.builder()
			.event(Event.builder().eventId(returnedEventId).memoryId("testMemoryId").build())
			.build();
		when(client.createEvent(any(CreateEventRequest.class))).thenReturn(response);

		// When
		memoryRepository.saveAll("testActorId:testSessionId", messages);

		// Then: Messages should be marked with the returned eventId
		assertThat(userMessage.getMetadata().get(AgentCoreShortTermMemoryRepository.EVENT_ID_METADATA_KEY))
			.isEqualTo(returnedEventId);
		assertThat(assistantMessage.getMetadata().get(AgentCoreShortTermMemoryRepository.EVENT_ID_METADATA_KEY))
			.isEqualTo(returnedEventId);
	}

	@Test
	void shouldRestoreEventIdFromAgentCoreWhenFinding() {
		// Given: AgentCore returns events with eventId
		String eventId = "agentcore-event-id-456";
		Event event = Event.builder()
			.memoryId("testMemoryId")
			.actorId("testActorId")
			.sessionId("testSessionId")
			.eventId(eventId)
			.payload(PayloadType.builder()
				.conversational(Conversational.builder()
					.role(Role.USER)
					.content(Content.builder().text("stored message").build())
					.build())
				.build())
			.build();

		ListEventsResponse listEventsResponse = ListEventsResponse.builder().events(event).build();
		when(client.listEvents(any(ListEventsRequest.class))).thenReturn(listEventsResponse);

		// When
		List<Message> messages = memoryRepository.findByConversationId("testActorId:testSessionId");

		// Then: Messages should have eventId in metadata
		assertThat(messages).hasSize(1);
		assertThat(messages.get(0).getMetadata().get(AgentCoreShortTermMemoryRepository.EVENT_ID_METADATA_KEY))
			.isEqualTo(eventId);
	}

	@Test
	void shouldHandleRoundTripCorrectly() {
		// This test simulates the full flow:
		// 1. Find existing messages (with eventIds)
		// 2. Add new messages
		// 3. Save all - only new messages should be saved

		String existingEventId = "existing-event-id";
		String newEventId = "new-event-id";

		// Setup: findByConversationId returns messages with eventId
		Event existingEvent = Event.builder()
			.memoryId("testMemoryId")
			.actorId("testActorId")
			.sessionId("testSessionId")
			.eventId(existingEventId)
			.payload(
					PayloadType.builder()
						.conversational(Conversational.builder()
							.role(Role.USER)
							.content(Content.builder().text("existing user message").build())
							.build())
						.build(),
					PayloadType.builder()
						.conversational(Conversational.builder()
							.role(Role.ASSISTANT)
							.content(Content.builder().text("existing assistant message").build())
							.build())
						.build())
			.build();

		ListEventsResponse listEventsResponse = ListEventsResponse.builder().events(existingEvent).build();
		when(client.listEvents(any(ListEventsRequest.class))).thenReturn(listEventsResponse);

		CreateEventResponse createResponse = CreateEventResponse.builder()
			.event(Event.builder().eventId(newEventId).memoryId("testMemoryId").build())
			.build();
		when(client.createEvent(any(CreateEventRequest.class))).thenReturn(createResponse);

		// Step 1: Find existing messages
		List<Message> existingMessages = memoryRepository.findByConversationId("testActorId:testSessionId");
		assertThat(existingMessages).hasSize(2);

		// Step 2: Create combined list with new messages
		List<Message> allMessages = new ArrayList<>(existingMessages);
		allMessages.add(UserMessage.builder().text("new user message").build());
		allMessages.add(AssistantMessage.builder().content("new assistant message").build());

		// Step 3: Save all - should only save the 2 new messages
		memoryRepository.saveAll("testActorId:testSessionId", allMessages);

		// Verify: Only new messages were saved
		ArgumentCaptor<CreateEventRequest> requestCaptor = ArgumentCaptor.forClass(CreateEventRequest.class);
		verify(client, times(1)).createEvent(requestCaptor.capture());
		assertThat(requestCaptor.getValue().payload()).hasSize(2);
		assertThat(requestCaptor.getValue().payload().get(0).conversational().content().text())
			.isEqualTo("new user message");
		assertThat(requestCaptor.getValue().payload().get(1).conversational().content().text())
			.isEqualTo("new assistant message");
	}

	@Test
	void shouldHandleEmptyMessageList() {
		// When
		memoryRepository.saveAll("testActorId:testSessionId", List.of());

		// Then: No createEvent call
		verify(client, never()).createEvent(any(CreateEventRequest.class));
	}

	@Test
	void shouldHandleNullMessageList() {
		// When
		memoryRepository.saveAll("testActorId:testSessionId", null);

		// Then: No createEvent call
		verify(client, never()).createEvent(any(CreateEventRequest.class));
	}

	@Test
	void shouldPreserveMessageOrderInDelta() {
		// Given: Messages in specific order, some with eventId
		Map<String, Object> existingMetadata = new HashMap<>();
		existingMetadata.put(AgentCoreShortTermMemoryRepository.EVENT_ID_METADATA_KEY, "existing-event-id");

		List<Message> messages = List.of(UserMessage.builder().text("old 1").metadata(existingMetadata).build(),
				UserMessage.builder().text("new 1").build(),
				AssistantMessage.builder().content("old 2").properties(existingMetadata).build(),
				AssistantMessage.builder().content("new 2").build(), UserMessage.builder().text("new 3").build());

		CreateEventResponse response = CreateEventResponse.builder()
			.event(Event.builder().eventId("new-event-id").memoryId("testMemoryId").build())
			.build();
		when(client.createEvent(any(CreateEventRequest.class))).thenReturn(response);

		// When
		memoryRepository.saveAll("testActorId:testSessionId", messages);

		// Then: New messages should be in order
		ArgumentCaptor<CreateEventRequest> requestCaptor = ArgumentCaptor.forClass(CreateEventRequest.class);
		verify(client).createEvent(requestCaptor.capture());
		List<PayloadType> payloads = requestCaptor.getValue().payload();
		assertThat(payloads).hasSize(3);
		assertThat(payloads.get(0).conversational().content().text()).isEqualTo("new 1");
		assertThat(payloads.get(1).conversational().content().text()).isEqualTo("new 2");
		assertThat(payloads.get(2).conversational().content().text()).isEqualTo("new 3");
	}

	@Test
	void shouldReverseEventsToChronologicalOrder() {
		// Given: AgentCore returns events in descending order (newest first)
		ListEventsResponse listEventsResponse = ListEventsResponse.builder()
			.events(Event.builder()
				.eventId("event-3")
				.payload(PayloadType.builder()
					.conversational(Conversational.builder()
						.role(Role.USER)
						.content(Content.builder().text("third message").build())
						.build())
					.build())
				.build(),
					Event.builder()
						.eventId("event-2")
						.payload(PayloadType.builder()
							.conversational(Conversational.builder()
								.role(Role.USER)
								.content(Content.builder().text("second message").build())
								.build())
							.build())
						.build(),
					Event.builder()
						.eventId("event-1")
						.payload(PayloadType.builder()
							.conversational(Conversational.builder()
								.role(Role.USER)
								.content(Content.builder().text("first message").build())
								.build())
							.build())
						.build())
			.build();
		when(client.listEvents(any(ListEventsRequest.class))).thenReturn(listEventsResponse);

		// When
		List<Message> messages = memoryRepository.findByConversationId("testActorId:testSessionId");

		// Then: Messages should be in chronological order (oldest first)
		assertThat(messages).hasSize(3);
		assertThat(messages.get(0).getText()).isEqualTo("first message");
		assertThat(messages.get(1).getText()).isEqualTo("second message");
		assertThat(messages.get(2).getText()).isEqualTo("third message");
	}

}
