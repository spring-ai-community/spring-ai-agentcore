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

import org.springaicommunity.agentcore.artifacts.ArtifactStoreFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for AgentCore Browser.
 *
 * @param mode browser mode: "agentcore" (default) for AgentCore Browser service, "local"
 * for local Chromium
 * @param sessionTimeoutSeconds session timeout in seconds (default 900)
 * @param browserIdentifier identifier for the browser service (default "aws.browser.v1")
 * @param viewportWidth browser viewport width in pixels (default 1456)
 * @param viewportHeight browser viewport height in pixels (default 819)
 * @param maxContentLength maximum content length before truncation (default 10000)
 * @param screenshotTtlSeconds TTL for cached screenshots in seconds (default 300)
 * @param artifactStoreMaxSize maximum sessions in artifact store (default 10000)
 * @param browseUrlDescription custom description for browseUrl tool (optional)
 * @param screenshotDescription custom description for takeScreenshot tool (optional)
 * @param clickDescription custom description for clickElement tool (optional)
 * @param fillDescription custom description for fillForm tool (optional)
 * @param evaluateDescription custom description for evaluateScript tool (optional)
 * @author Yuriy Bezsonov
 */
@ConfigurationProperties(prefix = "agentcore.browser")
public record AgentCoreBrowserConfiguration(String mode, Integer sessionTimeoutSeconds, String browserIdentifier,
		Integer viewportWidth, Integer viewportHeight, Integer maxContentLength, Integer screenshotTtlSeconds,
		Integer artifactStoreMaxSize, String browseUrlDescription, String screenshotDescription,
		String clickDescription, String fillDescription, String evaluateDescription) {

	/** Default browser mode. */
	public static final String DEFAULT_MODE = "agentcore";

	/** Local browser mode. */
	public static final String MODE_LOCAL = "local";

	/** Default session timeout in seconds. */
	public static final int DEFAULT_SESSION_TIMEOUT_SECONDS = 900;

	/** Default browser identifier. */
	public static final String DEFAULT_BROWSER_IDENTIFIER = "aws.browser.v1";

	/** Default viewport width in pixels. */
	public static final int DEFAULT_VIEWPORT_WIDTH = 1456;

	/** Default viewport height in pixels. */
	public static final int DEFAULT_VIEWPORT_HEIGHT = 819;

	/** Default maximum content length before truncation. */
	public static final int DEFAULT_MAX_CONTENT_LENGTH = 10000;

	/** Default TTL for cached screenshots in seconds. */
	public static final int DEFAULT_SCREENSHOT_TTL_SECONDS = 300;

	public AgentCoreBrowserConfiguration {
		if (mode == null || mode.isBlank()) {
			mode = DEFAULT_MODE;
		}
		if (sessionTimeoutSeconds == null || sessionTimeoutSeconds <= 0) {
			sessionTimeoutSeconds = DEFAULT_SESSION_TIMEOUT_SECONDS;
		}
		if (browserIdentifier == null || browserIdentifier.isEmpty()) {
			browserIdentifier = DEFAULT_BROWSER_IDENTIFIER;
		}
		if (viewportWidth == null || viewportWidth <= 0) {
			viewportWidth = DEFAULT_VIEWPORT_WIDTH;
		}
		if (viewportHeight == null || viewportHeight <= 0) {
			viewportHeight = DEFAULT_VIEWPORT_HEIGHT;
		}
		if (maxContentLength == null || maxContentLength <= 0) {
			maxContentLength = DEFAULT_MAX_CONTENT_LENGTH;
		}
		if (screenshotTtlSeconds == null || screenshotTtlSeconds <= 0) {
			screenshotTtlSeconds = DEFAULT_SCREENSHOT_TTL_SECONDS;
		}
		if (artifactStoreMaxSize == null || artifactStoreMaxSize <= 0) {
			artifactStoreMaxSize = ArtifactStoreFactory.DEFAULT_MAX_SIZE;
		}
		// Tool descriptions can be null - will use defaults from BrowserTools
	}

}
