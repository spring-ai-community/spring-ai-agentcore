/*
 * Copyright 2025-2025 the original author or authors.
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * ChatMemoryRepository implementation for Amazon Bedrock AgentCore Memory.
 *
 * <p>
 * This implementation uses eventId-based delta detection to save only new messages,
 * avoiding duplication in AgentCore events. Each message's metadata contains the eventId
 * of the AgentCore event it was saved in, allowing efficient delta calculation.
 * </p>
 *
 * <p>
 * Key behaviors:
 * </p>
 * <ul>
 * <li>findByConversationId: Returns messages with eventId in metadata for tracking</li>
 * <li>saveAll: Only saves messages without eventId (new messages)</li>
 * <li>After save: Marks saved messages with the returned eventId</li>
 * </ul>
 */
public class AgentCoreShortTermMemoryRepository implements ChatMemoryRepository {

	private static final Logger logger = LoggerFactory.getLogger(AgentCoreShortTermMemoryRepository.class);

	/**
	 * Metadata key for storing the AgentCore eventId in message metadata. Used for delta
	 * detection - messages with this key have already been saved.
	 */
	public static final String EVENT_ID_METADATA_KEY = "agentcore.eventId";

	private final BedrockAgentCoreClient client;

	private final String memoryId;

	private final Integer totalEventsLimit;

	private final String defaultSession;

	private final int pageSize;

	private final boolean ignoreUnknownRoles;

	public AgentCoreShortTermMemoryRepository(String memoryId, BedrockAgentCoreClient client, Integer totalEventsLimit,
			String defaultSession, int pageSize, boolean ignoreUnknownRoles) {
		this.memoryId = validateMemoryId(memoryId);
		this.client = client;
		this.totalEventsLimit = totalEventsLimit;
		this.defaultSession = defaultSession;
		this.pageSize = pageSize;
		this.ignoreUnknownRoles = ignoreUnknownRoles;
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

			var messages = allEvents.stream().flatMap(event -> {
				String eventId = event.eventId();
				return event.payload().stream().map(payload -> {
					Message message = switch (payload.conversational().role()) {
						case ASSISTANT -> createAssistantMessage(payload, eventId);
						case USER -> createUserMessage(payload, eventId);
						default -> {
							if (ignoreUnknownRoles) {
								logger.warn("Ignoring unknown role: {}", payload.conversational().role());
								yield null;
							}
							else {
								throw new IllegalStateException("Unsupported role: " + payload.conversational().role());
							}
						}
					};
					return message;
				});
			}).filter(Objects::nonNull).collect(java.util.stream.Collectors.toList());

			logger.debug("Retrieved {} messages for conversation: {}", messages.size(), conversationId);
			return messages;
		}
		catch (SdkException e) {
			logger.error("Failed to retrieve messages for conversation: {}", conversationId, e);
			throw new AgentCoreMemoryException.RetrievalException(
					"Failed to retrieve messages for conversation: " + conversationId, e);
		}
	}

	/**
	 * Create AssistantMessage with eventId in metadata for delta tracking.
	 */
	private AssistantMessage createAssistantMessage(PayloadType payload, String eventId) {
		return AssistantMessage.builder()
			.content(payload.conversational().content().text())
			.properties((eventId != null) ? Map.of(EVENT_ID_METADATA_KEY, eventId) : Map.of())
			.build();
	}

	/**
	 * Create UserMessage with eventId in metadata for delta tracking.
	 */
	private UserMessage createUserMessage(PayloadType payload, String eventId) {
		return UserMessage.builder()
			.text(payload.conversational().content().text())
			.metadata((eventId != null) ? Map.of(EVENT_ID_METADATA_KEY, eventId) : Map.of())
			.build();
	}

	private List<Event> fetchAllEvents(AgentCoreMemoryConversationIdParser.ActorAndSession actorAndSession) {
		var allEvents = new ArrayList<Event>();
		var nextToken = (String) null;
		int requestPageSize = totalEventsLimit != null ? Math.min(pageSize, totalEventsLimit) : pageSize;

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

				if (totalEventsLimit != null && allEvents.size() >= totalEventsLimit) {
					allEvents = new ArrayList<>(allEvents.subList(0, totalEventsLimit));
					break;
				}
			}
			while (nextToken != null);

			// AgentCore returns events in descending order (newest first),
			// reverse to chronological order for LLM context
			Collections.reverse(allEvents);
			return allEvents;
		}
		catch (SdkException e) {
			logger.error("Failed to fetch events for actor: {}, session: {}", actorAndSession.actor(),
					actorAndSession.session(), e);
			throw new AgentCoreMemoryException.RetrievalException("Failed to fetch events", e);
		}
	}

	/**
	 * Save only new messages (delta) to AgentCore.
	 *
	 * <p>
	 * Messages that already have an eventId in their metadata have been saved before and
	 * are skipped. Only messages without eventId are saved as a new event.
	 * </p>
	 *
	 * <p>
	 * After successful save, the returned eventId is added to each saved message's
	 * metadata for future delta detection.
	 * </p>
	 */
	@Override
	public void saveAll(String conversationId, List<Message> messages) {
		validateConversationId(conversationId);
		if (messages == null || messages.isEmpty()) {
			logger.debug("No messages to save for conversation: {}", conversationId);
			return;
		}

		// Delta detection: filter to only new messages (no eventId in metadata)
		List<Message> delta = messages.stream()// .toList();
			.filter(m -> m.getMetadata().get(EVENT_ID_METADATA_KEY) == null)
			.toList();

		if (delta.isEmpty()) {
			logger.debug("No new messages to save for conversation: {} (all {} messages already saved)", conversationId,
					messages.size());
			return;
		}

		logger.debug("Saving {} new messages (delta) for conversation: {} (total: {})", delta.size(), conversationId,
				messages.size());

		try {
			var actorAndSession = actorAndSession(conversationId);

			var payloads = delta.stream().map(this::buildPayloadType).filter(Objects::nonNull).toList();

			if (payloads.isEmpty()) {
				logger.debug("No valid payloads to save after filtering");
				return;
			}

			var createEventRequest = CreateEventRequest.builder()
				.memoryId(memoryId)
				.actorId(actorAndSession.actor())
				.sessionId(actorAndSession.session())
				.payload(payloads)
				.eventTimestamp(Instant.now())
				.build();

			var response = client.createEvent(createEventRequest);
			String eventId = response.event().eventId();

			// Mark saved messages with eventId for future delta detection
			delta.forEach(m -> m.getMetadata().put(EVENT_ID_METADATA_KEY, eventId));

			logger.debug("Successfully saved {} messages as event {} for conversation: {}", delta.size(), eventId,
					conversationId);
		}
		catch (SdkException e) {
			logger.error("Failed to save messages for conversation: {}", conversationId, e);
			throw new AgentCoreMemoryException.StorageException(
					"Failed to save messages for conversation: " + conversationId, e);
		}
	}

	private PayloadType buildPayloadType(Message message) {
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
				throw new IllegalStateException("Unsupported message type: " + message.getClass().getSimpleName());
			}
		}

		var content = Content.builder().text(message.getText()).build();
		var conversational = Conversational.builder().content(content).role(role).build();
		return PayloadType.builder().conversational(conversational).build();
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
			throw new AgentCoreMemoryException.StorageException("Failed to delete conversation: " + conversationId, e);
		}
	}

	AgentCoreMemoryConversationIdParser.ActorAndSession actorAndSession(String conversationId) {
		return AgentCoreMemoryConversationIdParser.parse(conversationId, defaultSession);
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
