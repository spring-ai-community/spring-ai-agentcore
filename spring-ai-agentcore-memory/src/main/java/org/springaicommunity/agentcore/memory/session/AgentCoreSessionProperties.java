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

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for the Spring AI Session API bean stack.
 *
 * <p>
 * <strong>User id convention.</strong> The AgentCore-backed
 * {@link AgentCoreSessionRepository} derives {@code Session.userId} from the actor
 * segment of the sessionId (parsed by
 * {@link org.springaicommunity.agentcore.memory.AgentCoreMemoryConversationIdParser}).
 * Callers using {@code SessionMemoryAdvisor.USER_ID_CONTEXT_KEY} must use the sessionId
 * format {@code "userId:sessionSuffix"} and pass that same user id under
 * {@code USER_ID_CONTEXT_KEY}. A mismatch triggers an {@link IllegalStateException} from
 * the advisor on the second turn.
 *
 * <p>
 * <strong>Session-scoped tunables.</strong> {@code totalEventsLimit}, {@code pageSize},
 * {@code ignoreUnknownRoles}, and {@code defaultSession} let a session-only adopter
 * configure the repository without touching {@code agentcore.memory.short-term.*}. When a
 * session value is {@code null} the auto-config falls back to the short-term namespace,
 * then the legacy {@code agentcore.memory.*} namespace, then a hard default (see
 * {@code AgentCoreSessionRepositoryAutoConfiguration}). This is purely additive: existing
 * short-term or legacy configs keep working with the same deprecation warnings.
 *
 * <p>
 * <strong>Branch-swap replaceEvents.</strong> {@code branchSwapEnabled} defaults to
 * {@code false}. When enabled, {@code replaceEvents} writes the replacement timeline to a
 * fresh branch and switches the current-branch pointer (non-destructive, highest
 * generation wins) instead of the legacy delete-then-recreate. The migration is one-way
 * per session, so prove it out in a non-production environment before enabling it. See
 * the class Javadoc on {@code AgentCoreSessionRepository} and the module README rollback
 * section. {@code deleteSupersededBranch} (default {@code false}) opts into best-effort
 * deletion of a superseded branch after a swap. The optional {@code branchCacheEnabled}
 * ({@code false}) plus {@code branchCacheTtl} tune a per-instance branch-resolution
 * cache; it is a latency optimization, not a correctness mechanism, and is per-JVM only.
 *
 * <p>
 * <strong>Storage-growth note.</strong> With branch-swap enabled, ledger compaction reaps
 * each superseded generation's branch events as its marker is compacted, so the steady
 * state is roughly one live branch. If both compaction branch-deletion and
 * {@code deleteSupersededBranch} keep failing under sustained AWS errors, superseded
 * branches persist until the memory-level {@code eventExpiryDuration} reaps them; lower
 * that duration as a backstop for high replace rates.
 *
 * @param enabled opt-in switch for the session-backed memory stack; defaults to
 * {@code false}
 * @param defaultUserId optional override for the {@code SessionMemoryAdvisor} built-in
 * default user id
 * @param persistSynthetic when {@code true}, synthetic session events (framework
 * generated, e.g. compaction summaries) are persisted to AgentCore; defaults to
 * {@code false}
 * @param totalEventsLimit session-scoped override for the maximum events to retrieve;
 * {@code null} falls back to short-term/legacy/default
 * @param pageSize session-scoped override for the AgentCore listEvents page size;
 * {@code null} falls back to short-term/legacy/default
 * @param ignoreUnknownRoles session-scoped override for skipping non-dialogue messages;
 * {@code null} falls back to short-term/legacy/default
 * @param defaultSession session-scoped override for the default session name;
 * {@code null} falls back to short-term/legacy/default
 * @param branchSwapEnabled when {@code true}, {@code replaceEvents} uses non-destructive
 * branch-swap; defaults to {@code false} (opt-in rollout)
 * @param deleteSupersededBranch when {@code true}, best-effort delete the prior branch
 * after a successful swap; defaults to {@code false}
 * @param branchCacheEnabled when {@code true}, cache the resolved current branch per
 * (actor, session) in a bounded per-instance map; defaults to {@code false}
 * @param branchCacheTtl time-to-live for cached branch resolutions; {@code null} leaves
 * entries until eviction
 * @author Spring AI Community
 */
@ConfigurationProperties(AgentCoreSessionProperties.CONFIG_PREFIX)
public record AgentCoreSessionProperties(Boolean enabled, String defaultUserId, Boolean persistSynthetic,
		Integer totalEventsLimit, Integer pageSize, Boolean ignoreUnknownRoles, String defaultSession,
		Boolean branchSwapEnabled, Boolean deleteSupersededBranch, Boolean branchCacheEnabled,
		Duration branchCacheTtl) {

	/**
	 * Configuration prefix for the session-backed memory stack.
	 */
	public static final String CONFIG_PREFIX = "agentcore.memory.session";

}
