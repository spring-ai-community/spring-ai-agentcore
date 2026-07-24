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
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springaicommunity.agentcore.memory.AgentCoreMemoryConversationIdParser;
import org.springaicommunity.agentcore.memory.AgentCoreMemoryException;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.bedrockagentcore.BedrockAgentCoreClient;
import software.amazon.awssdk.services.bedrockagentcore.model.Content;
import software.amazon.awssdk.services.bedrockagentcore.model.Conversational;
import software.amazon.awssdk.services.bedrockagentcore.model.CreateEventRequest;
import software.amazon.awssdk.services.bedrockagentcore.model.DeleteEventRequest;
import software.amazon.awssdk.services.bedrockagentcore.model.Event;
import software.amazon.awssdk.services.bedrockagentcore.model.ListEventsRequest;
import software.amazon.awssdk.services.bedrockagentcore.model.ListSessionsRequest;
import software.amazon.awssdk.services.bedrockagentcore.model.ListSessionsResponse;
import software.amazon.awssdk.services.bedrockagentcore.model.PayloadType;
import software.amazon.awssdk.services.bedrockagentcore.model.Role;
import software.amazon.awssdk.services.bedrockagentcore.model.SessionSummary;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.session.EventFilter;
import org.springframework.ai.session.Session;
import org.springframework.ai.session.SessionEvent;
import org.springframework.ai.session.SessionRepository;

/**
 * AgentCore-backed {@link SessionRepository}. Wraps an AgentCore memory resource; each
 * {@link SessionEvent} is stored as an AgentCore event under (memoryId, actorId,
 * sessionId, eventTimestamp).
 *
 * <h3>User id and session id</h3> AgentCore has no session-metadata store, so this
 * repository cannot persist the userId supplied on {@code CreateSessionRequest} across
 * restarts. It derives {@code Session.userId} from the actor segment of the sessionId
 * (parsed by {@link AgentCoreMemoryConversationIdParser}). When you also set
 * {@code SessionMemoryAdvisor.USER_ID_CONTEXT_KEY}, use the sessionId format
 * {@code "userId:sessionSuffix"} and pass that same user id under the context key. On the
 * second turn the advisor compares {@code USER_ID_CONTEXT_KEY} against
 * {@code Session.userId()}; if the actor segment does not match,
 * {@code SessionMemoryAdvisor.before()} throws {@link IllegalStateException} with the
 * message "Session '...' does not belong to user '...'. Access denied." Callers that rely
 * only on the advisor's {@code defaultUserId} (no per-request context key) are
 * unaffected. This repository validates its own sessionId seam eagerly: null/blank ids
 * and empty segments (a leading colon, a trailing colon, whitespace-only segments) are
 * rejected up front with a clear {@link IllegalArgumentException}. The pure
 * userId-vs-{@code USER_ID_CONTEXT_KEY} semantic mismatch is owned by the advisor and
 * stays a turn-2 concern; the repository does not receive the context key.
 *
 * <h3>Divergences from the {@link SessionRepository} contract</h3>
 * <ul>
 * <li>{@link #save(Session)} is a no-op. AgentCore has no session-metadata store, so any
 * metadata mutated on the {@link Session} (for example via
 * {@code session.withMetadata(...)}) is not persisted and will not be visible on a
 * subsequent {@link #findById(String)}. Do not rely on this method for metadata
 * persistence.</li>
 * <li>{@link #findByUserId(String)} maps {@code userId} to the AgentCore actor and lists
 * that actor's sessions. It returns compound ids of the form {@code "userId:sessionId"}
 * so the results round-trip through the other methods.</li>
 * <li>{@link #findExpiredSessionIds(Instant)} throws
 * {@link UnsupportedOperationException}: expiry is a memory-level retention
 * ({@code eventExpiryDuration}) applied per event at write time and is not re-derivable
 * per session, and AgentCore reaps events automatically, so an external sweep is
 * unnecessary and unsupported here.</li>
 * <li>{@link #appendEvent(SessionEvent)} does not throw when the session has zero prior
 * events. AgentCore has no notion of session existence separate from events, so the first
 * appendEvent implicitly creates the session server-side. This deviates from the SPI
 * Javadoc.</li>
 * <li>{@link #replaceEvents(String, List)} is a non-atomic delete-then-recreate.
 * AgentCore has no server-side transactional replace, so a createEvent failure partway
 * through can leave partial data on the event log.</li>
 * <li>{@link #replaceEvents(String, List, long)} is a best-effort check-then-act with a
 * race window; AgentCore has no server-side compare-and-swap on the event log.</li>
 * </ul>
 *
 * <h3>replaceEvents is best-effort, not atomic</h3> Both
 * {@link #replaceEvents(String, List)} and its CAS variant
 * {@link #replaceEvents(String, List, long)} delete the existing event log and then
 * recreate it in separate, non-transactional AgentCore calls. A concurrent reader can
 * observe the partial state between the delete and recreate phases. If a
 * {@code createEvent} call fails after the delete phase, the original events are lost and
 * the log is left partial, with no way for this repository to recover without an external
 * backup. There is no server-side lock either, so these methods are not safe under
 * concurrent writers: two callers racing on the same sessionId can interleave delete and
 * recreate and corrupt the log. Hold an external lock (for example a DynamoDB conditional
 * write or Redis SETNX) so that only one writer runs replaceEvents per sessionId, and
 * keep a backup to recover from a mid-flight failure.
 *
 * <h3>Synthesized {@link Session} fields</h3> On {@link #findById(String)} we synthesize
 * a {@link Session} from the event-log tail and the session's {@code SessionSummary}:
 * <ul>
 * <li>{@code id} = the requested sessionId string.</li>
 * <li>{@code userId} = actor segment of the sessionId (see convention above).</li>
 * <li>{@code createdAt} = the {@code SessionSummary.createdAt} when the summary is
 * visible; otherwise the timestamp of the most recent event (a real, non-sentinel value);
 * only if neither is available does it fall back to {@link #SYNTHETIC_CREATED_AT}. The
 * most recent event timestamp is also exposed under metadata key
 * {@value #LAST_EVENT_AT_METADATA_KEY}.</li>
 * <li>{@code expiresAt} = {@code null}: AgentCore has no per-session TTL.</li>
 * <li>{@code metadata} = {@link Map} with keys {@value #ACTOR_ID_METADATA_KEY},
 * {@value #SESSION_METADATA_KEY}, and {@value #LAST_EVENT_AT_METADATA_KEY}.</li>
 * </ul>
 *
 * @author Spring AI Community
 */
