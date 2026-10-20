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

package org.springaicommunity.agentcore.memory;

/**
 * Parses conversationId into actor and session components. Supports two formats:
 * <ul>
 * <li>{@code "actorId"} → actor: actorId, session: default</li>
 * <li>{@code "actorId:sessionId"} → actor: actorId, session: sessionId</li>
 * </ul>
 *
 * <p>
 * <strong>Separator.</strong> The conversationId is split at its <em>last</em> colon.
 * AgentCore allows colons inside an actor id (for example a tenant prefix or a URN-style
 * subject) but never inside a session id, so only the last colon can separate the two:
 * {@code "tenant:alice:s1"} parses to actor {@code "tenant:alice"} and session
 * {@code "s1"}. An actor id that contains a colon must therefore always be passed in the
 * {@code "actorId:sessionId"} form, because the actor-only form {@code "tenant:alice"}
 * parses to actor {@code "tenant"} and session {@code "alice"}. Build the compound form
 * with {@link #of(String, String)}. Both segments are trimmed, as the session repository
 * and {@link #of(String, String)} trim them, so every component derives the same actor
 * and session from a padded id. {@link #parse} does not check the characters AgentCore
 * accepts in actor and session ids; AgentCore rejects invalid ids when the request is
 * sent. {@link #of(String, String)} rejects blank parts and control characters up front.
 *
 * <p>
 * <strong>Ownership rule.</strong> The {@code actorId} segment is the user id. When this
 * conversationId doubles as a Session API sessionId (via
 * {@code SessionMemoryAdvisor.SESSION_ID_CONTEXT_KEY}),
 * {@code AgentCoreSessionRepository} derives {@code Session.userId} from the
 * {@code actorId} segment, because AgentCore has no session-metadata store to persist a
 * user id separately. So any caller that also sets
 * {@code SessionMemoryAdvisor.USER_ID_CONTEXT_KEY} must use the
 * {@code "actorId:sessionId"} form and pass the same value as the {@code actorId}
 * segment. If the two disagree, {@code SessionMemoryAdvisor.before()} throws
 * {@link IllegalStateException} ("...does not belong to user...") on the second turn,
 * when the ownership check runs against the derived {@code Session.userId}. A caller that
 * never sets {@code USER_ID_CONTEXT_KEY} is unaffected.
 *
 * @author Yuriy Bezsonov
 */
public final class AgentCoreMemoryConversationIdParser {

	/**
	 * Default session ID used when conversationId doesn't include a session.
	 */
	public static final String DEFAULT_SESSION = "default-session";

	/**
	 * Error message for an actor id that {@link #hasBlankPart(String)} rejects.
	 */
	public static final String BLANK_ACTOR_PART_MESSAGE = "actor id must not start or end with ':' or contain an "
			+ "empty part between colons";

	private AgentCoreMemoryConversationIdParser() {
	}

	/**
	 * Parse conversationId using the default session fallback.
	 * @param conversationId the conversation ID (format: "actorId" or
	 * "actorId:sessionId", split at the last colon)
	 * @return parsed actor and session
	 * @throws IllegalArgumentException if conversationId is null or empty
	 */
	public static ActorAndSession parse(String conversationId) {
		return parse(conversationId, DEFAULT_SESSION);
	}

	/**
	 * Parse conversationId with a custom session fallback.
	 * @param conversationId the conversation ID (format: "actorId" or
	 * "actorId:sessionId", split at the last colon)
	 * @param defaultSession the session to use when not specified in conversationId
	 * @return parsed actor and session
	 * @throws IllegalArgumentException if conversationId is null or empty
	 */
	public static ActorAndSession parse(String conversationId, String defaultSession) {
		if (conversationId == null || conversationId.isEmpty()) {
			throw new IllegalArgumentException("conversationId is required (format: 'actorId' or 'actorId:sessionId')");
		}

		// Session ids never contain ':', so the last colon is the separator.
		int separator = conversationId.lastIndexOf(':');
		if (separator >= 0) {
			return new ActorAndSession(conversationId.substring(0, separator).trim(),
					conversationId.substring(separator + 1).trim());
		}
		return new ActorAndSession(conversationId.trim(), (defaultSession != null) ? defaultSession : DEFAULT_SESSION);
	}

	/**
	 * Compose a compound conversationId from a user id and a session suffix.
	 *
	 * <p>
	 * Use this instead of hand-concatenating {@code userId + ":" + sessionSuffix},
	 * especially when the session suffix comes from the client: a colon in a concatenated
	 * suffix would move the split point into the user id. Both segments are trimmed and
	 * must be non-blank. The user id may contain colons, because {@link #parse} splits at
	 * the last colon, but no part between its colons may be blank. The session suffix
	 * must not contain a colon; AgentCore session ids cannot contain one anyway. Neither
	 * segment may contain control characters. The result round-trips through
	 * {@link #parse} and is always accepted by the session repository seam, which applies
	 * the same trim and the same checks.
	 * @param userId the user id (actor segment); must not be blank, may contain colons
	 * @param sessionSuffix the session suffix; must not be blank or contain a colon
	 * @return {@code trimmedUserId + ":" + trimmedSessionSuffix}
	 * @throws IllegalArgumentException if either segment is null or blank after trimming,
	 * if the session suffix contains a colon, if a part of the user id between colons is
	 * blank, or if either segment contains a control character
	 */
	public static String of(String userId, String sessionSuffix) {
		if (userId == null || userId.trim().isEmpty()) {
			throw new IllegalArgumentException("userId must not be null or blank");
		}
		if (sessionSuffix == null || sessionSuffix.trim().isEmpty()) {
			throw new IllegalArgumentException("sessionSuffix must not be null or blank");
		}
		if (sessionSuffix.indexOf(':') >= 0) {
			throw new IllegalArgumentException("sessionSuffix must not contain ':' because AgentCore session ids"
					+ " cannot contain it; the last ':' separates the user id from the session suffix");
		}
		String actor = userId.trim();
		String session = sessionSuffix.trim();
		if (hasBlankPart(actor)) {
			throw new IllegalArgumentException(BLANK_ACTOR_PART_MESSAGE);
		}
		rejectControlCharacters("userId", actor);
		rejectControlCharacters("sessionSuffix", session);
		return actor + ":" + session;
	}

	/**
	 * Whether the actor id is empty or has a blank part between colons, as in
	 * {@code ":tenant"}, {@code "tenant:"} or {@code "a::b"}. AgentCore never accepts
	 * such an actor id. Shared by {@link #of(String, String)}, the short-term memory
	 * repository and the session repository, which live in other packages.
	 * @param actor the actor id, already trimmed
	 * @return {@code true} if the actor id is empty or any part between colons is blank
	 */
	public static boolean hasBlankPart(String actor) {
		for (String part : actor.split(":", -1)) {
			if (part.isBlank()) {
				return true;
			}
		}
		return false;
	}

	// The session repository rejects control characters too, so of() never builds an id
	// that the repository refuses.
	private static void rejectControlCharacters(String name, String value) {
		for (int i = 0; i < value.length(); i++) {
			char c = value.charAt(i);
			if (c < 0x20 || c == 0x7F) {
				throw new IllegalArgumentException("%s must not contain control characters (found U+%04X at index %d)"
					.formatted(name, (int) c, i));
			}
		}
	}

	/**
	 * Represents parsed actor and session from a conversationId.
	 *
	 * @param actor parsed actor identifier
	 * @param session parsed session identifier
	 */
	public record ActorAndSession(String actor, String session) {
	}

}
