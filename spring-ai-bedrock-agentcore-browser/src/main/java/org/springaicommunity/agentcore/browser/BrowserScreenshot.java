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

import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;

/**
 * Represents a screenshot captured from a browser session.
 *
 * @param mimeType MIME type (always "image/png")
 * @param data raw PNG bytes (defensively copied on construction)
 * @param url the URL that was captured
 * @param width viewport width in pixels
 * @param height viewport height in pixels
 * @param timestamp when the screenshot was taken
 * @author Yuriy Bezsonov
 */
public record BrowserScreenshot(String mimeType, byte[] data, String url, int width, int height, Instant timestamp) {

	private static final String DEFAULT_MIME_TYPE = "image/png";

	/**
	 * Canonical constructor with defensive copy of data array.
	 */
	public BrowserScreenshot {
		data = data != null ? Arrays.copyOf(data, data.length) : new byte[0];
		if (mimeType == null || mimeType.isBlank()) {
			mimeType = DEFAULT_MIME_TYPE;
		}
		if (timestamp == null) {
			timestamp = Instant.now();
		}
	}

	/**
	 * Convenience constructor for PNG screenshots.
	 */
	public BrowserScreenshot(byte[] data, String url, int width, int height) {
		this(DEFAULT_MIME_TYPE, data, url, width, height, Instant.now());
	}

	/**
	 * Returns a copy of the data array to prevent mutation.
	 * @return copy of the screenshot data
	 */
	@Override
	public byte[] data() {
		return Arrays.copyOf(data, data.length);
	}

	/**
	 * Get the size of the screenshot data in bytes.
	 * @return size in bytes
	 */
	public int size() {
		return data.length;
	}

	/**
	 * Convert to data URL for embedding in HTML.
	 * @return data URL string (e.g., "data:image/png;base64,...")
	 */
	public String toDataUrl() {
		return "data:" + mimeType + ";base64," + Base64.getEncoder().encodeToString(data());
	}

}
