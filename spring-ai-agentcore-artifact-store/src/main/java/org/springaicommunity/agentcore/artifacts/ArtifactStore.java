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

package org.springaicommunity.agentcore.artifacts;

import java.util.List;

/**
 * Session-scoped storage for tool-generated artifacts.
 * <p>
 * Stores artifacts (files, screenshots, etc.) keyed by session ID and optional category.
 * Supports concurrent access in multi-user environments. Artifacts are retrieved once and
 * cleared (one-time consumption pattern).
 * <p>
 * <b>Category Support:</b> Methods without category parameter use
 * {@link #DEFAULT_CATEGORY}. Use categories to isolate artifacts from different tools
 * (e.g., "browser", "codeinterpreter") within a shared store.
 * <p>
 * <b>Thread Safety:</b> Implementations must be thread-safe. Multiple threads may
 * concurrently store and retrieve artifacts for different sessions. Operations on the
 * same session ID must be atomic.
 *
 * @param <T> the type of artifact to store
 * @author Yuriy Bezsonov
 */
public interface ArtifactStore<T> {

	/** Default category used when category is not specified. */
	String DEFAULT_CATEGORY = "default";

	// ========== Category-aware methods (primary API) ==========

	/**
	 * Store a single artifact for a session and category.
	 * @param sessionId the session ID (use {@link SessionConstants#DEFAULT_SESSION_ID}
	 * for single-user)
	 * @param category the category (e.g., "browser", "codeinterpreter")
	 * @param artifact the artifact to store
	 */
	void store(String sessionId, String category, T artifact);

	/**
	 * Store multiple artifacts for a session and category.
	 * @param sessionId the session ID
	 * @param category the category
	 * @param artifacts list of artifacts to store
	 */
	void storeAll(String sessionId, String category, List<T> artifacts);

	/**
	 * Retrieve and clear stored artifacts for a session and category (destructive read).
	 * @param sessionId the session ID
	 * @param category the category
	 * @return list of artifacts, or null if none stored
	 */
	List<T> retrieve(String sessionId, String category);

	/**
	 * Peek at stored artifacts without removing them (non-destructive read).
	 * @param sessionId the session ID
	 * @param category the category
	 * @return unmodifiable list of artifacts, or null if none stored
	 */
	List<T> peek(String sessionId, String category);

	/**
	 * Check if artifacts are stored for a session and category.
	 * @param sessionId the session ID
	 * @param category the category
	 * @return true if artifacts are available
	 */
	boolean hasArtifacts(String sessionId, String category);

	/**
	 * Get the count of artifacts stored for a session and category.
	 * @param sessionId the session ID
	 * @param category the category
	 * @return number of artifacts, or 0 if none stored
	 */
	int count(String sessionId, String category);

	/**
	 * Clear stored artifacts for a session and category without returning them.
	 * @param sessionId the session ID
	 * @param category the category
	 */
	void clear(String sessionId, String category);

	// ========== Convenience methods using DEFAULT_CATEGORY ==========

	/**
	 * Store a single artifact for a session using default category.
	 * @param sessionId the session ID
	 * @param artifact the artifact to store
	 */
	default void store(String sessionId, T artifact) {
		store(sessionId, DEFAULT_CATEGORY, artifact);
	}

	/**
	 * Store multiple artifacts for a session using default category.
	 * @param sessionId the session ID
	 * @param artifacts list of artifacts to store
	 */
	default void storeAll(String sessionId, List<T> artifacts) {
		storeAll(sessionId, DEFAULT_CATEGORY, artifacts);
	}

	/**
	 * Retrieve and clear stored artifacts for a session using default category.
	 * @param sessionId the session ID
	 * @return list of artifacts, or null if none stored
	 */
	default List<T> retrieve(String sessionId) {
		return retrieve(sessionId, DEFAULT_CATEGORY);
	}

	/**
	 * Peek at stored artifacts using default category.
	 * @param sessionId the session ID
	 * @return unmodifiable list of artifacts, or null if none stored
	 */
	default List<T> peek(String sessionId) {
		return peek(sessionId, DEFAULT_CATEGORY);
	}

	/**
	 * Check if artifacts are stored using default category.
	 * @param sessionId the session ID
	 * @return true if artifacts are available
	 */
	default boolean hasArtifacts(String sessionId) {
		return hasArtifacts(sessionId, DEFAULT_CATEGORY);
	}

	/**
	 * Get the count of artifacts using default category.
	 * @param sessionId the session ID
	 * @return number of artifacts, or 0 if none stored
	 */
	default int count(String sessionId) {
		return count(sessionId, DEFAULT_CATEGORY);
	}

	/**
	 * Clear stored artifacts using default category.
	 * @param sessionId the session ID
	 */
	default void clear(String sessionId) {
		clear(sessionId, DEFAULT_CATEGORY);
	}

}
