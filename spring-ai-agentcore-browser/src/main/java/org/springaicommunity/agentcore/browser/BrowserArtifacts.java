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

import java.util.Map;
import java.util.Optional;

import org.springaicommunity.agentcore.artifacts.ArtifactMetadata;
import org.springaicommunity.agentcore.artifacts.GeneratedFile;

/**
 * Browser-specific artifact utilities for creating and accessing screenshot metadata.
 *
 * @author Yuriy Bezsonov
 */
public final class BrowserArtifacts {

	/** Metadata key for source URL. */
	public static final String META_URL = "url";

	/** Metadata key for viewport width. */
	public static final String META_WIDTH = "width";

	/** Metadata key for viewport height. */
	public static final String META_HEIGHT = "height";

	private BrowserArtifacts() {
	}

	/**
	 * Create a browser screenshot with full metadata.
	 * @param data PNG bytes
	 * @param url source URL
	 * @param width viewport width
	 * @param height viewport height
	 * @return GeneratedFile with screenshot metadata
	 */
	public static GeneratedFile screenshot(byte[] data, String url, int width, int height) {
		Map<String, String> meta = ArtifactMetadata.withTimestamp(META_URL, url, META_WIDTH, String.valueOf(width),
				META_HEIGHT, String.valueOf(height));
		return new GeneratedFile("image/png", data, "screenshot-" + url.hashCode() + ".png", meta);
	}

	/**
	 * Get the source URL from a screenshot.
	 * @param file the generated file
	 * @return source URL if present
	 */
	public static Optional<String> url(GeneratedFile file) {
		return Optional.ofNullable(file.metadata().get(META_URL));
	}

	/**
	 * Get the viewport width from a screenshot.
	 * @param file the generated file
	 * @return width if present and valid
	 */
	public static Optional<Integer> width(GeneratedFile file) {
		String w = file.metadata().get(META_WIDTH);
		if (w == null) {
			return Optional.empty();
		}
		try {
			return Optional.of(Integer.parseInt(w));
		}
		catch (NumberFormatException e) {
			return Optional.empty();
		}
	}

	/**
	 * Get the viewport height from a screenshot.
	 * @param file the generated file
	 * @return height if present and valid
	 */
	public static Optional<Integer> height(GeneratedFile file) {
		String h = file.metadata().get(META_HEIGHT);
		if (h == null) {
			return Optional.empty();
		}
		try {
			return Optional.of(Integer.parseInt(h));
		}
		catch (NumberFormatException e) {
			return Optional.empty();
		}
	}

}
