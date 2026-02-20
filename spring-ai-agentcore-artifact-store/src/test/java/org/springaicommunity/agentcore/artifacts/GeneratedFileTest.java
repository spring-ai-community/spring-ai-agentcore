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

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link GeneratedFile}.
 *
 * @author Yuriy Bezsonov
 */
@DisplayName("GeneratedFile Tests")
class GeneratedFileTest {

	@Test
	@DisplayName("Should create file with all fields")
	void shouldCreateFileWithAllFields() {
		byte[] data = new byte[] { 1, 2, 3 };
		GeneratedFile file = new GeneratedFile("image/png", data, "test.png");

		assertThat(file.mimeType()).isEqualTo("image/png");
		assertThat(file.data()).isEqualTo(data);
		assertThat(file.name()).isEqualTo("test.png");
	}

	@Test
	@DisplayName("Should defensively copy data on construction")
	void shouldDefensivelyCopyDataOnConstruction() {
		byte[] original = new byte[] { 1, 2, 3 };
		GeneratedFile file = new GeneratedFile("image/png", original, "test.png");

		original[0] = 99;

		assertThat(file.data()[0]).isEqualTo((byte) 1);
	}

	@Test
	@DisplayName("Should defensively copy data on access")
	void shouldDefensivelyCopyDataOnAccess() {
		GeneratedFile file = new GeneratedFile("image/png", new byte[] { 1, 2, 3 }, "test.png");

		byte[] accessed = file.data();
		accessed[0] = 99;

		assertThat(file.data()[0]).isEqualTo((byte) 1);
	}

	@Test
	@DisplayName("Should handle null data")
	void shouldHandleNullData() {
		GeneratedFile file = new GeneratedFile("image/png", null, "test.png");

		assertThat(file.data()).isEmpty();
		assertThat(file.size()).isZero();
	}

	@Test
	@DisplayName("Should detect image mime type")
	void shouldDetectImageMimeType() {
		assertThat(new GeneratedFile("image/png", new byte[0], "test.png").isImage()).isTrue();
		assertThat(new GeneratedFile("image/jpeg", new byte[0], "test.jpg").isImage()).isTrue();
		assertThat(new GeneratedFile("image/gif", new byte[0], "test.gif").isImage()).isTrue();
		assertThat(new GeneratedFile("application/pdf", new byte[0], "test.pdf").isImage()).isFalse();
	}

	@Test
	@DisplayName("Should detect text mime type")
	void shouldDetectTextMimeType() {
		assertThat(new GeneratedFile("text/plain", new byte[0], "test.txt").isText()).isTrue();
		assertThat(new GeneratedFile("text/csv", new byte[0], "test.csv").isText()).isTrue();
		assertThat(new GeneratedFile("application/json", new byte[0], "test.json").isText()).isTrue();
		assertThat(new GeneratedFile("application/xml", new byte[0], "test.xml").isText()).isTrue();
		assertThat(new GeneratedFile("application/javascript", new byte[0], "test.js").isText()).isTrue();
		assertThat(new GeneratedFile("application/yaml", new byte[0], "test.yaml").isText()).isTrue();
		assertThat(new GeneratedFile("application/x-yaml", new byte[0], "test.yml").isText()).isTrue();
		assertThat(new GeneratedFile("image/png", new byte[0], "test.png").isText()).isFalse();
	}

	@Test
	@DisplayName("Should handle null mime type for isImage")
	void shouldHandleNullMimeTypeForIsImage() {
		GeneratedFile file = new GeneratedFile(null, new byte[0], "test");

		assertThat(file.isImage()).isFalse();
	}

	@Test
	@DisplayName("Should handle null mime type for isText")
	void shouldHandleNullMimeTypeForIsText() {
		GeneratedFile file = new GeneratedFile(null, new byte[0], "test");

		assertThat(file.isText()).isFalse();
	}

	@Test
	@DisplayName("Should generate valid data URL")
	void shouldGenerateValidDataUrl() {
		byte[] data = "hello".getBytes();
		GeneratedFile file = new GeneratedFile("text/plain", data, "test.txt");

		String dataUrl = file.toDataUrl();

		assertThat(dataUrl).startsWith("data:text/plain;base64,");
		assertThat(dataUrl).isEqualTo("data:text/plain;base64,aGVsbG8=");
	}

	@Test
	@DisplayName("Should return correct size")
	void shouldReturnCorrectSize() {
		GeneratedFile file = new GeneratedFile("image/png", new byte[] { 1, 2, 3, 4, 5 }, "test.png");

		assertThat(file.size()).isEqualTo(5);
	}

	@Test
	@DisplayName("Should have immutable metadata")
	void shouldHaveImmutableMetadata() {
		java.util.Map<String, String> mutableMeta = new java.util.HashMap<>();
		mutableMeta.put("key", "value");
		GeneratedFile file = new GeneratedFile("image/png", new byte[0], "test.png", mutableMeta);

		// Modify original map
		mutableMeta.put("key", "modified");
		mutableMeta.put("newKey", "newValue");

		// File metadata should be unchanged
		assertThat(file.metadata().get("key")).isEqualTo("value");
		assertThat(file.metadata().containsKey("newKey")).isFalse();
	}

	@Test
	@DisplayName("Should handle null metadata in constructor")
	void shouldHandleNullMetadataInConstructor() {
		GeneratedFile file = new GeneratedFile("image/png", new byte[0], "test.png", null);

		assertThat(file.metadata()).isEmpty();
	}

	@Test
	@DisplayName("Should create file with timestamp using factory method")
	void shouldCreateFileWithTimestampUsingFactoryMethod() {
		byte[] data = new byte[] { 1, 2, 3 };
		GeneratedFile file = GeneratedFile.withTimestamp("image/png", data, "test.png");

		assertThat(file.mimeType()).isEqualTo("image/png");
		assertThat(file.data()).isEqualTo(data);
		assertThat(file.name()).isEqualTo("test.png");
		assertThat(file.metadata()).containsKey(ArtifactMetadata.META_TIMESTAMP);
		assertThat(ArtifactMetadata.timestamp(file)).isPresent();
	}

}