public class AgentCoreSessionRepository implements SessionRepository {

	/** Metadata key for the AgentCore event id stamped on a wrapped {@link Message}. */
	public static final String EVENT_ID_METADATA_KEY = "agentcore.eventId";

	/** Metadata key exposing the timestamp of the most recent AgentCore event. */
	public static final String LAST_EVENT_AT_METADATA_KEY = "agentcore.lastEventAt";

	/** Metadata key for the AgentCore actor id derived from the sessionId. */
	public static final String ACTOR_ID_METADATA_KEY = "agentcore.actorId";

	/** Metadata key for the AgentCore session segment derived from the sessionId. */
	public static final String SESSION_METADATA_KEY = "agentcore.session";

	/**
	 * Last-resort createdAt when neither a SessionSummary nor an event timestamp exists.
	 */
	static final Instant SYNTHETIC_CREATED_AT = Instant.EPOCH;

	private static final int SERVICE_MAX_RESULTS = 100;

	private static final Logger logger = LoggerFactory.getLogger(AgentCoreSessionRepository.class);

	private final BedrockAgentCoreClient client;

	private final String memoryId;

	private final Integer totalEventsLimit;

	private final String defaultSession;

	private final int pageSize;

	private final boolean ignoreUnknownRoles;

	private final boolean persistSynthetic;

	public AgentCoreSessionRepository(String memoryId, BedrockAgentCoreClient client, Integer totalEventsLimit,
			String defaultSession, int pageSize, boolean ignoreUnknownRoles, boolean persistSynthetic) {
		this.memoryId = validateMemoryId(memoryId);
		this.client = client;
		this.totalEventsLimit = totalEventsLimit;
		this.defaultSession = defaultSession;
		this.pageSize = pageSize;
		this.ignoreUnknownRoles = ignoreUnknownRoles;
		this.persistSynthetic = persistSynthetic;
	}

