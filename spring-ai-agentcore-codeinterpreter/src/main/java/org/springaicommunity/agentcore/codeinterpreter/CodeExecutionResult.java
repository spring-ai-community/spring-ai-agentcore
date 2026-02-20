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

import java.util.List;

import org.springaicommunity.agentcore.artifacts.GeneratedFile;

/**
 * Immutable result of code execution.
 *
 * @param textOutput stdout/stderr combined output
 * @param isError true if execution resulted in an error
 * @param files list of generated files (images, PDFs, etc.)
 * @author Yuriy Bezsonov
 */
public record CodeExecutionResult(String textOutput, boolean isError, List<GeneratedFile> files) {

	/**
	 * Canonical constructor with null-safe defaults.
	 */
	public CodeExecutionResult {
		textOutput = textOutput != null ? textOutput : "";
		files = files != null ? List.copyOf(files) : List.of();
	}

	/**
	 * Check if any files were generated.
	 * @return true if files list is non-empty
	 */
	public boolean hasFiles() {
		return !files.isEmpty();
	}

	/**
	 * Check if any image files were generated.
	 * @return true if at least one file is an image
	 */
	public boolean hasImages() {
		return files.stream().anyMatch(GeneratedFile::isImage);
	}

}
