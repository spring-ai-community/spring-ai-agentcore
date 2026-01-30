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
import org.springframework.ai.chat.model.ToolContext;

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

	private final AgentCoreBrowserClient client;

	private final BrowserScreenshotStore screenshotStore;

	private final AgentCoreBrowserConfiguration config;

	public BrowserTools(AgentCoreBrowserClient client, BrowserScreenshotStore screenshotStore,
			AgentCoreBrowserConfiguration config) {
		this.client = client;
		this.screenshotStore = screenshotStore;
		this.config = config;
		logger.debug("BrowserTools initialized");
	}

	/**
	 * Browse a URL and extract page content.
	 * @param url the URL to navigate to
	 * @return extracted page content with title
	 */
	public String browseUrl(String url) {
		logger.debug("browseUrl: {}", url);
		try {
			return client.browseAndExtract(url);
		}
		catch (BrowserOperationException e) {
			logger.error("Browse failed: {}", e.getMessage());
			return "Error: " + e.getMessage();
		}
	}

	/**
	 * Take a screenshot of a web page.
	 * @param url the URL to navigate to
	 * @param toolContext context containing session ID for multi-user support
	 * @return metadata about the captured screenshot
	 */
	public String takeScreenshot(String url, ToolContext toolContext) {
		logger.debug("takeScreenshot: {}", url);

		try {
			byte[] screenshotBytes = client.screenshotBytes(url);

			String sessionId = extractSessionId(toolContext);

			// Store screenshot
			BrowserScreenshot screenshot = new BrowserScreenshot(screenshotBytes, url, config.viewportWidth(),
					config.viewportHeight());
			screenshotStore.store(sessionId, screenshot);

			logger.debug("Screenshot stored for session {}: {} bytes", sessionId, screenshotBytes.length);

			return String.format("Screenshot captured: %d bytes, %dx%d from %s", screenshotBytes.length,
					config.viewportWidth(), config.viewportHeight(), url);
		}
		catch (BrowserOperationException e) {
			logger.error("Screenshot failed: {}", e.getMessage());
			return "Error: " + e.getMessage();
		}
	}

	/**
	 * Extract session ID from tool context.
	 * @param toolContext the tool context
	 * @return session ID or default if not present
	 */
	private String extractSessionId(ToolContext toolContext) {
		if (toolContext != null && toolContext.getContext() != null) {
			Object sessionIdObj = toolContext.getContext().get(BrowserScreenshotStore.SESSION_ID_KEY);
			if (sessionIdObj instanceof String s && !s.isBlank()) {
				return s;
			}
		}
		return BrowserScreenshotStore.DEFAULT_SESSION_ID;
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
			return client.click(url, selector);
		}
		catch (BrowserOperationException e) {
			logger.error("Click failed: {}", e.getMessage());
			return "Error: " + e.getMessage();
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
			return client.fill(url, selector, value);
		}
		catch (BrowserOperationException e) {
			logger.error("Fill failed: {}", e.getMessage());
			return "Error: " + e.getMessage();
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
			return client.evaluate(url, script);
		}
		catch (BrowserOperationException e) {
			logger.error("Evaluate failed: {}", e.getMessage());
			return "Error: " + e.getMessage();
		}
	}

}