	// ==================== Sessions ====================

	/**
	 * No-op. AgentCore has no session-metadata store, so this repository cannot persist
	 * session-level metadata.
	 *
	 * <p>
	 * <strong>Divergence from the {@link SessionRepository} SPI.</strong> Calling
	 * {@code save} persists nothing. Any fields mutated on the supplied {@link Session}
	 * (for example via {@code session.withMetadata(...)} or a changed {@code expiresAt})
	 * are discarded and will not be visible on a subsequent {@link #findById(String)}.
	 * The method returns the same {@link Session} instance it was given, for API
	 * symmetry. Do not rely on it for metadata persistence; session state lives entirely
	 * in the AgentCore event log written by {@link #appendEvent(SessionEvent)}.
	 * @param session the session (must not be null)
	 * @return the same {@code session} instance, unmodified and unpersisted
	 */
	@Override
	public Session save(Session session) {
		if (session == null) {
			throw new IllegalArgumentException("session must not be null");
		}
		logger.debug(
				"AgentCoreSessionRepository.save is a no-op for id: {}; AgentCore has no session-metadata store, so any"
						+ " metadata on the Session is not persisted (see class Javadoc)",
				session.id());
		return session;
	}

	@Override
	public Optional<Session> findById(String sessionId) {
		validateSessionId(sessionId);
		logger.debug("Finding AgentCore session tail for sessionId: {}", sessionId);

		try {
			var actorAndSession = this.actorAndSession(sessionId);
			var request = ListEventsRequest.builder()
				.actorId(actorAndSession.actor())
				.sessionId(actorAndSession.session())
				.memoryId(this.memoryId)
				.maxResults(1)
				.includePayloads(false)
				.build();
			var response = this.client.listEvents(request);
			var events = response.events();
			if (events == null || events.isEmpty()) {
				return Optional.empty();
			}
			Event tail = events.get(0);
			Map<String, Object> metadata = new HashMap<>();
			metadata.put(ACTOR_ID_METADATA_KEY, actorAndSession.actor());
			metadata.put(SESSION_METADATA_KEY, actorAndSession.session());
			if (tail.eventTimestamp() != null) {
				metadata.put(LAST_EVENT_AT_METADATA_KEY, tail.eventTimestamp());
			}
			Instant createdAt = resolveCreatedAt(actorAndSession, tail);
			Session synthesized = Session.builder()
				.id(sessionId)
				.userId(actorAndSession.actor())
				.createdAt(createdAt)
				.expiresAt(null)
				.metadata(metadata)
				.build();
			return Optional.of(synthesized);
		}
		catch (SdkException ex) {
			logger.error("Failed to load session tail for sessionId: {}", sessionId, ex);
			throw new AgentCoreMemoryException.RetrievalException(
					"Failed to load session tail for sessionId: " + sessionId, ex);
		}
	}

	/**
	 * Lists the sessions belonging to a user by mapping {@code userId} to the AgentCore
	 * actor and paginating {@code ListSessions}. Returned {@link Session} ids are
	 * compound ({@code "userId:sessionId"}) so they round-trip through
	 * {@link #findById(String)} and {@link #findEvents(String, EventFilter)}.
	 * {@code createdAt} comes from each {@code SessionSummary}; {@code expiresAt} is
	 * {@code null}. An unknown user yields an empty list rather than an exception.
	 * @param userId the user id (AgentCore actor); must not be blank
	 * @return the user's sessions, possibly empty
	 */
	@Override
	public List<Session> findByUserId(String userId) {
		if (userId == null || userId.trim().isEmpty()) {
			throw new IllegalArgumentException("userId must not be null or empty");
		}
		String actorId = userId.trim();
		int maxResults = Math.min(Math.max(this.pageSize, 1), SERVICE_MAX_RESULTS);
		try {
			List<Session> sessions = new ArrayList<>();
			String nextToken = null;
			do {
				ListSessionsRequest.Builder builder = ListSessionsRequest.builder()
					.memoryId(this.memoryId)
					.actorId(actorId)
					.maxResults(maxResults);
				if (nextToken != null) {
					builder.nextToken(nextToken);
				}
				ListSessionsResponse response = this.client.listSessions(builder.build());
				if (response == null || response.sessionSummaries() == null) {
					break;
				}
				for (SessionSummary summary : response.sessionSummaries()) {
					sessions.add(toSession(actorId, summary));
				}
				nextToken = response.nextToken();
			}
			while (nextToken != null);
			return List.copyOf(sessions);
		}
		catch (SdkException ex) {
			logger.error("Failed to list AgentCore sessions for userId: {}", userId, ex);
			throw new AgentCoreMemoryException.RetrievalException("Failed to list sessions for userId: " + userId, ex);
		}
	}

