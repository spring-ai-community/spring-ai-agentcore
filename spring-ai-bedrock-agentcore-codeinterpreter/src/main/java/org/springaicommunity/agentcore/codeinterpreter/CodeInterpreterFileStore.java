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

package org.springaicommunity.agentcore.codeinterpreter;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Storage for files generated during code execution. Uses session ID as key to support
 * concurrent requests in multi-user environments (EKS/ECS).
 * <p>
 * For single-user environments (AgentCore), use DEFAULT_SESSION_ID.
 * <p>
 * Uses Caffeine cache with TTL to automatically evict orphaned entries from failed or
 * disconnected sessions.
 *
 * @author Yuriy Bezsonov
 */
public class CodeInterpreterFileStore {

	private static final Logger logger = LoggerFactory.getLogger(CodeInterpreterFileStore.class);

	/** Default session ID for single-user environments (AgentCore). */
	public static final String DEFAULT_SESSION_ID = "default";

	private final Cache<String, List<GeneratedFile>> filesBySession;

	/**
	 * Create file store with specified TTL.
	 * @param ttlSeconds time-to-live in seconds for cached entries
	 */
	public CodeInterpreterFileStore(int ttlSeconds) {
		this.filesBySession = Caffeine.newBuilder()
			.expireAfterWrite(Duration.ofSeconds(ttlSeconds))
			.maximumSize(1000)
			.build();
		logger.debug("CodeInterpreterFileStore initialized: ttl={}s", ttlSeconds);
	}

	/**
	 * Store generated files for a session.
	 * @param sessionId the session ID (use DEFAULT_SESSION_ID for single-user)
	 * @param generatedFiles list of files to store
	 */
	public void store(String sessionId, List<GeneratedFile> generatedFiles) {
		if (generatedFiles == null || generatedFiles.isEmpty()) {
			return;
		}
		String key = sessionId != null ? sessionId : DEFAULT_SESSION_ID;
		int[] totalSize = { 0 };
		this.filesBySession.asMap().compute(key, (k, existing) -> {
			if (existing == null) {
				existing = new ArrayList<>();
			}
			existing.addAll(generatedFiles);
			totalSize[0] = existing.size();
			return existing;
		});
		logger.debug("Stored {} files for session {}, total now: {}", generatedFiles.size(), key, totalSize[0]);
	}

	/**
	 * Retrieve and clear stored files for a session.
	 * @param sessionId the session ID (use DEFAULT_SESSION_ID for single-user)
	 * @return list of files, or null if none stored
	 */
	public List<GeneratedFile> retrieve(String sessionId) {
		String key = sessionId != null ? sessionId : DEFAULT_SESSION_ID;
		List<GeneratedFile> result = this.filesBySession.asMap().remove(key);
		logger.debug("Retrieved {} files for session {}", result != null ? result.size() : 0, key);
		return result;
	}

	/**
	 * Check if files are stored for a session.
	 * @param sessionId the session ID
	 * @return true if files are available
	 */
	public boolean hasFiles(String sessionId) {
		String key = sessionId != null ? sessionId : DEFAULT_SESSION_ID;
		List<GeneratedFile> stored = this.filesBySession.getIfPresent(key);
		return stored != null && !stored.isEmpty();
	}

}
