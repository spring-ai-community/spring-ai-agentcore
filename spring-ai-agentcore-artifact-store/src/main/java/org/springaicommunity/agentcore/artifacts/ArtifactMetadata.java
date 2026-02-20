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

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Shared metadata utilities for artifacts.
 *
 * @author Yuriy Bezsonov
 */
public final class ArtifactMetadata {

	/** Metadata key for creation timestamp. */
	public static final String META_TIMESTAMP = "timestamp";

	/** Metadata key for tool call ID (to group artifacts by invocation). */
	public static final String META_TOOL_CALL_ID = "toolCallId";

	private ArtifactMetadata() {
	}

	/**
	 * Get the creation timestamp from an artifact.
	 * @param file the generated file
	 * @return timestamp if present
	 */
	public static Optional<Instant> timestamp(GeneratedFile file) {
		String ts = file.metadata().get(META_TIMESTAMP);
		return ts != null ? Optional.of(Instant.parse(ts)) : Optional.empty();
	}

	/**
	 * Get the current timestamp as a string for metadata.
	 * @return current timestamp string
	 */
	public static String nowTimestamp() {
		return Instant.now().toString();
	}

	/**
	 * Create a metadata map with timestamp and additional key-value pairs.
	 * <p>
	 * Example usage: <pre>
	 * Map&lt;String, String&gt; meta = ArtifactMetadata.withTimestamp("url", url, "width", "1920");
	 * </pre>
	 * @param keyValues alternating keys and values (must be even number)
	 * @return mutable map with timestamp and provided entries
	 * @throws IllegalArgumentException if odd number of arguments
	 */
	public static Map<String, String> withTimestamp(String... keyValues) {
		if (keyValues.length % 2 != 0) {
			throw new IllegalArgumentException("Must provide key-value pairs (even number of arguments)");
		}
		Map<String, String> meta = new HashMap<>();
		meta.put(META_TIMESTAMP, nowTimestamp());
		for (int i = 0; i < keyValues.length; i += 2) {
			if (keyValues[i] != null && keyValues[i + 1] != null) {
				meta.put(keyValues[i], keyValues[i + 1]);
			}
		}
		return meta;
	}

}