	@Override
	public List<String> findExpiredSessionIds(Instant before) {
		throw new UnsupportedOperationException(
				"findExpiredSessionIds is unsupported: AgentCore expiry is a memory-level retention "
						+ "(eventExpiryDuration) applied per event at write time and is not re-derivable per "
						+ "session, and AgentCore reaps expired events and empty sessions automatically, so an "
						+ "external sweep is unnecessary. Use findByUserId(userId) to enumerate a user's "
						+ "sessions.");
	}

	@Override
	public void delete(String sessionId) {
		validateSessionId(sessionId);
		logger.debug("Deleting AgentCore session: {}", sessionId);

		try {
			var actorAndSession = this.actorAndSession(sessionId);
			AtomicInteger deleted = new AtomicInteger();
			this.forEachEventPage(actorAndSession, false, false, (page) -> page.forEach((event) -> {
				this.client.deleteEvent(DeleteEventRequest.builder()
					.memoryId(this.memoryId)
					.actorId(actorAndSession.actor())
					.sessionId(actorAndSession.session())
					.eventId(event.eventId())
					.build());
				deleted.incrementAndGet();
			}));
			logger.debug("Deleted {} AgentCore events for sessionId: {}", deleted.get(), sessionId);
		}
		catch (SdkException ex) {
			logger.error("Failed to delete AgentCore session: {}", sessionId, ex);
			throw new AgentCoreMemoryException.StorageException("Failed to delete session: " + sessionId, ex);
		}
	}

	// ==================== Events ====================

	/**
	 * Appends a single event to the AgentCore-backed session log.
	 *
	 * <p>
	 * <strong>Divergence from the {@link SessionRepository} SPI.</strong> AgentCore has
	 * no notion of session existence separate from events. Where the SPI Javadoc says to
	 * throw {@code IllegalArgumentException} for an unknown session, this method does
	 * not: the first appendEvent implicitly creates the AgentCore session. If you need
	 * explicit-existence semantics, call {@link #findById(String)} first.
	 *
	 * <p>
	 * Messages already carrying the {@value #EVENT_ID_METADATA_KEY} metadata key are
	 * treated as previously persisted and silently skipped (delta-append behavior); this
	 * lets a caller re-append a loaded event stream without producing duplicates.
	 * @param event the event to append
	 */
	@Override
	public void appendEvent(SessionEvent event) {
		if (event == null) {
			throw new IllegalArgumentException("event must not be null");
		}
		String sessionId = event.getSessionId();
		validateSessionId(sessionId);

		Message message = event.getMessage();
		if (event.isSynthetic() && !this.persistSynthetic) {
			logger.debug("Skipping synthetic SessionEvent {} for sessionId {} (persistSynthetic=false)", event.getId(),
					sessionId);
			return;
		}
		if (message.getMetadata().get(EVENT_ID_METADATA_KEY) != null) {
			logger.debug("Skipping already-stamped message for sessionId {} (agentcore.eventId present)", sessionId);
			return;
		}
		PayloadType payload = this.buildPayloadType(message);
		if (payload == null) {
			logger.debug("Skipping SessionEvent {} whose message produced no payload for sessionId {}", event.getId(),
					sessionId);
			return;
		}

		try {
			var actorAndSession = this.actorAndSession(sessionId);
			CreateEventRequest request = CreateEventRequest.builder()
				.memoryId(this.memoryId)
				.actorId(actorAndSession.actor())
				.sessionId(actorAndSession.session())
				.payload(List.of(payload))
				.eventTimestamp((event.getTimestamp() != null) ? event.getTimestamp() : Instant.now())
				.clientToken(UUID.randomUUID().toString())
				.build();
			var response = this.client.createEvent(request);
			String eventId = (response.event() != null) ? response.event().eventId() : null;
			if (eventId != null) {
				message.getMetadata().put(EVENT_ID_METADATA_KEY, eventId);
			}
			logger.debug("Appended AgentCore event {} for sessionId {}", eventId, sessionId);
		}
		catch (SdkException ex) {
			logger.error("Failed to append AgentCore event for sessionId: {}", sessionId, ex);
			throw new AgentCoreMemoryException.StorageException("Failed to append event for sessionId: " + sessionId,
					ex);
		}
	}

