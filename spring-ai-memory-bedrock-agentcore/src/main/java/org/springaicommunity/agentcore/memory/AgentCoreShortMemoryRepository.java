package org.springaicommunity.agentcore.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.bedrockagentcore.BedrockAgentCoreClient;
import software.amazon.awssdk.services.bedrockagentcore.model.*;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

public class AgentCoreShortMemoryRepository implements ChatMemoryRepository {

	private static final Logger logger = LoggerFactory.getLogger(AgentCoreShortMemoryRepository.class);

	private final BedrockAgentCoreClient client;

	private final String memoryId;

	private final Integer totalEventsLimit;

	private final String defaultSession;

	private final int pageSize;

	private final boolean ignoreUnknownRoles;

	public AgentCoreShortMemoryRepository(String memoryId, BedrockAgentCoreClient client, Integer totalEventsLimit,
			String defaultSession, int pageSize, boolean ignoreUnknownRoles) {
		this.memoryId = validateMemoryId(memoryId);
		this.client = client;
		this.totalEventsLimit = totalEventsLimit;
		this.defaultSession = defaultSession;
		this.pageSize = pageSize;
		this.ignoreUnknownRoles = ignoreUnknownRoles;
	}

	record ActorAndSession(String actor, String session) {
	}

	@Override
	public List<String> findConversationIds() {
		throw new UnsupportedOperationException();
	}

	@Override
	public List<Message> findByConversationId(String conversationId) {
		validateConversationId(conversationId);
		logger.debug("Finding messages for conversation: {}", conversationId);

		try {
			var actorAndSession = actorAndSession(conversationId);
			var allEvents = fetchAllEvents(actorAndSession);

			var messages = allEvents.stream()
				.flatMap(event -> event.payload()
					.stream()
					.map(payload -> (Message) switch (payload.conversational().role()) {
						case ASSISTANT -> new AssistantMessage(payload.conversational().content().text());
						case USER -> new UserMessage(payload.conversational().content().text());
						default -> {
							if (ignoreUnknownRoles) {
								logger.warn("Ignoring unknown role: {}", payload.conversational().role());
								yield null;
							}
							else {
								throw new IllegalStateException("Unsupported role: " + payload.conversational().role());
							}
						}
					}))
				.filter(Objects::nonNull)
				.collect(java.util.stream.Collectors.toList());

			logger.debug("Retrieved {} messages for conversation: {}", messages.size(), conversationId);
			return messages;
		}
		catch (SdkException e) {
			logger.error("Failed to retrieve messages for conversation: {}", conversationId, e);
			throw new AgentCoreMemoryException("Failed to retrieve messages for conversation: " + conversationId, e);
		}
	}

	private List<Event> fetchAllEvents(ActorAndSession actorAndSession) {
		var allEvents = new java.util.ArrayList<Event>();
		var nextToken = (String) null;

		// Default to 1 when totalEventsLimit is null.
		// Each event contains the full conversation window (all messages from that turn),
		// so fetching multiple events causes message multiplication:
		// Event1=[msg1,msg2], Event2=[msg1,msg2,msg3,msg4] → loading both = 6 messages
		// instead of 4.
		// The most recent event always contains the complete windowed history.
		int effectiveLimit = totalEventsLimit != null ? totalEventsLimit : 1;
		int requestPageSize = Math.min(pageSize, effectiveLimit);

		try {
			do {
				var requestBuilder = ListEventsRequest.builder()
					.actorId(actorAndSession.actor())
					.sessionId(actorAndSession.session())
					.memoryId(memoryId)
					.includePayloads(true)
					.maxResults(requestPageSize);

				if (nextToken != null) {
					requestBuilder.nextToken(nextToken);
				}

				var listEventsResponse = client.listEvents(requestBuilder.build());
				allEvents.addAll(listEventsResponse.events());
				nextToken = listEventsResponse.nextToken();

				// Always apply limit check (effectiveLimit is never null)
				if (allEvents.size() >= effectiveLimit) {
					return allEvents.size() <= effectiveLimit ? allEvents : allEvents.subList(0, effectiveLimit);
				}
			}
			while (nextToken != null);

			return allEvents;
		}
		catch (SdkException e) {
			logger.error("Failed to fetch events for actor: {}, session: {}", actorAndSession.actor(),
					actorAndSession.session(), e);
			throw new AgentCoreMemoryException("Failed to fetch events", e);
		}
	}

