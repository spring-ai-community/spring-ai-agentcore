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

/**
 * Factory for creating {@link ArtifactStore} instances.
 * <p>
 * Provides a clean abstraction over artifact store creation, hiding implementation
 * details from consuming modules (browser, code interpreter, etc.).
 *
 * @author Yuriy Bezsonov
 */
public interface ArtifactStoreFactory {

	/** Default TTL in seconds for artifact store entries. */
	int DEFAULT_TTL_SECONDS = 300;

	/** Default maximum number of sessions to track. */
	int DEFAULT_MAX_SIZE = 10_000;

	/**
	 * Create an artifact store for {@link GeneratedFile} artifacts with default settings.
	 * @param storeName name for logging purposes
	 * @return configured artifact store
	 */
	default ArtifactStore<GeneratedFile> create(String storeName) {
		return create(storeName, DEFAULT_TTL_SECONDS, DEFAULT_MAX_SIZE);
	}

	/**
	 * Create an artifact store for {@link GeneratedFile} artifacts with custom TTL.
	 * @param storeName name for logging purposes
	 * @param ttlSeconds time-to-live in seconds for cached entries
	 * @return configured artifact store
	 */
	default ArtifactStore<GeneratedFile> create(String storeName, int ttlSeconds) {
		return create(storeName, ttlSeconds, DEFAULT_MAX_SIZE);
	}

	/**
	 * Create an artifact store for {@link GeneratedFile} artifacts with full
	 * configuration.
	 * @param storeName name for logging purposes
	 * @param ttlSeconds time-to-live in seconds for cached entries
	 * @param maxSize maximum number of sessions to track
	 * @return configured artifact store
	 */
	ArtifactStore<GeneratedFile> create(String storeName, int ttlSeconds, int maxSize);

}
