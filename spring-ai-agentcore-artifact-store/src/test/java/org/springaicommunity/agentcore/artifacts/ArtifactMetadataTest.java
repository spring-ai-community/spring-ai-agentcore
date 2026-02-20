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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ArtifactMetadata}.
 *
 * @author Yuriy Bezsonov
 */
@DisplayName("ArtifactMetadata Tests")
class ArtifactMetadataTest {

	@Test
	@DisplayName("Should create metadata with timestamp only")
	void shouldCreateMetadataWithTimestampOnly() {
		Map<String, String> meta = ArtifactMetadata.withTimestamp();

		assertThat(meta).containsKey(ArtifactMetadata.META_TIMESTAMP);
		assertThat(meta).hasSize(1);
	}

	@Test
	@DisplayName("Should create metadata with timestamp and key-value pairs")
	void shouldCreateMetadataWithTimestampAndKeyValuePairs() {
		Map<String, String> meta = ArtifactMetadata.withTimestamp("url", "https://example.com", "width", "1920");

		assertThat(meta).containsKey(ArtifactMetadata.META_TIMESTAMP);
		assertThat(meta).containsEntry("url", "https://example.com");
		assertThat(meta).containsEntry("width", "1920");
		assertThat(meta).hasSize(3);
	}

	@Test
	@DisplayName("Should skip null keys and values in withTimestamp")
	void shouldSkipNullKeysAndValuesInWithTimestamp() {
		Map<String, String> meta = ArtifactMetadata.withTimestamp("key1", "value1", null, "value2", "key3", null);

		assertThat(meta).containsKey(ArtifactMetadata.META_TIMESTAMP);
		assertThat(meta).containsEntry("key1", "value1");
		assertThat(meta).doesNotContainKey(null);
		assertThat(meta).doesNotContainKey("key3");
		assertThat(meta).hasSize(2);
	}

	@Test
	@DisplayName("Should throw for odd number of arguments in withTimestamp")
	void shouldThrowForOddNumberOfArgumentsInWithTimestamp() {
		assertThatThrownBy(() -> ArtifactMetadata.withTimestamp("key1", "value1", "key2"))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("even number");
	}

	@Test
	@DisplayName("Should return parseable timestamp from nowTimestamp")
	void shouldReturnParseableTimestampFromNowTimestamp() {
		String ts = ArtifactMetadata.nowTimestamp();

		assertThat(ts).isNotNull();
		assertThat(java.time.Instant.parse(ts)).isNotNull();
	}

	@Test
	@DisplayName("Should extract timestamp from GeneratedFile")
	void shouldExtractTimestampFromGeneratedFile() {
		GeneratedFile file = GeneratedFile.withTimestamp("text/plain", new byte[0], "test.txt");

		assertThat(ArtifactMetadata.timestamp(file)).isPresent();
	}

	@Test
	@DisplayName("Should return empty for file without timestamp")
	void shouldReturnEmptyForFileWithoutTimestamp() {
		GeneratedFile file = new GeneratedFile("text/plain", new byte[0], "test.txt");

		assertThat(ArtifactMetadata.timestamp(file)).isEmpty();
	}

}
