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

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springaicommunity.agentcore.artifacts.ArtifactMetadata;
import org.springaicommunity.agentcore.artifacts.GeneratedFile;

/**
 * Unit tests for {@link BrowserArtifacts}.
 *
 * @author Yuriy Bezsonov
 */
@DisplayName("BrowserArtifacts Tests")
class BrowserArtifactsTest {

	@Test
	@DisplayName("Should create screenshot with full metadata")
	void shouldCreateScreenshotWithFullMetadata() {
		byte[] data = new byte[] { 1, 2, 3 };
		GeneratedFile screenshot = BrowserArtifacts.screenshot(data, "https://example.com", 1920, 1080);

		assertThat(screenshot.mimeType()).isEqualTo("image/png");
		assertThat(screenshot.data()).isEqualTo(data);
		assertThat(screenshot.name()).startsWith("screenshot-");
		assertThat(screenshot.name()).endsWith(".png");
		assertThat(BrowserArtifacts.url(screenshot)).hasValue("https://example.com");
		assertThat(BrowserArtifacts.width(screenshot)).hasValue(1920);
		assertThat(BrowserArtifacts.height(screenshot)).hasValue(1080);
		assertThat(ArtifactMetadata.timestamp(screenshot)).isPresent();
	}

	@Test
	@DisplayName("Screenshot should be detected as image")
	void screenshotShouldBeDetectedAsImage() {
		GeneratedFile screenshot = BrowserArtifacts.screenshot(new byte[] { 1 }, "https://example.com", 800, 600);

		assertThat(screenshot.isImage()).isTrue();
		assertThat(screenshot.isText()).isFalse();
	}

	@Test
	@DisplayName("Should return empty optional for non-screenshot file")
	void shouldReturnEmptyOptionalForNonScreenshotFile() {
		GeneratedFile file = new GeneratedFile("text/plain", new byte[0], "test.txt");

		assertThat(BrowserArtifacts.url(file)).isEmpty();
		assertThat(BrowserArtifacts.width(file)).isEmpty();
		assertThat(BrowserArtifacts.height(file)).isEmpty();
		assertThat(ArtifactMetadata.timestamp(file)).isEmpty();
	}

	@Test
	@DisplayName("Should return empty optional for invalid width/height values")
	void shouldReturnEmptyOptionalForInvalidWidthHeightValues() {
		GeneratedFile file = new GeneratedFile("image/png", new byte[0], "test.png",
				java.util.Map.of(BrowserArtifacts.META_WIDTH, "invalid", BrowserArtifacts.META_HEIGHT, "not-a-number"));

		assertThat(BrowserArtifacts.width(file)).isEmpty();
		assertThat(BrowserArtifacts.height(file)).isEmpty();
	}

}
