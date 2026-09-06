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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springaicommunity.agentcore.memory.AgentCoreMemoryConversationIdParser;
import org.springaicommunity.agentcore.memory.AgentCoreMemoryException;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.bedrockagentcore.BedrockAgentCoreClient;
import software.amazon.awssdk.services.bedrockagentcore.model.Branch;
import software.amazon.awssdk.services.bedrockagentcore.model.BranchFilter;
import software.amazon.awssdk.services.bedrockagentcore.model.Content;
import software.amazon.awssdk.services.bedrockagentcore.model.Conversational;
import software.amazon.awssdk.services.bedrockagentcore.model.CreateEventRequest;
import software.amazon.awssdk.services.bedrockagentcore.model.DeleteEventRequest;
import software.amazon.awssdk.services.bedrockagentcore.model.Event;
import software.amazon.awssdk.services.bedrockagentcore.model.FilterInput;
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
 * rejected up front with a clear {@link IllegalArgumentException}.
 *
 * <p>
 * <strong>Security.</strong> The sessionId (and therefore the derived
 * {@code Session.userId}) is client-supplied input. A caller that controls the
 * conversationId chooses the actor, and the advisor's ownership check compares two values
 * derived from that same string, so it does not by itself stop a hostile caller from
 * reading another user's session. Where an authenticated principal exists, the
 * application layer MUST build the conversationId's actor segment from the principal —
 * never from unvalidated request input. Deriving only the {@code USER_ID_CONTEXT_KEY}
 * value from the principal is NOT sufficient: the advisor's ownership check runs only
 * when the target session already exists, so a first write to a fresh sessionId passes
 * regardless of the context key. See the module README security section.
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
 * Javadoc. Synthetic events (framework generated, for example compaction summaries) are
 * never persisted; they are skipped with a DEBUG log.</li>
 * <li>{@link #replaceEvents(String, List)} and {@link #replaceEvents(String, List, long)}
 * throw {@link UnsupportedOperationException}. AgentCore has no transactional replace and
 * no compare-and-set on the event log, so any client-side rewrite (delete-then-recreate,
 * or a branch-plus-pointer swap) is inherently racy: a concurrent {@code appendEvent} can
 * be silently lost and a mid-flight failure can leave a partial log. The AgentCore-native
 * way to bound context is read-windowing ({@code totalEventsLimit},
 * {@code EventFilter.lastN(int)}) plus long-term memory extraction — not in-place log
 * rewriting. The event log is append-only here: {@link #appendEvent(SessionEvent)} and
 * {@link #delete(String)} are the only write paths, and neither needs locking.</li>
 * </ul>
 *
 * <h3>Synthesized {@link Session} fields</h3> On {@link #findById(String)} we synthesize
 * a {@link Session} from the event-log tail:
 * <ul>
 * <li>{@code id} = the requested sessionId string.</li>
 * <li>{@code userId} = actor segment of the sessionId (see convention above).</li>
 * <li>{@code createdAt} = the timestamp of the most recent event (a real, non-sentinel
 * value already fetched for the tail read); only when that event carries no timestamp
 * does it fall back to {@link #SYNTHETIC_CREATED_AT}. {@code findById} intentionally does
 * not call {@code ListSessions} to read {@code SessionSummary.createdAt}: that would add
 * an O(all-sessions) scan and the {@code ListSessions} IAM permission to the common read
 * path for a field most callers ignore. {@link #findByUserId(String)} still surfaces the
 * true {@code SessionSummary.createdAt}. The most recent event timestamp is also exposed
 * under metadata key {@value #LAST_EVENT_AT_METADATA_KEY}.</li>
 * <li>{@code expiresAt} = {@code null}: AgentCore has no per-session TTL.</li>
 * <li>{@code metadata} = {@link Map} with keys {@value #ACTOR_ID_METADATA_KEY},
 * {@value #SESSION_METADATA_KEY}, and {@value #LAST_EVENT_AT_METADATA_KEY}.</li>
 * </ul>
 *
 * @author Spring AI Community
 */
public final class AgentCoreSessionRepository implements SessionRepository {

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

	private static final String REPLACE_EVENTS_UNSUPPORTED = "replaceEvents is unsupported: AgentCore has no"
			+ " transactional replace and no compare-and-set on the event log, so any client-side rewrite risks"
			+ " losing a concurrent appendEvent or leaving a partial log on mid-flight failure. Bound context via"
			+ " read-windowing (totalEventsLimit, EventFilter.lastN) plus AgentCore long-term memory extraction"
			+ " instead of rewriting the log.";

	private static final Logger logger = LoggerFactory.getLogger(AgentCoreSessionRepository.class);

	private final BedrockAgentCoreClient client;

	private final String memoryId;

	private final Integer totalEventsLimit;

	private final String defaultSession;

	private final int pageSize;

	private final boolean ignoreUnknownRoles;

	private AgentCoreSessionRepository(Builder builder) {
		this.memoryId = validateMemoryId(builder.memoryId);
		if (builder.client == null) {
			throw new IllegalArgumentException("client must not be null");
		}
		if (builder.pageSize < 1) {
			throw new IllegalArgumentException("pageSize must be >= 1, got " + builder.pageSize);
		}
		if (builder.totalEventsLimit != null && builder.totalEventsLimit < 1) {
			throw new IllegalArgumentException(
					"totalEventsLimit must be null (unbounded) or >= 1, got " + builder.totalEventsLimit);
		}
		if (builder.defaultSession == null || builder.defaultSession.isBlank()) {
			throw new IllegalArgumentException("defaultSession must not be null or blank");
		}
		this.client = builder.client;
		this.totalEventsLimit = builder.totalEventsLimit;
		this.defaultSession = builder.defaultSession;
		this.pageSize = builder.pageSize;
		this.ignoreUnknownRoles = builder.ignoreUnknownRoles;
	}

	public static Builder builder() {
		return new Builder();
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
			var response = this.client.listEvents(ListEventsRequest.builder()
				.actorId(actorAndSession.actor())
				.sessionId(actorAndSession.session())
				.memoryId(this.memoryId)
				.maxResults(1)
				.includePayloads(false)
				.build());
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
			// createdAt is derived from the tail event timestamp already fetched here (a
			// real, non-sentinel value), falling back to SYNTHETIC_CREATED_AT only when
			// the tail carries no timestamp. findById does NOT call ListSessions: paying
			// an O(all-sessions) scan to synthesize a field most callers ignore is not
			// worth it, and it keeps the ListSessions IAM permission off the common read
			// path (findByUserId is the only method that needs it).
			Instant createdAt = (tail.eventTimestamp() != null) ? tail.eventTimestamp() : SYNTHETIC_CREATED_AT;
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
					sessions.add(this.toSession(actorId, summary));
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
			this.forEachEventPage(actorAndSession, false, false, null, (page) -> {
				page.forEach((event) -> {
					this.deleteEvent(actorAndSession, event.eventId());
					deleted.incrementAndGet();
				});
				return true;
			});
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
	 * Synthetic events (framework generated, for example compaction summaries) are never
	 * persisted; they are skipped with a DEBUG log. Messages already carrying the
	 * {@value #EVENT_ID_METADATA_KEY} metadata key are treated as previously persisted
	 * and silently skipped (delta-append behavior); this lets a caller re-append a loaded
	 * event stream without producing duplicates. When the event carries a branch
	 * ({@code SessionEvent.getBranch()}), it is written to that AgentCore branch so a
	 * later {@code findEvents} with {@code EventFilter.forBranch(...)} round-trips.
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
		if (event.isSynthetic()) {
			logger.debug("Skipping synthetic SessionEvent {} for sessionId {}; synthetic events are not persisted",
					event.getId(), sessionId);
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
			CreateEventRequest.Builder request = CreateEventRequest.builder()
				.memoryId(this.memoryId)
				.actorId(actorAndSession.actor())
				.sessionId(actorAndSession.session())
				.payload(List.of(payload))
				.eventTimestamp((event.getTimestamp() != null) ? event.getTimestamp() : Instant.now())
				.clientToken(UUID.randomUUID().toString());
			if (event.getBranch() != null && !event.getBranch().isBlank()) {
				request.branch(Branch.builder().name(event.getBranch()).build());
			}
			var response = this.client.createEvent(request.build());
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
	 * Unsupported. AgentCore has no transactional replace and no compare-and-set on the
	 * event log, so a faithful {@code replaceEvents} cannot be implemented without silent
	 * data-loss races (a concurrent {@link #appendEvent(SessionEvent)} vanishing, or a
	 * mid-flight failure leaving a partial log). Bound context with read-windowing
	 * ({@code totalEventsLimit}, {@code EventFilter.lastN(int)}) and AgentCore long-term
	 * memory extraction instead of rewriting the log.
	 * @param sessionId the session whose event log would be replaced
	 * @param events the replacement events
	 * @throws UnsupportedOperationException always
	 */
	@Override
	public void replaceEvents(String sessionId, List<SessionEvent> events) {
		throw new UnsupportedOperationException(REPLACE_EVENTS_UNSUPPORTED);
	}

	/**
	 * Unsupported, for the same reasons as {@link #replaceEvents(String, List)}; the
	 * {@code expectedVersion} check cannot be honored either, because AgentCore offers no
	 * server-side compare-and-set to make check-then-act atomic.
	 * @param sessionId the session whose event log would be replaced
	 * @param events the replacement events
	 * @param expectedVersion the version the caller expects
	 * @return never returns
	 * @throws UnsupportedOperationException always
	 */
	@Override
	public boolean replaceEvents(String sessionId, List<SessionEvent> events, long expectedVersion) {
		throw new UnsupportedOperationException(REPLACE_EVENTS_UNSUPPORTED);
	}

	@Override
	public long getEventVersion(String sessionId) {
		validateSessionId(sessionId);
		try {
			var actorAndSession = this.actorAndSession(sessionId);
			AtomicLong count = new AtomicLong();
			this.forEachEventPage(actorAndSession, false, false, null, (page) -> {
				count.addAndGet(page.size());
				return true;
			});
			return count.get();
		}
		catch (SdkException ex) {
			logger.error("Failed to compute AgentCore event version for sessionId: {}", sessionId, ex);
			throw new AgentCoreMemoryException.RetrievalException(
					"Failed to compute event version for sessionId: " + sessionId, ex);
		}
	}

	/**
	 * Fetches events for a session, applying the {@link EventFilter}.
	 *
	 * <p>
	 * {@code filter.branch()} is pushed down to the service as an AgentCore branch filter
	 * with {@code includeParentBranches=true}, matching the SPI reference semantics (a
	 * branch read returns the pre-fork history too). AgentCore's ListEvents offers no
	 * server-side time or content filtering, so the remaining predicates
	 * ({@code from}/{@code to}, message types, keyword) are applied client-side. Because
	 * the service returns events newest-first, a {@code lastN} query stops paginating as
	 * soon as {@code lastN} matches are collected instead of fetching the whole log —
	 * this keeps the common per-turn advisor read O(lastN), not O(session). Paged queries
	 * and unbounded queries fetch up to {@code totalEventsLimit} (when configured).
	 * @param sessionId the session to read
	 * @param filter the filter to apply (must not be null)
	 * @return the matching events in chronological order
	 */
	@Override
	public List<SessionEvent> findEvents(String sessionId, EventFilter filter) {
		validateSessionId(sessionId);
		if (filter == null) {
			throw new IllegalArgumentException("filter must not be null");
		}
		// Parse outside the try: a malformed sessionId must surface as the documented
		// IllegalArgumentException, not be swallowed by the RetrievalException wrap
		// below.
		var actorAndSession = this.actorAndSession(sessionId);
		try {
			// Events arrive newest-first; collect matches grouped per event so the
			// early-stop for lastN never splits one event's messages.
			List<List<SessionEvent>> matchedPerEvent = new ArrayList<>();
			AtomicInteger matchedCount = new AtomicInteger();
			// EventFilter rejects lastN combined with page/pageSize at construction, so
			// stopping early on lastN can never race the paged path.
			boolean stopAtLastN = filter.lastN() != null;
			this.forEachEventPage(actorAndSession, true, true, branchFilter(filter.branch()), (page) -> {
				for (Event event : page) {
					List<SessionEvent> matched = this.toMatchedSessionEvents(event, sessionId, filter);
					if (!matched.isEmpty()) {
						matchedPerEvent.add(matched);
						matchedCount.addAndGet(matched.size());
					}
					if (stopAtLastN && matchedCount.get() >= filter.lastN()) {
						return false;
					}
				}
				return true;
			});

			// Flatten back to chronological order (oldest first).
			List<SessionEvent> matched = new ArrayList<>(matchedCount.get());
			for (int i = matchedPerEvent.size() - 1; i >= 0; i--) {
				matched.addAll(matchedPerEvent.get(i));
			}
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

	// Maps one AgentCore event to SessionEvents (one per conversational message, in
	// intra-event order) and keeps only those the filter matches.
	private List<SessionEvent> toMatchedSessionEvents(Event event, String sessionId, EventFilter filter) {
		List<Message> messages = this.mapPayloadsToMessages(event, sessionId);
		if (messages.isEmpty()) {
			return List.of();
		}
		List<SessionEvent> matched = new ArrayList<>(messages.size());
		for (int i = 0; i < messages.size(); i++) {
			// SessionEvent equality is (id, sessionId), so the messages of one
			// multi-payload event need distinct ids; the raw eventId stays available
			// under EVENT_ID_METADATA_KEY.
			String generatedId;
			if (event.eventId() == null) {
				generatedId = UUID.randomUUID().toString();
			}
			else {
				generatedId = (messages.size() == 1) ? event.eventId() : event.eventId() + "#" + i;
			}
			SessionEvent sessionEvent = SessionEvent.builder()
				.sessionId(sessionId)
				.id(generatedId)
				.timestamp((event.eventTimestamp() != null) ? event.eventTimestamp() : Instant.EPOCH)
				.message(messages.get(i))
				.metadata(Map.of(EVENT_ID_METADATA_KEY, (event.eventId() != null) ? event.eventId() : ""))
				.build();
			if (filter.matches(sessionEvent)) {
				matched.add(sessionEvent);
			}
		}
		return matched;
	}

	private void deleteEvent(AgentCoreMemoryConversationIdParser.ActorAndSession actorAndSession, String eventId) {
		this.client.deleteEvent(DeleteEventRequest.builder()
			.memoryId(this.memoryId)
			.actorId(actorAndSession.actor())
			.sessionId(actorAndSession.session())
			.eventId(eventId)
			.build());
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

	// ==================== mapping ====================

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
		return out;
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

	// ==================== pagination ====================

	// Streams pages of events (newest first). The handler returns false to stop
	// paginating early; respectLimit caps the total events seen at totalEventsLimit.
	private void forEachEventPage(AgentCoreMemoryConversationIdParser.ActorAndSession actorAndSession,
			boolean includePayloads, boolean respectLimit, FilterInput filter, Predicate<List<Event>> pageHandler) {
		String nextToken = null;
		int requestPageSize = (respectLimit && this.totalEventsLimit != null)
				? Math.min(this.pageSize, this.totalEventsLimit) : this.pageSize;
		// ListEvents accepts maxResults 1-100; clamp like findByUserId does for
		// ListSessions so an oversized page-size property degrades instead of failing.
		requestPageSize = Math.min(Math.max(requestPageSize, 1), SERVICE_MAX_RESULTS);
		int seen = 0;
		do {
			ListEventsRequest.Builder builder = ListEventsRequest.builder()
				.actorId(actorAndSession.actor())
				.sessionId(actorAndSession.session())
				.memoryId(this.memoryId)
				.includePayloads(includePayloads)
				.maxResults(requestPageSize);
			if (filter != null) {
				builder.filter(filter);
			}
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
			if (!pageHandler.test(page)) {
				break;
			}
			seen += page.size();
			nextToken = response.nextToken();
			if (respectLimit && this.totalEventsLimit != null && seen >= this.totalEventsLimit) {
				break;
			}
		}
		while (nextToken != null);
	}

	private static FilterInput branchFilter(String branchName) {
		if (branchName == null) {
			return null;
		}
		// includeParentBranches(true) matches the SPI reference semantics
		// (InMemorySessionRepository): reading a branch returns its full history,
		// including the pre-fork main-line events.
		return FilterInput.builder()
			.branch(BranchFilter.builder().name(branchName).includeParentBranches(true).build())
			.build();
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
		// The sessionId is client-supplied and is echoed into logs and AWS requests;
		// control characters are never legitimate and enable log forging (CRLF).
		for (int i = 0; i < sessionId.length(); i++) {
			char c = sessionId.charAt(i);
			if (c < 0x20 || c == 0x7F) {
				String found = "(found U+%04X at index %d)".formatted((int) c, i);
				throw new IllegalArgumentException("sessionId must not contain control characters " + found);
			}
		}
	}

	/**
	 * Builder for {@link AgentCoreSessionRepository}.
	 */
	public static final class Builder {

		private String memoryId;

		private BedrockAgentCoreClient client;

		private Integer totalEventsLimit;

		private String defaultSession = AgentCoreMemoryConversationIdParser.DEFAULT_SESSION;

		private int pageSize = 100;

		private boolean ignoreUnknownRoles = true;

		private Builder() {
		}

		/**
		 * The AgentCore memory resource id (required).
		 * @param memoryId the memory id
		 * @return this builder
		 */
		public Builder memoryId(String memoryId) {
			this.memoryId = memoryId;
			return this;
		}

		/**
		 * The AgentCore client (required).
		 * @param client the client
		 * @return this builder
		 */
		public Builder client(BedrockAgentCoreClient client) {
			this.client = client;
			return this;
		}

		/**
		 * Maximum events to retrieve per read; {@code null} (the default) means
		 * unbounded.
		 * @param totalEventsLimit the limit, or {@code null} for unbounded
		 * @return this builder
		 */
		public Builder totalEventsLimit(Integer totalEventsLimit) {
			this.totalEventsLimit = totalEventsLimit;
			return this;
		}

		/**
		 * Session segment used when a sessionId carries no colon; defaults to
		 * {@link AgentCoreMemoryConversationIdParser#DEFAULT_SESSION}.
		 * @param defaultSession the default session segment
		 * @return this builder
		 */
		public Builder defaultSession(String defaultSession) {
			this.defaultSession = defaultSession;
			return this;
		}

		/**
		 * ListEvents page size; defaults to 100.
		 * @param pageSize the page size
		 * @return this builder
		 */
		public Builder pageSize(int pageSize) {
			this.pageSize = pageSize;
			return this;
		}

		/**
		 * Whether to skip (rather than reject) non-dialogue messages; defaults to
		 * {@code true}.
		 * @param ignoreUnknownRoles {@code true} to skip unknown roles
		 * @return this builder
		 */
		public Builder ignoreUnknownRoles(boolean ignoreUnknownRoles) {
			this.ignoreUnknownRoles = ignoreUnknownRoles;
			return this;
		}

		public AgentCoreSessionRepository build() {
			return new AgentCoreSessionRepository(this);
		}

	}

}
