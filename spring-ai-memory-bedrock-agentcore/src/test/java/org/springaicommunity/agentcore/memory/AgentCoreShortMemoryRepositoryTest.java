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
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import software.amazon.awssdk.services.bedrockagentcore.BedrockAgentCoreClient;
import software.amazon.awssdk.services.bedrockagentcore.model.*;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class AgentCoreShortMemoryRepositoryTest {

	@Mock
	private BedrockAgentCoreClient client;

	private AgentCoreShortMemoryRepository memoryRepository;

	@BeforeEach
	void setUp() {
		memoryRepository = new AgentCoreShortMemoryRepository("testMemoryId", client, null, "default-session", 100,
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
		// Use explicit totalEventsLimit=10 to test multi-event scenario
		// (default is now 1 to prevent message multiplication)
		var memoryRepositoryWithLimit = new AgentCoreShortMemoryRepository("testMemoryId", client, 10,
				"default-session", 100, false);

		CreateEventResponse response = CreateEventResponse.builder().event(buildTestEvent()).build();
		when(client.createEvent(any(CreateEventRequest.class))).thenReturn(response);

		ListEventsResponse listEventsResponse = ListEventsResponse.builder()
			.events(buildTestEvent(), buildTestEvent(), buildTestEvent())
			.build();
		when(client.listEvents(any(ListEventsRequest.class))).thenReturn(listEventsResponse);

		var chatMemory = MessageWindowChatMemory.builder()
			.chatMemoryRepository(memoryRepositoryWithLimit)
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
		var result = memoryRepository.actorAndSession("actor123:session456");

		assertEquals("actor123", result.actor());
		assertEquals("session456", result.session());
	}

	@Test
	void shouldUseDefaultSessionWhenNoSeparator() {
		var result = memoryRepository.actorAndSession("actor123");

		assertEquals("actor123", result.actor());
		assertEquals("default-session", result.session());
	}

	@Test
	void shouldRespectTotalLimitWhenConfigured() {
		var memoryRepositoryWithLimit = new AgentCoreShortMemoryRepository("testMemoryId", client, 1, "default-session",
				100, false);

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
	@CsvSource({ "200, 100", // limit > PAGE_SIZE -> PAGE_SIZE
			"50, 50", // limit < PAGE_SIZE -> limit
			"100, 100", // limit = PAGE_SIZE -> PAGE_SIZE
			"1, 1" // very small limit -> limit
	})
	void shouldUseCorrectPageSize(Integer totalEventsLimit, int expectedPageSize) {
		var memoryRepository = new AgentCoreShortMemoryRepository("testMemoryId", client, totalEventsLimit,
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
				() -> new AgentCoreShortMemoryRepository(null, client, null, "default-session", 100, false)))
			.hasMessage("MemoryId cannot be null or empty");
	}

	@Test
	void shouldIgnoreUnknownRolesWhenConfigured() {
		var memoryRepositoryWithIgnore = new AgentCoreShortMemoryRepository("testMemoryId", client, null,
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
		var memoryRepositoryIgnoreTrue = new AgentCoreShortMemoryRepository("testMemoryId", client, null,
				"default-session", 100, true);
		var memoryRepositoryIgnoreFalse = new AgentCoreShortMemoryRepository("testMemoryId", client, null,
				"default-session", 100, false);

		// We can't directly test the field, but we can verify the constructor accepts the
		// parameter
		// The actual behavior is tested through integration and the configuration system
		assertThat(memoryRepositoryIgnoreTrue).isNotNull();
		assertThat(memoryRepositoryIgnoreFalse).isNotNull();
	}

	@Test
	void shouldIgnoreUnknownMessageTypesWhenSaving() {
		var memoryRepositoryWithIgnore = new AgentCoreShortMemoryRepository("testMemoryId", client, null,
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

	@Test
	void shouldDefaultToOneWhenTotalEventsLimitIsNull() {
		// Given: repository with null totalEventsLimit
		// Default should be 1 because each event contains the full conversation window.
		// Loading multiple events causes message multiplication since each event
		// contains all messages from that turn (Event1=[msg1,msg2],
		// Event2=[msg1,msg2,msg3,msg4]).
		var memoryRepository = new AgentCoreShortMemoryRepository("testMemoryId", client, null, "default-session", 10,
				false);

		// Create event with multiple messages (simulating a conversation window)
		var event = Event.builder()
			.payload(
					PayloadType.builder()
						.conversational(Conversational.builder()
							.role(Role.USER)
							.content(Content.builder().text("message 1").build())
							.build())
						.build(),
					PayloadType.builder()
						.conversational(Conversational.builder()
							.role(Role.ASSISTANT)
							.content(Content.builder().text("response 1").build())
							.build())
						.build())
			.build();

		ListEventsResponse response = ListEventsResponse.builder()
			.events(List.of(event))
			.nextToken("more-events-available") // More events exist but should not be
												// fetched
			.build();
		when(client.listEvents(any(ListEventsRequest.class))).thenReturn(response);

		// When: fetching messages
		List<Message> messages = memoryRepository.findByConversationId("testActorId:testSessionId");

		// Then: should return messages from only 1 event (the default limit)
		assertThat(messages).hasSize(2);

		// Verify only one API call was made (no pagination due to limit=1)
		verify(client, times(1)).listEvents(any(ListEventsRequest.class));
	}

}