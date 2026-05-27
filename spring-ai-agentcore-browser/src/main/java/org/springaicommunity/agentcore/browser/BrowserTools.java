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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springaicommunity.agentcore.artifacts.ArtifactStore;
import org.springaicommunity.agentcore.artifacts.GeneratedFile;
import org.springaicommunity.agentcore.artifacts.SessionConstants;
import reactor.util.context.ContextView;

import org.springframework.ai.model.tool.internal.ToolCallReactiveContextHolder;

/**
 * Browser tool implementation for browsing web pages and extracting content.
 * <p>
 * This class contains the tool logic. Tool registration with configurable description is
 * handled by {@link AgentCoreBrowserAutoConfiguration}.
 *
 * @author Yuriy Bezsonov
 */
public class BrowserTools {

	private static final Logger logger = LoggerFactory.getLogger(BrowserTools.class);

	/**
	 * Category used when storing artifacts. Use this when retrieving from shared store.
	 */
	public static final String CATEGORY = "browser";

	public static final String BROWSE_URL_DESCRIPTION = """
			Browse a web page and extract its text content.
			Returns the page title and body text.
			""";

	public static final String SCREENSHOT_DESCRIPTION = """
			Take a screenshot of a web page.
			Returns metadata about the captured image. Screenshot is stored for retrieval.
			""";

	public static final String CLICK_DESCRIPTION = """
			Click an element on a web page.
			Selector is a CSS selector (for example, 'button', '#submit', '.btn-primary').
			""";

	public static final String FILL_DESCRIPTION = """
			Fill a form field on a web page.
			Selector is a CSS selector for the input field.
			""";

	public static final String EVALUATE_DESCRIPTION = """
			Execute JavaScript on a web page and return the result.
			""";

	private final BrowserClient client;

	private final ArtifactStore<GeneratedFile> artifactStore;

	private final AgentCoreBrowserConfiguration config;

	private final String category;

	/**
	 * Create BrowserTools with default category (artifacts stored without category).
	 * @param client the browser client
	 * @param artifactStore the artifact store
	 * @param config the browser configuration
	 */
	public BrowserTools(BrowserClient client, ArtifactStore<GeneratedFile> artifactStore,
			AgentCoreBrowserConfiguration config) {
		this(client, artifactStore, config, null);
	}

	/**
	 * Create BrowserTools with explicit category for artifact storage.
	 * @param client the browser client
	 * @param artifactStore the artifact store
	 * @param config the browser configuration
	 * @param category the category for storing artifacts (null for default category)
	 */
	public BrowserTools(BrowserClient client, ArtifactStore<GeneratedFile> artifactStore,
			AgentCoreBrowserConfiguration config, String category) {
		this.client = client;
		this.artifactStore = artifactStore;
		this.config = config;
		this.category = category;
		logger.debug("BrowserTools initialized with category: {}",
				(category != null) ? category : ArtifactStore.DEFAULT_CATEGORY);
	}

	/**
	 * Browse a URL and extract page content.
	 * @param url the URL to navigate to
	 * @return extracted page content with title
	 */
	public String browseUrl(String url) {
		logger.debug("browseUrl: {}", url);
		try {
			return this.client.browseAndExtract(url);
		}
		catch (BrowserOperationException ex) {
			logger.error("Browse failed: {}", ex.getMessage());
			return "Error: " + ex.getMessage();
		}
	}

	/**
	 * Take a screenshot of a web page.
	 * @param url the URL to navigate to
	 * @return metadata about the captured screenshot
	 */
	public String takeScreenshot(String url) {
		logger.debug("takeScreenshot: {}", url);

		try {
			byte[] screenshotBytes = this.client.screenshotBytes(url);

			// Get session ID from Reactor context (available via
			// ToolCallReactiveContextHolder)
			ContextView ctx = ToolCallReactiveContextHolder.getContext();
			String sessionId = ctx.getOrDefault(SessionConstants.SESSION_ID_KEY, SessionConstants.DEFAULT_SESSION_ID);

			// Store screenshot as GeneratedFile with metadata
			GeneratedFile screenshot = BrowserArtifacts.screenshot(screenshotBytes, url, this.config.viewportWidth(),
					this.config.viewportHeight());
			if (this.category != null) {
				this.artifactStore.store(sessionId, this.category, screenshot);
			}
			else {
				this.artifactStore.store(sessionId, screenshot);
			}

			logger.debug("Screenshot stored for session {}: {} bytes", sessionId, screenshotBytes.length);

			return String.format("Screenshot captured: %d bytes, %dx%d from %s", screenshotBytes.length,
					this.config.viewportWidth(), this.config.viewportHeight(), url);
		}
		catch (BrowserOperationException ex) {
			logger.error("Screenshot failed: {}", ex.getMessage());
			return "Error: " + ex.getMessage();
		}
	}

	/**
	 * Click an element on a web page.
	 * @param url the URL to navigate to
	 * @param selector CSS selector for the element
	 * @return result message
	 */
	public String clickElement(String url, String selector) {
		logger.debug("clickElement: {} -> {}", url, selector);
		try {
			return this.client.click(url, selector);
		}
		catch (BrowserOperationException ex) {
			logger.error("Click failed: {}", ex.getMessage());
			return "Error: " + ex.getMessage();
		}
	}

	/**
	 * Fill a form field on a web page.
	 * @param url the URL to navigate to
	 * @param selector CSS selector for the input
	 * @param value value to fill
	 * @return result message
	 */
	public String fillForm(String url, String selector, String value) {
		logger.debug("fillForm: {} -> {} = {}", url, selector, value);
		try {
			return this.client.fill(url, selector, value);
		}
		catch (BrowserOperationException ex) {
			logger.error("Fill failed: {}", ex.getMessage());
			return "Error: " + ex.getMessage();
		}
	}

	/**
	 * Execute JavaScript on a web page.
	 * @param url the URL to navigate to
	 * @param script JavaScript code
	 * @return script result
	 */
	public String evaluateScript(String url, String script) {
		logger.debug("evaluateScript: {} -> {}", url, script);
		try {
			return this.client.evaluate(url, script);
		}
		catch (BrowserOperationException ex) {
			logger.error("Evaluate failed: {}", ex.getMessage());
			return "Error: " + ex.getMessage();
		}
	}

}
