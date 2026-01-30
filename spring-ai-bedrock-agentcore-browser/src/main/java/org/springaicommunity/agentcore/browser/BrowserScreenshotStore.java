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

package org.springaicommunity.agentcore.browser;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Storage for browser screenshots. Uses session ID as key to support concurrent requests
 * in multi-user environments (EKS/ECS).
 * <p>
 * For single-user environments (AgentCore), use DEFAULT_SESSION_ID.
 * <p>
 * Uses Caffeine cache with TTL to automatically evict orphaned entries.
 *
 * @author Yuriy Bezsonov
 */
public class BrowserScreenshotStore {

	private static final Logger logger = LoggerFactory.getLogger(BrowserScreenshotStore.class);

	/** Key used in ToolContext to pass session ID to tools. */
	public static final String SESSION_ID_KEY = "browser_session_id";

	/** Default session ID for single-user environments (AgentCore). */
	public static final String DEFAULT_SESSION_ID = "default";

	private final Cache<String, List<BrowserScreenshot>> screenshotsBySession;

	/**
	 * Create screenshot store with specified TTL.
	 * @param ttlSeconds time-to-live in seconds for cached entries
	 */
	public BrowserScreenshotStore(int ttlSeconds) {
		this.screenshotsBySession = Caffeine.newBuilder()
			.expireAfterWrite(Duration.ofSeconds(ttlSeconds))
			.maximumSize(100)
			.build();
		logger.debug("BrowserScreenshotStore initialized: ttl={}s", ttlSeconds);
	}

	/**
	 * Store a screenshot for a session.
	 * @param sessionId the session ID (use DEFAULT_SESSION_ID for single-user)
	 * @param screenshot the screenshot to store
	 */
	public void store(String sessionId, BrowserScreenshot screenshot) {
		if (screenshot == null) {
			return;
		}
		String key = normalizeSessionId(sessionId);
		List<BrowserScreenshot> list = this.screenshotsBySession.asMap()
			.computeIfAbsent(key, k -> new CopyOnWriteArrayList<>());
		list.add(screenshot);
		logger.debug("Stored screenshot for session {}, total now: {}", key, list.size());
	}

	/**
	 * Normalize session ID to handle null values.
	 * @param sessionId the session ID
	 * @return normalized session ID
	 */
	private String normalizeSessionId(String sessionId) {
		return sessionId != null ? sessionId : DEFAULT_SESSION_ID;
	}

	/**
	 * Retrieve and clear stored screenshots for a session.
	 * @param sessionId the session ID (use DEFAULT_SESSION_ID for single-user)
	 * @return list of screenshots, or null if none stored
	 */
	public List<BrowserScreenshot> retrieve(String sessionId) {
		String key = normalizeSessionId(sessionId);
		List<BrowserScreenshot> result = this.screenshotsBySession.asMap().remove(key);
		logger.debug("Retrieved {} screenshots for session {}", result != null ? result.size() : 0, key);
		return result;
	}

	/**
	 * Check if screenshots are stored for a session.
	 * @param sessionId the session ID
	 * @return true if screenshots are available
	 */
	public boolean hasScreenshots(String sessionId) {
		String key = normalizeSessionId(sessionId);
		List<BrowserScreenshot> stored = this.screenshotsBySession.getIfPresent(key);
		return stored != null && !stored.isEmpty();
	}

}
