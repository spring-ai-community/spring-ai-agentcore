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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.microsoft.playwright.Playwright;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * Tests for {@link LocalBrowserClient} using a locally launched Chromium.
 *
 * @author Yuriy Bezsonov
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("LocalBrowserClient Tests")
class LocalBrowserClientTest {

	private static Playwright playwright;

	private static LocalBrowserClient client;

	@BeforeAll
	static void setUp() {
		playwright = Playwright.create();
		AgentCoreBrowserConfiguration config = new AgentCoreBrowserConfiguration(
				AgentCoreBrowserConfiguration.MODE_LOCAL, null, null, null, null, null, null, null, null, null, null,
				null, null);
		client = new LocalBrowserClient(playwright, config);
	}

	@AfterAll
	static void tearDown() {
		if (playwright != null) {
			playwright.close();
		}
	}

	@Test
	@Order(1)
	@DisplayName("Should browse URL and extract content")
	void shouldBrowseAndExtract() {
		String result = client.browseAndExtract("https://example.com");

		assertThat(result).contains("Title:");
		assertThat(result).containsIgnoringCase("example");
	}

	@Test
	@Order(2)
	@DisplayName("Should take screenshot and return PNG bytes")
	void shouldTakeScreenshot() {
		byte[] bytes = client.screenshotBytes("https://example.com");

		assertThat(bytes).isNotEmpty();
		// PNG magic bytes
		assertThat(bytes[0]).isEqualTo((byte) 0x89);
		assertThat(bytes[1]).isEqualTo((byte) 0x50); // 'P'
		assertThat(bytes[2]).isEqualTo((byte) 0x4E); // 'N'
		assertThat(bytes[3]).isEqualTo((byte) 0x47); // 'G'
	}

	@Test
	@Order(3)
	@DisplayName("Should click element on page")
	void shouldClickElement() {
		String result = client.click("https://example.com", "a");

		assertThat(result).containsIgnoringCase("clicked");
	}

	@Test
	@Order(4)
	@DisplayName("Should fill form field")
	void shouldFillFormField() {
		String result = client.fill("https://duckduckgo.com", "input[name='q']", "test query");

		assertThat(result).containsIgnoringCase("filled");
	}

	@Test
	@Order(5)
	@DisplayName("Should evaluate JavaScript")
	void shouldEvaluateScript() {
		String result = client.evaluate("https://example.com", "document.title");

		assertThat(result).containsIgnoringCase("example");
	}

	@Test
	@Order(6)
	@DisplayName("Should throw BrowserOperationException for invalid URL")
	void shouldThrowForInvalidUrl() {
		assertThatThrownBy(() -> client.browseAndExtract("https://this-domain-does-not-exist-12345.com"))
			.isInstanceOf(BrowserOperationException.class);
	}

	@Test
	@Order(7)
	@DisplayName("Should truncate content exceeding max length")
	void shouldTruncateContent() {
		AgentCoreBrowserConfiguration smallConfig = new AgentCoreBrowserConfiguration(
				AgentCoreBrowserConfiguration.MODE_LOCAL, null, null, null, null, 50, null, null, null, null, null,
				null, null);
		LocalBrowserClient smallClient = new LocalBrowserClient(playwright, smallConfig);

		String result = smallClient.browseAndExtract("https://example.com");

		assertThat(result).contains("[truncated]");
	}

	@Test
	@Order(8)
	@DisplayName("Should implement BrowserClient interface")
	void shouldImplementBrowserClientInterface() {
		assertThat(client).isInstanceOf(BrowserClient.class);
	}

}
