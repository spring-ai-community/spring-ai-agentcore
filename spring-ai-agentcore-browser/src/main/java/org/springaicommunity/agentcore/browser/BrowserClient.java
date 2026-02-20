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

/**
 * Interface for browser operations. Implementations can use AgentCore Browser (remote) or
 * a local Playwright browser.
 *
 * @author Yuriy Bezsonov
 */
public interface BrowserClient {

	/**
	 * Browse a URL and extract page content.
	 * @param url the URL to navigate to
	 * @return extracted page content
	 * @throws BrowserOperationException if the operation fails
	 */
	String browseAndExtract(String url);

	/**
	 * Take a screenshot of a web page and return raw bytes.
	 * @param url the URL to navigate to
	 * @return PNG bytes
	 * @throws BrowserOperationException if screenshot capture fails
	 */
	byte[] screenshotBytes(String url);

	/**
	 * Click an element on a web page.
	 * @param url the URL to navigate to
	 * @param selector CSS selector for the element to click
	 * @return result message
	 * @throws BrowserOperationException if the operation fails
	 */
	String click(String url, String selector);

	/**
	 * Fill a form field on a web page.
	 * @param url the URL to navigate to
	 * @param selector CSS selector for the input field
	 * @param value value to fill
	 * @return result message
	 * @throws BrowserOperationException if the operation fails
	 */
	String fill(String url, String selector, String value);

	/**
	 * Execute JavaScript on a web page.
	 * @param url the URL to navigate to
	 * @param script JavaScript code to execute
	 * @return script result as string
	 * @throws BrowserOperationException if the operation fails
	 */
	String evaluate(String url, String script);

}
