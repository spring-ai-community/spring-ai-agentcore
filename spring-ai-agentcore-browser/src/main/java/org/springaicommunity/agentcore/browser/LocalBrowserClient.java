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

import java.util.function.Function;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Local browser client using Playwright with a locally launched Chromium. Useful for
 * development and testing without requiring AgentCore Browser service.
 *
 * @author Yuriy Bezsonov
 */
public class LocalBrowserClient implements BrowserClient {

	private static final Logger logger = LoggerFactory.getLogger(LocalBrowserClient.class);

	private final Playwright playwright;

	private final AgentCoreBrowserConfiguration config;

	public LocalBrowserClient(Playwright playwright, AgentCoreBrowserConfiguration config) {
		this.playwright = playwright;
		this.config = config;
		logger.info("LocalBrowserClient initialized: viewport={}x{}, maxContent={}", config.viewportWidth(),
				config.viewportHeight(), config.maxContentLength());
	}

	@Override
	public String browseAndExtract(String url) {
		return executeOnPage(url, page -> {
			String title = page.title();
			String textContent = page.locator("body").innerText();

			logger.info("Page loaded: {} ({} chars)", title, textContent.length());

			return PageContentFormatter.format(title, textContent, config.maxContentLength());
		});
	}

	@Override
	public byte[] screenshotBytes(String url) {
		return executeOnPage(url, page -> {
			byte[] bytes = page.screenshot();
			logger.info("Screenshot taken: {} bytes", bytes.length);
			return bytes;
		});
	}

	@Override
	public String click(String url, String selector) {
		return executeOnPage(url, page -> {
			page.click(selector);
			page.waitForLoadState();
			String newUrl = page.url();
			String title = page.title();
			logger.info("Clicked '{}', now at: {}", selector, newUrl);
			return String.format("Clicked element. Page: %s (%s)", title, newUrl);
		});
	}

	@Override
	public String fill(String url, String selector, String value) {
		return executeOnPage(url, page -> {
			page.fill(selector, value);
			logger.info("Filled '{}' with value", selector);
			return String.format("Filled element '%s' with value.", selector);
		});
	}

	@Override
	public String evaluate(String url, String script) {
		return executeOnPage(url, page -> {
			Object result = page.evaluate(script);
			String resultStr = result != null ? result.toString() : "null";
			logger.info("Script executed, result: {}", resultStr);
			return resultStr;
		});
	}

	private <T> T executeOnPage(String url, Function<Page, T> operation) {
		Browser browser = null;
		try {
			BrowserType chromium = playwright.chromium();
			browser = chromium.launch(new BrowserType.LaunchOptions().setHeadless(true));

			BrowserContext context = browser.newContext(
					new Browser.NewContextOptions().setViewportSize(config.viewportWidth(), config.viewportHeight()));
			Page page = context.newPage();

			logger.info("Navigating to: {}", url);
			page.navigate(url);
			page.waitForLoadState();

			return operation.apply(page);
		}
		catch (Exception e) {
			logger.error("Local browser operation failed", e);
			throw new BrowserOperationException("Local browser operation failed: " + e.getMessage(), e);
		}
		finally {
			if (browser != null) {
				try {
					browser.close();
				}
				catch (Exception e) {
					logger.warn("Failed to close local browser: {}", e.getMessage());
				}
			}
		}
	}

}
