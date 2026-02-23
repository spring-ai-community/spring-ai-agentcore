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

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springaicommunity.agentcore.artifacts.ArtifactMetadata;
import org.springaicommunity.agentcore.artifacts.GeneratedFile;

/**
 * Unit tests for {@link CodeInterpreterArtifacts}.
 *
 * @author Yuriy Bezsonov
 */
@DisplayName("CodeInterpreterArtifacts Tests")
class CodeInterpreterArtifactsTest {

	@Test
	@DisplayName("Should create file from path with metadata")
	void shouldCreateFileFromPathWithMetadata() {
		byte[] data = "test content".getBytes();
		GeneratedFile file = CodeInterpreterArtifacts.fromPath("text/csv", data, "report.csv",
				"/sandbox/output/report.csv");

		assertThat(file.mimeType()).isEqualTo("text/csv");
		assertThat(file.data()).isEqualTo(data);
		assertThat(file.name()).isEqualTo("report.csv");
		assertThat(CodeInterpreterArtifacts.sourcePath(file)).hasValue("/sandbox/output/report.csv");
		assertThat(ArtifactMetadata.timestamp(file)).isPresent();
	}

	@Test
	@DisplayName("Should handle null source path")
	void shouldHandleNullSourcePath() {
		GeneratedFile file = CodeInterpreterArtifacts.fromPath("text/plain", new byte[0], "test.txt", null);

		assertThat(CodeInterpreterArtifacts.sourcePath(file)).isEmpty();
		assertThat(ArtifactMetadata.timestamp(file)).isPresent();
	}

	@Test
	@DisplayName("File from path should preserve type detection")
	void fileFromPathShouldPreserveTypeDetection() {
		GeneratedFile csvFile = CodeInterpreterArtifacts.fromPath("text/csv", new byte[0], "data.csv",
				"/path/data.csv");
		GeneratedFile pngFile = CodeInterpreterArtifacts.fromPath("image/png", new byte[0], "chart.png",
				"/path/chart.png");

		assertThat(csvFile.isText()).isTrue();
		assertThat(csvFile.isImage()).isFalse();
		assertThat(pngFile.isImage()).isTrue();
		assertThat(pngFile.isText()).isFalse();
	}

	@Test
	@DisplayName("Should return empty optional for file without source path")
	void shouldReturnEmptyOptionalForFileWithoutSourcePath() {
		GeneratedFile file = new GeneratedFile("text/plain", new byte[0], "test.txt");

		assertThat(CodeInterpreterArtifacts.sourcePath(file)).isEmpty();
		assertThat(ArtifactMetadata.timestamp(file)).isEmpty();
	}

}
