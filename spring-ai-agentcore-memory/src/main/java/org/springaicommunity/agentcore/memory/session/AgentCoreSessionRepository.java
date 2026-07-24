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

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
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
import software.amazon.awssdk.services.bedrockagentcore.model.Branch;
import software.amazon.awssdk.services.bedrockagentcore.model.BranchFilter;
import software.amazon.awssdk.services.bedrockagentcore.model.Content;
import software.amazon.awssdk.services.bedrockagentcore.model.Conversational;
import software.amazon.awssdk.services.bedrockagentcore.model.CreateEventRequest;
import software.amazon.awssdk.services.bedrockagentcore.model.DeleteEventRequest;
import software.amazon.awssdk.services.bedrockagentcore.model.Event;
import software.amazon.awssdk.services.bedrockagentcore.model.EventMetadataFilterExpression;
import software.amazon.awssdk.services.bedrockagentcore.model.FilterInput;
import software.amazon.awssdk.services.bedrockagentcore.model.LeftExpression;
import software.amazon.awssdk.services.bedrockagentcore.model.ListEventsRequest;
import software.amazon.awssdk.services.bedrockagentcore.model.ListSessionsRequest;
import software.amazon.awssdk.services.bedrockagentcore.model.ListSessionsResponse;
import software.amazon.awssdk.services.bedrockagentcore.model.MetadataValue;
import software.amazon.awssdk.services.bedrockagentcore.model.OperatorType;
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
 * <li>{@link #replaceEvents(String, List)} is a non-destructive branch-swap when
 * branch-swap is enabled, and a legacy delete-then-recreate otherwise. See the
 * concurrency section below.</li>
 * <li>{@link #replaceEvents(String, List, long)} is a best-effort check-then-act with a
 * race window; AgentCore has no server-side compare-and-swap on the event log.</li>
 * </ul>
 *
 * <h3>replaceEvents concurrency semantics</h3> AgentCore offers no server-side
 * transactional replace and no compare-and-swap on the event log. Two strategies are
 * supported, selected by the {@code agentcore.memory.session.branch-swap-enabled}
 * property:
 * <ul>
 * <li><strong>Branch-swap (opt-in).</strong> {@code replaceEvents} writes the full
 * replacement timeline to a fresh branch named {@code gen-<counter>-<8hex>}, then makes
 * that branch the current read target by writing a small pointer marker on the main line
 * carrying {@value #GENERATION_METADATA_KEY}. Discovery is highest-generation-wins over
 * the pointer ledger (not list position, since eventTimestamp is caller-supplied and
 * ListEvents ordering is not guaranteed); ties on generation are broken deterministically
 * by lexicographic branch name. This is non-destructive: a failed replacement leaves the
 * old branch current, so readers never see a partial timeline. It is NOT a CAS.
 * Concurrent {@code replaceEvents} calls are resolved highest-generation-wins; between
 * replacers no events are interleaved (each writes an isolated branch), but a whole
 * replacement can be silently superseded by a concurrent higher-generation one. If you
 * require exactly-one- winner semantics, hold an external lock per sessionId.</li>
 * <li><strong>Legacy delete-then-recreate (default).</strong> When branch-swap is
 * disabled, {@code replaceEvents} deletes the existing log and recreates it in separate,
 * non-transactional calls. A {@code createEvent} failure after the delete phase leaves
 * the log partial and the original events lost (logged at ERROR, not retryable here). On
 * a session that was already migrated to branch mode, the disabled path refuses rather
 * than destroying the ledger; re-enable branch-swap or run the migrate-back utility.</li>
 * </ul>
 * <strong>appendEvent vs replaceEvents (silent orphan window).</strong> The no-interleave
 * guarantee is scoped to replacer-vs-replacer only. An {@link #appendEvent(SessionEvent)}
 * that races a concurrent {@code replaceEvents} can land on a branch that is immediately
 * superseded, making the appended event invisible to subsequent reads with no error
 * raised. To avoid this, an external per-session lock MUST cover {@code appendEvent} AND
 * both {@code replaceEvents} variants together, not just concurrent replacers. No code
 * mechanism eliminates this without server CAS, which AgentCore does not provide.
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

	/** Pointer-marker metadata key naming the current read-target branch. */
	public static final String CURRENT_BRANCH_METADATA_KEY = "agentcore.currentBranch";

	/** Pointer-marker metadata key carrying the zero-padded generation counter. */
	public static final String GENERATION_METADATA_KEY = "agentcore.gen";

	/** Pointer-marker metadata key ({@code "true"}) flagging a pointer marker event. */
	public static final String POINTER_MARKER_METADATA_KEY = "agentcore.pointer";

	/**
	 * Last-resort createdAt when neither a SessionSummary nor an event timestamp exists.
	 */
	static final Instant SYNTHETIC_CREATED_AT = Instant.EPOCH;

	private static final String BRANCH_NAME_FORMAT = "gen-%05d-%s";

	private static final int SERVICE_MAX_RESULTS = 100;

	private static final Logger logger = LoggerFactory.getLogger(AgentCoreSessionRepository.class);

	private final BedrockAgentCoreClient client;

	private final String memoryId;

	private final Integer totalEventsLimit;

	private final String defaultSession;

	private final int pageSize;

	private final boolean ignoreUnknownRoles;

	private final boolean persistSynthetic;

	private final boolean branchSwapEnabled;

	private final boolean deleteSupersededBranch;

	private final BranchResolutionCache branchCache;

	// Legacy constructor: branch-swap disabled, no superseded-branch cleanup, no branch
	// cache. Retained for callers that predate the branch-swap tunables.
	public AgentCoreSessionRepository(String memoryId, BedrockAgentCoreClient client, Integer totalEventsLimit,
			String defaultSession, int pageSize, boolean ignoreUnknownRoles, boolean persistSynthetic) {
		this(memoryId, client, totalEventsLimit, defaultSession, pageSize, ignoreUnknownRoles, persistSynthetic, false,
				false, false, null);
	}

	// Full constructor including the branch-swap and resolution-cache tunables.
	@SuppressWarnings("checkstyle:parameternumber")
	public AgentCoreSessionRepository(String memoryId, BedrockAgentCoreClient client, Integer totalEventsLimit,
			String defaultSession, int pageSize, boolean ignoreUnknownRoles, boolean persistSynthetic,
			boolean branchSwapEnabled, boolean deleteSupersededBranch, boolean branchCacheEnabled,
			Duration branchCacheTtl) {
		this.memoryId = validateMemoryId(memoryId);
		this.client = client;
		this.totalEventsLimit = totalEventsLimit;
		this.defaultSession = defaultSession;
		this.pageSize = pageSize;
		this.ignoreUnknownRoles = ignoreUnknownRoles;
		this.persistSynthetic = persistSynthetic;
		this.branchSwapEnabled = branchSwapEnabled;
		this.deleteSupersededBranch = deleteSupersededBranch;
		this.branchCache = branchCacheEnabled ? new BranchResolutionCache(branchCacheTtl) : null;
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
			String branch = resolveCurrentBranch(actorAndSession);
			var builder = ListEventsRequest.builder()
				.actorId(actorAndSession.actor())
				.sessionId(actorAndSession.session())
				.memoryId(this.memoryId)
				.maxResults(1)
				.includePayloads(false);
			FilterInput branchFilter = branchFilter(branch);
			if (branchFilter != null) {
				builder.filter(branchFilter);
			}
			var response = this.client.listEvents(builder.build());
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
			List<PointerMarker> ledger = readLedger(actorAndSession);
			AtomicInteger deleted = new AtomicInteger();

			// 1. Delete every branch recorded in the ledger.
			for (PointerMarker marker : ledger) {
				this.forEachEventPage(actorAndSession, false, false, branchFilter(marker.branchName()),
						(page) -> page.forEach((event) -> {
							deleteEvent(actorAndSession, event.eventId());
							deleted.incrementAndGet();
						}));
			}
			// 2. Delete all main-line pointer markers.
			for (PointerMarker marker : ledger) {
				deleteEvent(actorAndSession, marker.eventId());
				deleted.incrementAndGet();
			}
			// 3. Delete any remaining main-line events (v1/pre-migration tail).
			this.forEachEventPage(actorAndSession, false, false, null, (page) -> page.forEach((event) -> {
				deleteEvent(actorAndSession, event.eventId());
				deleted.incrementAndGet();
			}));
			if (this.branchCache != null) {
				this.branchCache.invalidate(actorAndSession);
			}
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
	 * When branch-swap is enabled and the session has been migrated, the event is
	 * appended to the current branch (resolved by pointer-marker discovery). This is a
	 * per-message hot path, so it pays a branch resolution before its write; ledger
	 * compaction keeps the steady-state marker count at one, and the optional
	 * per-instance resolution cache drops a warm append to zero extra AWS calls. A
	 * main-line (never-replaced) session appends with no branch, preserving v1 behavior.
	 *
	 * <p>
	 * <strong>Concurrency.</strong> An appendEvent that races a concurrent
	 * {@code replaceEvents} can land on a branch that is immediately superseded, making
	 * the appended event invisible to later reads with no error raised. Hold an external
	 * per-session lock covering appendEvent AND both replaceEvents variants together (see
	 * class Javadoc).
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
			String branch = resolveCurrentBranch(actorAndSession);
			CreateEventRequest.Builder builder = CreateEventRequest.builder()
				.memoryId(this.memoryId)
				.actorId(actorAndSession.actor())
				.sessionId(actorAndSession.session())
				.payload(List.of(payload))
				.eventTimestamp((event.getTimestamp() != null) ? event.getTimestamp() : Instant.now())
				.clientToken(UUID.randomUUID().toString());
			if (branch != null) {
				builder.branch(Branch.builder().name(branch).build());
			}
			var response = this.client.createEvent(builder.build());
			String eventId = (response.event() != null) ? response.event().eventId() : null;
			if (eventId != null) {
				message.getMetadata().put(EVENT_ID_METADATA_KEY, eventId);
			}
			logger.debug("Appended AgentCore event {} for sessionId {} (branch {})", eventId, sessionId, branch);
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
	 * no server-side transactional replace. When branch-swap is enabled
	 * ({@code agentcore.memory.session.branch-swap-enabled=true}) this method is
	 * non-destructive: it writes the replacement set to a fresh {@code gen-*} branch and
	 * switches the current-branch pointer (highest-generation-wins), leaving the prior
	 * timeline intact. When branch-swap is disabled (default) it performs the legacy
	 * non-atomic delete-then-recreate for a true v1 session, and refuses on a session
	 * that was already migrated to branch mode (to avoid destroying the ledger).
	 *
	 * <p>
	 * <strong>Concurrency.</strong> Branch-swap is highest-generation-wins, not a CAS: a
	 * whole replacement can be silently superseded by a concurrent higher-generation one,
	 * and a concurrent appendEvent can be orphaned. Hold an external per-session lock
	 * over appendEvent + both replaceEvents variants for strict single-winner semantics.
	 * See the class-level concurrency section.
	 * @param sessionId the session whose event log is being replaced
	 * @param events the new events to persist
	 */
	@Override
	public void replaceEvents(String sessionId, List<SessionEvent> events) {
		validateSessionId(sessionId);
		if (events == null) {
			throw new IllegalArgumentException("events must not be null");
		}
		logger.warn("AgentCore has no server-side transactional replace; replaceEvents writes a new branch and switches"
				+ " the current-branch pointer by highest generation (highest-gen-wins, non-destructive). Concurrent"
				+ " appendEvent can be silently orphaned; hold an external per-session lock over append+replace. See"
				+ " class Javadoc. sessionId {}", sessionId);
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
		logger.warn("AgentCore has no server-side compare-and-swap; performing check-then-act for sessionId {}. The"
				+ " risk under concurrency is silent supersession (highest-gen-wins) and an orphaned concurrent"
				+ " appendEvent, not partial or lost data; hold an external lock over append+replace for strict"
				+ " single-winner needs.", sessionId);
		this.doReplaceEvents(sessionId, events);
		return true;
	}

	@Override
	public long getEventVersion(String sessionId) {
		validateSessionId(sessionId);
		try {
			var actorAndSession = this.actorAndSession(sessionId);
			String branch = resolveCurrentBranch(actorAndSession);
			AtomicLong count = new AtomicLong();
			this.forEachEventPage(actorAndSession, false, false, branchFilter(branch),
					(page) -> page.forEach((event) -> {
						if (!isPointerMarker(event)) {
							count.incrementAndGet();
						}
					}));
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
			String branch = resolveCurrentBranch(actorAndSession);
			List<Event> allEvents = new ArrayList<>();
			this.forEachEventPage(actorAndSession, true, true, branchFilter(branch), allEvents::addAll);
			// AgentCore returns events in descending order (newest first); reverse to
			// chronological order.
			Collections.reverse(allEvents);

			List<SessionEvent> mapped = new ArrayList<>(allEvents.size());
			for (Event event : allEvents) {
				if (isPointerMarker(event)) {
					continue;
				}
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

	// ==================== replaceEvents internals ====================

	private void doReplaceEvents(String sessionId, List<SessionEvent> events) {
		var actorAndSession = this.actorAndSession(sessionId);
		if (!this.branchSwapEnabled) {
			// Step 0a: refuse on an already-migrated session; the legacy main-line delete
			// would destroy the ledger and orphan the live branch (N1).
			String branch = resolveCurrentBranch(actorAndSession);
			if (branch != null) {
				String msg = "replaceEvents with branch-swap disabled is refused on session " + sessionId
						+ ": it has a v2 branch timeline (current branch " + branch
						+ "). Re-enable agentcore.memory.session.branch-swap-enabled, or run the"
						+ " migrate-back utility (see README rollback) before disabling.";
				throw new AgentCoreMemoryException.StorageException(msg, null);
			}
			// Step 0b: true v1 session -> legacy delete-then-recreate.
			this.doReplaceEventsLegacy(sessionId, actorAndSession, events);
			return;
		}
		this.doReplaceEventsBranchSwap(sessionId, actorAndSession, events);
	}

	private void doReplaceEventsBranchSwap(String sessionId,
			AgentCoreMemoryConversationIdParser.ActorAndSession actorAndSession, List<SessionEvent> events) {
		AtomicInteger created = new AtomicInteger();
		long nextGen = resolveGeneration(actorAndSession) + 1;
		String branchName = String.format(BRANCH_NAME_FORMAT, nextGen, randomShortToken());
		int intended = 0;
		try {
			for (SessionEvent event : events) {
				Message message = event.getMessage();
				if (event.isSynthetic() && !this.persistSynthetic) {
					continue;
				}
				PayloadType payload = this.buildPayloadType(message);
				if (payload == null) {
					continue;
				}
				intended++;
				CreateEventRequest request = CreateEventRequest.builder()
					.memoryId(this.memoryId)
					.actorId(actorAndSession.actor())
					.sessionId(actorAndSession.session())
					.payload(List.of(payload))
					.eventTimestamp((event.getTimestamp() != null) ? event.getTimestamp() : Instant.now())
					.branch(Branch.builder().name(branchName).build())
					.clientToken(UUID.randomUUID().toString())
					.build();
				var response = this.client.createEvent(request);
				String eventId = (response.event() != null) ? response.event().eventId() : null;
				if (eventId != null) {
					message.getMetadata().put(EVENT_ID_METADATA_KEY, eventId);
				}
				created.incrementAndGet();
			}
			// Step 5: make the new branch durable and current BEFORE compaction, so a
			// crash never removes the only marker.
			writeCurrentBranchPointer(actorAndSession, branchName, nextGen);
		}
		catch (SdkException ex) {
			// No data loss: the pointer was not written, so the old branch stays current.
			logger.warn("replaceEvents branch write failed for sessionId {}: created {} of {} on branch {}, pointer not"
					+ " written, the old timeline is still current. Orphaned partial branch is reaped by memory TTL.",
					sessionId, created.get(), intended, branchName, ex);
			throw new AgentCoreMemoryException.StorageException("Failed to replace events for sessionId: " + sessionId,
					ex);
		}

		// Step 6: compaction couples marker removal to branch-event deletion (D1.1a).
		compactLedger(actorAndSession, nextGen);
		// Step 7: optional explicit prior-branch cleanup (redundant when compaction ran).
		if (this.deleteSupersededBranch) {
			deleteSupersededBranches(actorAndSession, nextGen);
		}
		// Step 8: invalidate this instance's cached resolution.
		if (this.branchCache != null) {
			this.branchCache.invalidate(actorAndSession);
		}
		logger.info("Replaced session {} timeline onto branch {} (gen {}, {} events); prior timeline retained.",
				sessionId, branchName, nextGen, created.get());
	}

	/*
	 * Legacy non-atomic delete-then-recreate, kept for the flag-off path on true v1
	 * sessions. Only reachable when branch-swap is disabled and no pointer marker exists.
	 * Its "Data loss" ERROR log warns about the mid-flight failure window this path still
	 * has.
	 */
	private void doReplaceEventsLegacy(String sessionId,
			AgentCoreMemoryConversationIdParser.ActorAndSession actorAndSession, List<SessionEvent> events) {
		AtomicInteger deleted = new AtomicInteger();
		AtomicInteger recreated = new AtomicInteger();
		boolean deletePhaseComplete = false;
		try {
			// 1. Delete every existing event, paginated.
			this.forEachEventPage(actorAndSession, false, false, null, (page) -> page.forEach((existing) -> {
				deleteEvent(actorAndSession, existing.eventId());
				deleted.incrementAndGet();
			}));
			deletePhaseComplete = true;

			// 2. Recreate each new event. This is a full replacement, so we do not filter
			// by agentcore.eventId metadata.
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
			if (deletePhaseComplete) {
				logger.error("Data loss replacing AgentCore events for sessionId {}: delete completed ({}"
						+ " deleted) but recreate failed after {} of {} events. The original log is gone and the"
						+ " current log is partial. Legacy replaceEvents is non-atomic and cannot be retried here;"
						+ " recover from an external backup, or enable"
						+ " agentcore.memory.session.branch-swap-enabled for the non-destructive path. See"
						+ " AgentCoreSessionRepository Javadoc.", sessionId, deleted.get(), recreated.get(),
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

	// ==================== branch discovery / ledger ====================

	/**
	 * Resolves the current read-target branch name, or {@code null} for a main-line/v1
	 * session. Uses the per-instance cache when enabled, otherwise a ledger scan.
	 * @param actorAndSession the parsed actor and session
	 * @return the current branch name, or {@code null} for a main-line/v1 session
	 */
	String resolveCurrentBranch(AgentCoreMemoryConversationIdParser.ActorAndSession actorAndSession) {
		if (this.branchCache != null) {
			BranchResolutionCache.Hit hit = this.branchCache.get(actorAndSession);
			if (hit != null) {
				return hit.branchName();
			}
		}
		String branch = resolveFromLedger(actorAndSession);
		if (this.branchCache != null) {
			this.branchCache.put(actorAndSession, branch);
		}
		return branch;
	}

	private String resolveFromLedger(AgentCoreMemoryConversationIdParser.ActorAndSession actorAndSession) {
		PointerMarker max = maxMarker(readLedger(actorAndSession));
		return (max != null) ? max.branchName() : null;
	}

	/**
	 * Returns the highest generation for the session, or {@code -1} for a main-line/v1
	 * session (no pointer markers).
	 * @param actorAndSession the parsed actor and session
	 * @return the highest generation, or {@code -1} for a main-line/v1 session
	 */
	long resolveGeneration(AgentCoreMemoryConversationIdParser.ActorAndSession actorAndSession) {
		PointerMarker max = maxMarker(readLedger(actorAndSession));
		return (max != null) ? max.gen() : -1L;
	}

	/**
	 * Reads every pointer marker on the main line (metadata EXISTS
	 * {@value #POINTER_MARKER_METADATA_KEY}). Discovery does not rely on ListEvents
	 * ordering; the caller selects the winner by highest generation.
	 * @param actorAndSession the parsed actor and session
	 * @return every pointer marker recorded on the main line, in no guaranteed order
	 */
	List<PointerMarker> readLedger(AgentCoreMemoryConversationIdParser.ActorAndSession actorAndSession) {
		List<PointerMarker> markers = new ArrayList<>();
		FilterInput filter = FilterInput.builder()
			.eventMetadata(EventMetadataFilterExpression.builder()
				.left(LeftExpression.fromMetadataKey(POINTER_MARKER_METADATA_KEY))
				.operator(OperatorType.EXISTS)
				.build())
			.build();
		this.forEachEventPage(actorAndSession, false, false, filter, (page) -> {
			for (Event event : page) {
				PointerMarker marker = toMarker(event);
				if (marker != null) {
					markers.add(marker);
				}
			}
		});
		return markers;
	}

	private static PointerMarker maxMarker(List<PointerMarker> markers) {
		PointerMarker best = null;
		for (PointerMarker marker : markers) {
			if (best == null || marker.gen() > best.gen()
					|| (marker.gen() == best.gen() && marker.branchName().compareTo(best.branchName()) > 0)) {
				best = marker;
			}
		}
		return best;
	}

	private static PointerMarker toMarker(Event event) {
		if (event == null || !event.hasMetadata()) {
			return null;
		}
		Map<String, MetadataValue> metadata = event.metadata();
		MetadataValue pointer = metadata.get(POINTER_MARKER_METADATA_KEY);
		if (pointer == null || !"true".equals(pointer.stringValue())) {
			return null;
		}
		MetadataValue branch = metadata.get(CURRENT_BRANCH_METADATA_KEY);
		MetadataValue gen = metadata.get(GENERATION_METADATA_KEY);
		if (branch == null || branch.stringValue() == null || gen == null || gen.stringValue() == null) {
			return null;
		}
		try {
			long parsedGen = Long.parseLong(gen.stringValue().trim());
			return new PointerMarker(parsedGen, branch.stringValue(), event.eventId());
		}
		catch (NumberFormatException ex) {
			logger.debug("Skipping pointer marker {} with unparseable generation '{}'", event.eventId(),
					gen.stringValue());
			return null;
		}
	}

	/**
	 * Writes the durable current-branch pointer marker on the main line. The event is
	 * identified as a pointer by its {@value #POINTER_MARKER_METADATA_KEY} metadata,
	 * independent of payload shape, so all counting and mapping paths exclude it.
	 * @param actorAndSession the parsed actor and session
	 * @param branchName the branch this marker names as current
	 * @param generation the generation counter for this marker
	 */
	void writeCurrentBranchPointer(AgentCoreMemoryConversationIdParser.ActorAndSession actorAndSession,
			String branchName, long generation) {
		Map<String, MetadataValue> metadata = new HashMap<>();
		metadata.put(CURRENT_BRANCH_METADATA_KEY, MetadataValue.fromStringValue(branchName));
		metadata.put(GENERATION_METADATA_KEY, MetadataValue.fromStringValue(String.format("%05d", generation)));
		metadata.put(POINTER_MARKER_METADATA_KEY, MetadataValue.fromStringValue("true"));
		// Primary: empty payload (min-0 payload is allowed). If the live service rejects
		// it, the IT-only fallback is a single blob payload
		// PayloadType.builder().blob(Document.fromString("agentcore-pointer")).build();
		// switching shapes changes nothing downstream because the pointer is keyed on
		// metadata, not payload.
		CreateEventRequest request = CreateEventRequest.builder()
			.memoryId(this.memoryId)
			.actorId(actorAndSession.actor())
			.sessionId(actorAndSession.session())
			.payload(List.of())
			.metadata(metadata)
			.eventTimestamp(Instant.now())
			.clientToken(UUID.randomUUID().toString())
			.build();
		this.client.createEvent(request);
		logger.debug("Wrote current-branch pointer for actor {} session {}: branch {} gen {}", actorAndSession.actor(),
				actorAndSession.session(), branchName, generation);
	}

	// Compacts the ledger after a successful swap: for each marker with gen < newMaxGen,
	// best-effort delete that generation's branch events first, then its marker. If a
	// branch-event delete fails, KEEP its marker so delete() can still reach it. Best
	// effort throughout; never fails the swap.
	private void compactLedger(AgentCoreMemoryConversationIdParser.ActorAndSession actorAndSession, long newMaxGen) {
		List<PointerMarker> ledger;
		try {
			ledger = readLedger(actorAndSession);
		}
		catch (SdkException ex) {
			logger.debug("Ledger compaction skipped for actor {} session {}: could not read ledger",
					actorAndSession.actor(), actorAndSession.session(), ex);
			return;
		}
		for (PointerMarker marker : ledger) {
			if (marker.gen() >= newMaxGen) {
				continue;
			}
			boolean branchPurged = deleteBranchEvents(actorAndSession, marker.branchName());
			if (!branchPurged) {
				logger.debug("Keeping marker for gen {} (branch {}): branch-event deletion failed, so the ledger still"
						+ " records it for a future delete()/retry.", marker.gen(), marker.branchName());
				continue;
			}
			try {
				deleteEvent(actorAndSession, marker.eventId());
			}
			catch (SdkException ex) {
				logger.debug("Best-effort compaction: failed to delete pointer marker {} for gen {}", marker.eventId(),
						marker.gen(), ex);
			}
		}
	}

	private void deleteSupersededBranches(AgentCoreMemoryConversationIdParser.ActorAndSession actorAndSession,
			long newMaxGen) {
		List<PointerMarker> ledger;
		try {
			ledger = readLedger(actorAndSession);
		}
		catch (SdkException ex) {
			logger.warn("delete-superseded-branch skipped for actor {} session {}: could not read ledger",
					actorAndSession.actor(), actorAndSession.session(), ex);
			return;
		}
		for (PointerMarker marker : ledger) {
			if (marker.gen() < newMaxGen && !deleteBranchEvents(actorAndSession, marker.branchName())) {
				logger.warn("delete-superseded-branch: failed to fully delete branch {} (gen {}); it is reaped by TTL.",
						marker.branchName(), marker.gen());
			}
		}
	}

	// Best-effort delete of every event on a branch. Returns true on full success, false
	// if any delete failed.
	private boolean deleteBranchEvents(AgentCoreMemoryConversationIdParser.ActorAndSession actorAndSession,
			String branchName) {
		try {
			this.forEachEventPage(actorAndSession, false, false, branchFilter(branchName),
					(page) -> page.forEach((event) -> deleteEvent(actorAndSession, event.eventId())));
			return true;
		}
		catch (SdkException ex) {
			logger.debug("Best-effort branch-event deletion failed for branch {}", branchName, ex);
			return false;
		}
	}

	private void deleteEvent(AgentCoreMemoryConversationIdParser.ActorAndSession actorAndSession, String eventId) {
		this.client.deleteEvent(DeleteEventRequest.builder()
			.memoryId(this.memoryId)
			.actorId(actorAndSession.actor())
			.sessionId(actorAndSession.session())
			.eventId(eventId)
			.build());
	}

	// ==================== createdAt / findByUserId helpers ====================

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

	// ==================== pagination ====================

	private void forEachEventPage(AgentCoreMemoryConversationIdParser.ActorAndSession actorAndSession,
			boolean includePayloads, boolean respectLimit, FilterInput filter, Consumer<List<Event>> handler) {
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
			handler.accept(page);
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
		return FilterInput.builder()
			.branch(BranchFilter.builder().name(branchName).includeParentBranches(false).build())
			.build();
	}

	private static boolean isPointerMarker(Event event) {
		if (event == null || !event.hasMetadata()) {
			return false;
		}
		MetadataValue pointer = event.metadata().get(POINTER_MARKER_METADATA_KEY);
		return pointer != null && "true".equals(pointer.stringValue());
	}

	private static String randomShortToken() {
		return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
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

	/**
	 * A pointer marker recorded on the main line: a generation, the branch it names, and
	 * the marker event's own id (for compaction/delete).
	 *
	 * @param gen the parsed generation counter
	 * @param branchName the current-branch name the marker points to
	 * @param eventId the marker event's id
	 */
	record PointerMarker(long gen, String branchName, String eventId) {
	}

	/**
	 * Bounded per-instance cache of resolved branch names, keyed by (actor, session). A
	 * latency optimization only: it is per-JVM, so a replace on another instance is not
	 * seen until eviction/TTL. Compaction, not the cache, is the correctness bound.
	 */
	private static final class BranchResolutionCache {

		private static final int MAX_ENTRIES = 1024;

		private final Duration ttl;

		private final LinkedHashMap<String, Entry> entries;

		BranchResolutionCache(Duration ttl) {
			this.ttl = ttl;
			this.entries = new LinkedHashMap<>(16, 0.75f, true) {
				@Override
				protected boolean removeEldestEntry(Map.Entry<String, BranchResolutionCache.Entry> eldest) {
					return size() > MAX_ENTRIES;
				}
			};
		}

		synchronized Hit get(AgentCoreMemoryConversationIdParser.ActorAndSession as) {
			Entry entry = this.entries.get(key(as));
			if (entry == null) {
				return null;
			}
			if (this.ttl != null && Instant.now().isAfter(entry.expiresAt)) {
				this.entries.remove(key(as));
				return null;
			}
			return new Hit(entry.branchName);
		}

		synchronized void put(AgentCoreMemoryConversationIdParser.ActorAndSession as, String branchName) {
			Instant expiresAt = (this.ttl != null) ? Instant.now().plus(this.ttl) : Instant.MAX;
			this.entries.put(key(as), new Entry(branchName, expiresAt));
		}

		synchronized void invalidate(AgentCoreMemoryConversationIdParser.ActorAndSession as) {
			this.entries.remove(key(as));
		}

		private static String key(AgentCoreMemoryConversationIdParser.ActorAndSession as) {
			return as.actor() + " " + as.session();
		}

		private record Entry(String branchName, Instant expiresAt) {
		}

		record Hit(String branchName) {
		}

	}

}