	/**
	 * Replaces the entire event log for the given session with the supplied events.
	 *
	 * <p>
	 * <strong>Divergence from the {@link SessionRepository} SPI.</strong> AgentCore has
	 * no server-side transactional replace, so this method performs a non-atomic
	 * delete-then-recreate: it first deletes every existing event for the session, then
	 * creates each supplied event in turn.
	 *
	 * <p>
	 * <strong>Best-effort, not safe under concurrent writers.</strong> A concurrent
	 * reader can observe the partial state between the delete and recreate phases. A
	 * crash or a failing {@code createEvent} call after the delete phase leaves the log
	 * partial and the original events lost; the failure is logged at ERROR and is not
	 * retryable, since this repository cannot reconstruct the pre-delete state. There is
	 * no server-side lock, so two callers racing on the same sessionId can interleave and
	 * corrupt the log. Hold an external lock (for example a DynamoDB conditional write or
	 * Redis SETNX) so that only one writer runs replaceEvents per sessionId, and keep a
	 * backup to recover from a mid-flight failure. See the class-level "replaceEvents is
	 * best-effort" section.
	 * @param sessionId the session whose event log is being replaced
	 * @param events the new events to persist after the existing log has been cleared
	 * @throws org.springaicommunity.agentcore.memory.AgentCoreMemoryException.StorageException
	 * if an AgentCore call fails; when this happens after the delete phase the event log
	 * is left partial and the original events are unrecoverable (not retryable)
	 */
	@Override
	public void replaceEvents(String sessionId, List<SessionEvent> events) {
		validateSessionId(sessionId);
		if (events == null) {
			throw new IllegalArgumentException("events must not be null");
		}
		this.doReplaceEvents(sessionId, events);
	}

	@Override
	public boolean replaceEvents(String sessionId, List<SessionEvent> events, long expectedVersion) {
		validateSessionId(sessionId);
		if (events == null) {
			throw new IllegalArgumentException("events must not be null");
		}
		long current = this.getEventVersion(sessionId);
		if (current != expectedVersion) {
			logger.debug("replaceEvents CAS mismatch for sessionId {}: expected={}, current={}; skipping", sessionId,
					expectedVersion, current);
			return false;
		}
		this.doReplaceEvents(sessionId, events);
		return true;
	}

	@Override
	public long getEventVersion(String sessionId) {
		validateSessionId(sessionId);
		try {
			var actorAndSession = this.actorAndSession(sessionId);
			AtomicLong count = new AtomicLong();
			this.forEachEventPage(actorAndSession, false, false, (page) -> count.addAndGet(page.size()));
			return count.get();
		}
		catch (SdkException ex) {
			logger.error("Failed to compute AgentCore event version for sessionId: {}", sessionId, ex);
			throw new AgentCoreMemoryException.RetrievalException(
					"Failed to compute event version for sessionId: " + sessionId, ex);
		}
	}

