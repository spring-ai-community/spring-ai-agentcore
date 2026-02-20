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

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Caffeine-backed implementation of {@link ArtifactStore}.
 * <p>
 * Uses Caffeine cache with TTL to automatically evict orphaned entries from failed or
 * disconnected sessions. Keys are composite: "sessionId:category".
 *
 * @param <T> the type of artifact to store
 * @author Yuriy Bezsonov
 */
public class CaffeineArtifactStore<T> implements ArtifactStore<T> {

	private static final Logger logger = LoggerFactory.getLogger(CaffeineArtifactStore.class);

	/**
	 * Default maximum number of sessions to track. This is a safety limit to prevent
	 * unbounded memory growth in pathological cases. Normal cleanup is handled by TTL.
	 */
	public static final int DEFAULT_MAX_SIZE = 10_000;

	/**
	 * Default store name used when not specified.
	 */
	public static final String DEFAULT_STORE_NAME = "ArtifactStore";

	private final Cache<String, List<T>> cache;

	private final String storeName;

	/**
	 * Create artifact store with specified TTL, default max size, and default name.
	 * @param ttlSeconds time-to-live in seconds for cached entries
	 */
	public CaffeineArtifactStore(int ttlSeconds) {
		this(ttlSeconds, DEFAULT_MAX_SIZE, DEFAULT_STORE_NAME);
	}

	/**
	 * Create artifact store with specified TTL and default max size.
	 * @param ttlSeconds time-to-live in seconds for cached entries
	 * @param storeName name for logging purposes
	 */
	public CaffeineArtifactStore(int ttlSeconds, String storeName) {
		this(ttlSeconds, DEFAULT_MAX_SIZE, storeName);
	}

	/**
	 * Create artifact store with specified TTL and max size.
	 * @param ttlSeconds time-to-live in seconds for cached entries
	 * @param maxSize maximum number of sessions to track (safety limit)
	 * @param storeName name for logging purposes
	 */
	public CaffeineArtifactStore(int ttlSeconds, int maxSize, String storeName) {
		this.storeName = storeName;
		this.cache = Caffeine.newBuilder()
			.expireAfterWrite(Duration.ofSeconds(ttlSeconds))
			.maximumSize(maxSize)
			.build();
		logger.debug("{} initialized: ttl={}s, maxSize={}", storeName, ttlSeconds, maxSize);
	}

	@Override
	public void store(String sessionId, String category, T artifact) {
		if (artifact == null) {
			return;
		}
		String key = buildKey(sessionId, category);
		int[] totalSize = { 0 };
		this.cache.asMap().compute(key, (k, existing) -> {
			if (existing == null) {
				existing = new ArrayList<>();
			}
			existing.add(artifact);
			totalSize[0] = existing.size();
			return existing;
		});
		logger.debug("{}: stored 1 artifact for key {}, total now: {}", this.storeName, key, totalSize[0]);
	}

	@Override
	public void storeAll(String sessionId, String category, List<T> artifacts) {
		if (artifacts == null || artifacts.isEmpty()) {
			return;
		}
		String key = buildKey(sessionId, category);
		int[] totalSize = { 0 };
		this.cache.asMap().compute(key, (k, existing) -> {
			if (existing == null) {
				existing = new ArrayList<>();
			}
			existing.addAll(artifacts);
			totalSize[0] = existing.size();
			return existing;
		});
		logger.debug("{}: stored {} artifacts for key {}, total now: {}", this.storeName, artifacts.size(), key,
				totalSize[0]);
	}

	@Override
	public List<T> retrieve(String sessionId, String category) {
		String key = buildKey(sessionId, category);
		List<T> result = this.cache.asMap().remove(key);
		logger.debug("{}: retrieved {} artifacts for key {}", this.storeName, result != null ? result.size() : 0, key);
		return result;
	}

	@Override
	public boolean hasArtifacts(String sessionId, String category) {
		String key = buildKey(sessionId, category);
		List<T> stored = this.cache.getIfPresent(key);
		return stored != null && !stored.isEmpty();
	}

	@Override
	public int count(String sessionId, String category) {
		String key = buildKey(sessionId, category);
		List<T> stored = this.cache.getIfPresent(key);
		return stored != null ? stored.size() : 0;
	}

	@Override
	public List<T> peek(String sessionId, String category) {
		String key = buildKey(sessionId, category);
		List<T> stored = this.cache.getIfPresent(key);
		if (stored == null) {
			return null;
		}
		logger.debug("{}: peeked {} artifacts for key {}", this.storeName, stored.size(), key);
		return List.copyOf(stored);
	}

	@Override
	public void clear(String sessionId, String category) {
		String key = buildKey(sessionId, category);
		this.cache.asMap().remove(key);
		logger.debug("{}: cleared artifacts for key {}", this.storeName, key);
	}

	private String buildKey(String sessionId, String category) {
		String normalizedSession = (sessionId != null && !sessionId.isBlank()) ? sessionId
				: SessionConstants.DEFAULT_SESSION_ID;
		String normalizedCategory = (category != null && !category.isBlank()) ? category : DEFAULT_CATEGORY;
		return normalizedSession + ":" + normalizedCategory;
	}

}