	@Override
	public void saveAll(String conversationId, List<Message> messages) {
		validateConversationId(conversationId);
		if (messages == null || messages.isEmpty()) {
			logger.debug("No messages to save for conversation: {}", conversationId);
			return;
		}

		logger.debug("Saving {} messages for conversation: {}", messages.size(), conversationId);

		try {
			var actorAndSession = actorAndSession(conversationId);

			var payloads = messages.stream().map(message -> {
				Role role;

				if (message instanceof AssistantMessage) {
					role = Role.ASSISTANT;
				}
				else if (message instanceof UserMessage) {
					role = Role.USER;
				}
				else {
					if (ignoreUnknownRoles) {
						logger.warn("Ignoring unknown message type: {}", message.getClass().getSimpleName());
						return null;
					}
					else {
						throw new IllegalStateException(
								"Unsupported message type: " + message.getClass().getSimpleName());
					}
				}

				var content = Content.builder().text(message.getText()).build();
				var conversational = Conversational.builder().content(content).role(role).build();
				return PayloadType.builder().conversational(conversational).build();
			}).filter(Objects::nonNull).toList();

			var createEventRequest = CreateEventRequest.builder()
				.memoryId(memoryId)
				.actorId(actorAndSession.actor())
				.sessionId(actorAndSession.session())
				.payload(payloads)
				.eventTimestamp(Instant.now())
				.build();

			client.createEvent(createEventRequest);
			logger.debug("Successfully saved {} messages for conversation: {}", messages.size(), conversationId);
		}
		catch (SdkException e) {
			logger.error("Failed to save messages for conversation: {}", conversationId, e);
			throw new AgentCoreMemoryException("Failed to save messages for conversation: " + conversationId, e);
		}
	}

	@Override
	public void deleteByConversationId(String conversationId) {
		validateConversationId(conversationId);
		logger.debug("Deleting conversation: {}", conversationId);

		try {
			var actorAndSession = actorAndSession(conversationId);

			var listEventsRequest = ListEventsRequest.builder()
				.memoryId(memoryId)
				.actorId(actorAndSession.actor())
				.sessionId(actorAndSession.session())
				.includePayloads(false)
				.maxResults(pageSize)
				.build();

			var events = client.listEvents(listEventsRequest).events();

			events.forEach(event -> client.deleteEvent(DeleteEventRequest.builder()
				.memoryId(memoryId)
				.actorId(actorAndSession.actor())
				.sessionId(actorAndSession.session())
				.eventId(event.eventId())
				.build()));

			logger.debug("Successfully deleted {} events for conversation: {}", events.size(), conversationId);
		}
		catch (SdkException e) {
			logger.error("Failed to delete conversation: {}", conversationId, e);
			throw new AgentCoreMemoryException("Failed to delete conversation: " + conversationId, e);
		}
	}

	ActorAndSession actorAndSession(String conversationId) {
		if (conversationId.contains(":")) {
			var parts = conversationId.split(":");
			return new ActorAndSession(parts[0], parts[1]);
		}
		return new ActorAndSession(conversationId, defaultSession);
	}

	private String validateMemoryId(String memoryId) {
		if (memoryId == null || memoryId.trim().isEmpty()) {
			throw new IllegalArgumentException("MemoryId cannot be null or empty");
		}
		return memoryId;
	}

	private void validateConversationId(String conversationId) {
		if (conversationId == null || conversationId.trim().isEmpty()) {
			throw new IllegalArgumentException("ConversationId cannot be null or empty");
		}
	}

}