	@Override
	public List<SessionEvent> findEvents(String sessionId, EventFilter filter) {
		validateSessionId(sessionId);
		if (filter == null) {
			throw new IllegalArgumentException("filter must not be null");
		}
		try {
			var actorAndSession = this.actorAndSession(sessionId);
			List<Event> allEvents = new ArrayList<>();
			this.forEachEventPage(actorAndSession, true, true, allEvents::addAll);
			// AgentCore returns events in descending order (newest first); reverse to
			// chronological order.
			Collections.reverse(allEvents);

			List<SessionEvent> mapped = new ArrayList<>(allEvents.size());
			for (Event event : allEvents) {
				List<Message> messages = this.mapPayloadsToMessages(event, sessionId);
				if (messages.isEmpty()) {
					continue;
				}
				for (Message msg : messages) {
					String generatedId = (event.eventId() != null) ? event.eventId() : UUID.randomUUID().toString();
					SessionEvent sessionEvent = SessionEvent.builder()
						.sessionId(sessionId)
						.id(generatedId)
						.timestamp((event.eventTimestamp() != null) ? event.eventTimestamp() : Instant.EPOCH)
						.message(msg)
						.metadata(Map.of(EVENT_ID_METADATA_KEY, (event.eventId() != null) ? event.eventId() : ""))
						.build();
					mapped.add(sessionEvent);
				}
			}

			List<SessionEvent> matched = mapped.stream()
				.filter(filter::matches)
				.collect(Collectors.toCollection(ArrayList::new));

			if (filter.lastN() != null && matched.size() > filter.lastN()) {
				matched = new ArrayList<>(matched.subList(matched.size() - filter.lastN(), matched.size()));
			}
			if (filter.pageSize() != null) {
				int pageNum = (filter.page() != null) ? filter.page() : 0;
				int size = filter.pageSize();
				int fromIdx = pageNum * size;
				if (fromIdx >= matched.size()) {
					matched = new ArrayList<>();
				}
				else {
					matched = new ArrayList<>(matched.subList(fromIdx, Math.min(fromIdx + size, matched.size())));
				}
			}
			return List.copyOf(matched);
		}
		catch (SdkException ex) {
			logger.error("Failed to fetch AgentCore events for sessionId: {}", sessionId, ex);
			throw new AgentCoreMemoryException.RetrievalException("Failed to fetch events for sessionId: " + sessionId,
					ex);
		}
		catch (RuntimeException ex) {
			// A malformed server event can surface as an NPE/IAE from mapping. Wrap it so
			// callers see the domain RetrievalException, not a raw low-level throwable.
			logger.error("Failed to map AgentCore events for sessionId: {}", sessionId, ex);
			throw new AgentCoreMemoryException.RetrievalException("Failed to fetch events for sessionId: " + sessionId,
					ex);
		}
	}

	// ==================== Helpers ====================

	private void doReplaceEvents(String sessionId, List<SessionEvent> events) {
		// Track progress so a mid-flight failure can be logged with enough context to
		// assess data loss and drive external recovery, since this operation is
		// non-atomic.
		AtomicInteger deleted = new AtomicInteger();
		AtomicInteger recreated = new AtomicInteger();
		boolean deletePhaseComplete = false;
		try {
			var actorAndSession = this.actorAndSession(sessionId);
			// 1. Delete every existing event, paginated.
			this.forEachEventPage(actorAndSession, false, false, (page) -> page.forEach((existing) -> {
				this.client.deleteEvent(DeleteEventRequest.builder()
					.memoryId(this.memoryId)
					.actorId(actorAndSession.actor())
					.sessionId(actorAndSession.session())
					.eventId(existing.eventId())
					.build());
				deleted.incrementAndGet();
			}));
			deletePhaseComplete = true;

			// 2. Recreate each new event. This is a full replacement, so we do not
			// filter by agentcore.eventId metadata.
			for (SessionEvent event : events) {
				Message message = event.getMessage();
				if (event.isSynthetic() && !this.persistSynthetic) {
					continue;
				}
				PayloadType payload = this.buildPayloadType(message);
				if (payload == null) {
					continue;
				}
				CreateEventRequest request = CreateEventRequest.builder()
					.memoryId(this.memoryId)
					.actorId(actorAndSession.actor())
					.sessionId(actorAndSession.session())
					.payload(List.of(payload))
					.eventTimestamp((event.getTimestamp() != null) ? event.getTimestamp() : Instant.now())
					.clientToken(UUID.randomUUID().toString())
					.build();
				var response = this.client.createEvent(request);
				String eventId = (response.event() != null) ? response.event().eventId() : null;
				if (eventId != null) {
					message.getMetadata().put(EVENT_ID_METADATA_KEY, eventId);
				}
				recreated.incrementAndGet();
			}
		}
		catch (SdkException ex) {
			// A failure after the delete phase leaves the log partial and the original
			// events gone. Log at ERROR with recovery context; this repository cannot
			// retry it, because the pre-delete state is no longer available.
			if (deletePhaseComplete) {
				logger.error("Data loss replacing AgentCore events for sessionId {}: delete completed ({} deleted)"
						+ " but recreate failed after {} of {} events. The original log is gone and the current log"
						+ " is partial. replaceEvents is non-atomic and cannot be retried here; recover from an"
						+ " external backup and hold an external lock per sessionId to prevent concurrent writers."
						+ " See AgentCoreSessionRepository Javadoc.", sessionId, deleted.get(), recreated.get(),
						events.size(), ex);
			}
			else {
				logger.error(
						"Failed to replace AgentCore events for sessionId {} during the delete phase ({} events"
								+ " deleted before failure); the event log may be partially deleted.",
						sessionId, deleted.get(), ex);
			}
			throw new AgentCoreMemoryException.StorageException("Failed to replace events for sessionId: " + sessionId,
					ex);
		}
	}

