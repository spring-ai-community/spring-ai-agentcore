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

import java.util.Map;
import java.util.Optional;

import org.springaicommunity.agentcore.artifacts.ArtifactMetadata;
import org.springaicommunity.agentcore.artifacts.GeneratedFile;

/**
 * Code interpreter-specific artifact utilities for creating and accessing file metadata.
 *
 * @author Yuriy Bezsonov
 */
public final class CodeInterpreterArtifacts {

	/** Metadata key for source file path in sandbox. */
	public static final String META_SOURCE_PATH = "sourcePath";

	private CodeInterpreterArtifacts() {
	}

	/**
	 * Create a file from code interpreter with source path metadata.
	 * @param mimeType MIME type
	 * @param data file bytes
	 * @param name filename
	 * @param sourcePath original path in sandbox
	 * @return GeneratedFile with source path metadata
	 */
	public static GeneratedFile fromPath(String mimeType, byte[] data, String name, String sourcePath) {
		Map<String, String> meta = ArtifactMetadata.withTimestamp(META_SOURCE_PATH, sourcePath);
		return new GeneratedFile(mimeType, data, name, meta);
	}

	/**
	 * Get the source file path from a generated file.
	 * @param file the generated file
	 * @return source path if present
	 */
	public static Optional<String> sourcePath(GeneratedFile file) {
		return Optional.ofNullable(file.metadata().get(META_SOURCE_PATH));
	}

}