	private Instant resolveCreatedAt(AgentCoreMemoryConversationIdParser.ActorAndSession actorAndSession, Event tail) {
		try {
			String nextToken = null;
			int maxResults = Math.min(Math.max(this.pageSize, 1), SERVICE_MAX_RESULTS);
			do {
				ListSessionsRequest.Builder builder = ListSessionsRequest.builder()
					.memoryId(this.memoryId)
					.actorId(actorAndSession.actor())
					.maxResults(maxResults);
				if (nextToken != null) {
					builder.nextToken(nextToken);
				}
				ListSessionsResponse response = this.client.listSessions(builder.build());
				if (response == null || response.sessionSummaries() == null) {
					break;
				}
				for (SessionSummary summary : response.sessionSummaries()) {
					if (actorAndSession.session().equals(summary.sessionId()) && summary.createdAt() != null) {
						return summary.createdAt();
					}
				}
				nextToken = response.nextToken();
			}
			while (nextToken != null);
		}
		catch (SdkException ex) {
			logger.debug("Could not read SessionSummary.createdAt for actor {} session {}; falling back to the tail"
					+ " event timestamp", actorAndSession.actor(), actorAndSession.session(), ex);
		}
		if (tail != null && tail.eventTimestamp() != null) {
			return tail.eventTimestamp();
		}
		return SYNTHETIC_CREATED_AT;
	}

	private Session toSession(String actorId, SessionSummary summary) {
		String compoundId = AgentCoreMemoryConversationIdParser.of(actorId, summary.sessionId());
		Map<String, Object> metadata = new HashMap<>();
		metadata.put(ACTOR_ID_METADATA_KEY, actorId);
		metadata.put(SESSION_METADATA_KEY, summary.sessionId());
		return Session.builder()
			.id(compoundId)
			.userId(actorId)
			.createdAt((summary.createdAt() != null) ? summary.createdAt() : SYNTHETIC_CREATED_AT)
			.expiresAt(null)
			.metadata(metadata)
			.build();
	}

	private List<Message> mapPayloadsToMessages(Event event, String sessionId) {
		List<Message> out = new ArrayList<>();
		if (event.payload() == null) {
			return out;
		}
		String eventId = event.eventId();
		for (PayloadType payload : event.payload()) {
			if (payload.conversational() == null) {
				continue;
			}
			if (payload.conversational().content() == null || payload.conversational().content().text() == null) {
				logger.warn("Skipping malformed AgentCore event {} for sessionId {}: conversational payload has null"
						+ " content or text", eventId, sessionId);
				continue;
			}
			Role role = payload.conversational().role();
			Message message = switch (role) {
				case ASSISTANT -> AssistantMessage.builder()
					.content(payload.conversational().content().text())
					.properties((eventId != null) ? Map.of(EVENT_ID_METADATA_KEY, eventId) : Map.of())
					.build();
				case USER -> UserMessage.builder()
					.text(payload.conversational().content().text())
					.metadata((eventId != null) ? Map.of(EVENT_ID_METADATA_KEY, eventId) : Map.of())
					.build();
				default -> {
					if (this.ignoreUnknownRoles) {
						logger.warn("Ignoring unknown role: {}", role);
						yield null;
					}
					throw new IllegalStateException("Unsupported role: " + role);
				}
			};
			if (message != null) {
				out.add(message);
			}
		}
		return out.stream().filter(Objects::nonNull).toList();
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
			if (this.ignoreUnknownRoles) {
				logger.warn("Ignoring unknown message type: {}", message.getClass().getSimpleName());
				return null;
			}
			throw new IllegalStateException("Unsupported message type: " + message.getClass().getSimpleName());
		}
		if (message.getText() == null || message.getText().isBlank()) {
			logger.debug("Skipping empty-text message for role {}: {}", role, message.getClass().getSimpleName());
			return null;
		}
		Content content = Content.builder().text(message.getText()).build();
		Conversational conversational = Conversational.builder().content(content).role(role).build();
		return PayloadType.builder().conversational(conversational).build();
	}

	private void forEachEventPage(AgentCoreMemoryConversationIdParser.ActorAndSession actorAndSession,
			boolean includePayloads, boolean respectLimit, Consumer<List<Event>> handler) {
		String nextToken = null;
		int requestPageSize = (respectLimit && this.totalEventsLimit != null)
				? Math.min(this.pageSize, this.totalEventsLimit) : this.pageSize;
		int seen = 0;
		do {
			ListEventsRequest.Builder builder = ListEventsRequest.builder()
				.actorId(actorAndSession.actor())
				.sessionId(actorAndSession.session())
				.memoryId(this.memoryId)
				.includePayloads(includePayloads)
				.maxResults(requestPageSize);
			if (nextToken != null) {
				builder.nextToken(nextToken);
			}
			var response = this.client.listEvents(builder.build());
			if (response == null || response.events() == null) {
				break;
			}
			List<Event> page = response.events();
			if (respectLimit && this.totalEventsLimit != null && seen + page.size() > this.totalEventsLimit) {
				page = page.subList(0, this.totalEventsLimit - seen);
			}
			handler.accept(page);
			seen += page.size();
			nextToken = response.nextToken();
			if (respectLimit && this.totalEventsLimit != null && seen >= this.totalEventsLimit) {
				break;
			}
		}
		while (nextToken != null);
	}

	AgentCoreMemoryConversationIdParser.ActorAndSession actorAndSession(String sessionId) {
		AgentCoreMemoryConversationIdParser.ActorAndSession parsed = AgentCoreMemoryConversationIdParser
			.parse(sessionId, this.defaultSession);
		String actor = parsed.actor().trim();
		String session = parsed.session().trim();
		if (actor.isEmpty()) {
			throw new IllegalArgumentException("sessionId '" + sessionId
					+ "' has an empty actor segment; expected 'actorId' or 'actorId:sessionId'");
		}
		if (sessionId.contains(":") && session.isEmpty()) {
			throw new IllegalArgumentException("sessionId '" + sessionId
					+ "' has an empty session segment; expected 'actorId' or 'actorId:sessionId'");
		}
		if (sessionId.indexOf(':') != sessionId.lastIndexOf(':')) {
			logger.debug("Multi-colon sessionId '{}' parsed as actor '{}' session '{}' (split on first colon)",
					sessionId, actor, session);
		}
		return new AgentCoreMemoryConversationIdParser.ActorAndSession(actor, session);
	}

	private static String validateMemoryId(String memoryId) {
		if (memoryId == null || memoryId.trim().isEmpty()) {
			throw new IllegalArgumentException("MemoryId cannot be null or empty");
		}
		return memoryId;
	}

	private static void validateSessionId(String sessionId) {
		if (sessionId == null || sessionId.trim().isEmpty()) {
			throw new IllegalArgumentException("sessionId must not be null or empty");
		}
	}

}
