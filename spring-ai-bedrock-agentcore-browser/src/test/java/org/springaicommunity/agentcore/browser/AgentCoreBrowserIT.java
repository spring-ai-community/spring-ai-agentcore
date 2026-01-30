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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;

import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.services.bedrockagentcore.BedrockAgentCoreClient;

/**
 * Integration test for AgentCore Browser.
 *
 * <p>
 * Tests browser session management and page content extraction through the client.
 *
 * <p>
 * Requires: AGENTCORE_IT=true and AWS credentials.
 *
 * @author Yuriy Bezsonov
 */
@EnabledIfEnvironmentVariable(named = "AGENTCORE_IT", matches = "true")
@SpringBootTest(classes = AgentCoreBrowserIT.TestApp.class,
		properties = { "spring.autoconfigure.exclude="
				+ "org.springaicommunity.agentcore.browser.AgentCoreBrowserAutoConfiguration" })
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("AgentCore Browser Integration Tests")
class AgentCoreBrowserIT {

	private static final String BOLD = "\033[1m";

	private static final String RESET = "\033[0m";

	@Autowired
	private AgentCoreBrowserClient client;

	@Test
	@Order(1)
	@DisplayName("Should browse simple HTML page and extract content")
	void shouldBrowseSimplePage() {
		System.out.println(BOLD + "\n----- Simple Page Test -----" + RESET);

		String result = client.browseAndExtract("https://example.com");

		System.out.println(BOLD + "Result length: " + RESET + result.length() + " chars");
		System.out.println(BOLD + "Content preview: " + RESET + result.substring(0, Math.min(500, result.length())));

		assertThat(result).contains("Title:");
		assertThat(result).containsIgnoringCase("example");

		System.out.println(BOLD + "----------------------------" + RESET + "\n");
	}

	@Test
	@Order(2)
	@DisplayName("Should browse AWS documentation page")
	void shouldBrowseAwsDocsPage() {
		System.out.println(BOLD + "\n----- AWS Docs Page Test -----" + RESET);

		String result = client.browseAndExtract("https://docs.aws.amazon.com/bedrock/");

		System.out.println(BOLD + "Result length: " + RESET + result.length() + " chars");
		System.out.println(BOLD + "Content preview: " + RESET + result.substring(0, Math.min(500, result.length())));

		assertThat(result).contains("Title:");
		assertThat(result).containsIgnoringCase("bedrock");

		System.out.println(BOLD + "------------------------------" + RESET + "\n");
	}

	@Test
	@Order(3)
	@DisplayName("Should handle invalid URL gracefully")
	void shouldHandleInvalidUrl() {
		System.out.println(BOLD + "\n----- Invalid URL Test -----" + RESET);

		assertThatThrownBy(() -> client.browseAndExtract("https://this-domain-does-not-exist-12345.com"))
			.isInstanceOf(BrowserOperationException.class)
			.hasMessageContaining("ERR_NAME_NOT_RESOLVED");

		System.out.println(BOLD + "Exception thrown as expected" + RESET);
		System.out.println(BOLD + "----------------------------" + RESET + "\n");
	}

	@Test
	@Order(4)
	@DisplayName("Should truncate long page content")
	void shouldTruncateLongContent() {
		System.out.println(BOLD + "\n----- Long Content Test -----" + RESET);

		// Wikipedia pages are typically long
		String result = client.browseAndExtract("https://en.wikipedia.org/wiki/Amazon_Web_Services");

		System.out.println(BOLD + "Result length: " + RESET + result.length() + " chars");

		// Content should be truncated to ~10000 chars + title/formatting
		assertThat(result.length()).isLessThan(12000);
		assertThat(result).contains("Title:");

		if (result.contains("[truncated]")) {
			System.out.println(BOLD + "Content was truncated as expected" + RESET);
		}

		System.out.println(BOLD + "-----------------------------" + RESET + "\n");
	}

	@Test
	@Order(5)
	@DisplayName("Should take screenshot and return raw bytes")
	void shouldTakeScreenshotBytes() {
		System.out.println(BOLD + "\n----- Screenshot Bytes Test -----" + RESET);

		byte[] result = client.screenshotBytes("https://example.com");

		System.out.println(BOLD + "Result: " + RESET + (result != null ? result.length + " bytes" : "null"));

		assertThat(result).isNotNull();
		assertThat(result.length).isGreaterThan(1000);

		// Verify PNG magic bytes
		assertThat(result[0]).isEqualTo((byte) 0x89);
		assertThat(result[1]).isEqualTo((byte) 0x50); // P
		assertThat(result[2]).isEqualTo((byte) 0x4E); // N
		assertThat(result[3]).isEqualTo((byte) 0x47); // G

		System.out.println(BOLD + "---------------------------------" + RESET + "\n");
	}

	@Test
	@Order(6)
	@DisplayName("Should store screenshot in BrowserScreenshotStore")
	void shouldStoreScreenshot() {
		System.out.println(BOLD + "\n----- Screenshot Store Test -----" + RESET);

		BrowserScreenshotStore store = new BrowserScreenshotStore(300);
		AgentCoreBrowserConfiguration config = new AgentCoreBrowserConfiguration(null, null, null, null, null, null,
				null, null, null, null, null);
		BrowserTools tools = new BrowserTools(client, store, config);

		// Take screenshot (stores in store)
		String result = tools.takeScreenshot("https://example.com", null);

		System.out.println(BOLD + "Tool result: " + RESET + result);

		assertThat(result).contains("Screenshot captured:");
		assertThat(result).contains("bytes");
		assertThat(result).contains("example.com");

		// Verify screenshot is in store
		assertThat(store.hasScreenshots(null)).isTrue();

		// Retrieve and verify
		var screenshots = store.retrieve(null);
		assertThat(screenshots).hasSize(1);

		BrowserScreenshot screenshot = screenshots.get(0);
		assertThat(screenshot.url()).isEqualTo("https://example.com");
		assertThat(screenshot.size()).isGreaterThan(1000);
		assertThat(screenshot.mimeType()).isEqualTo("image/png");

		System.out.println(BOLD + "Screenshot stored: " + RESET + screenshot.size() + " bytes, " + screenshot.width()
				+ "x" + screenshot.height());

		// Store should be empty after retrieve
		assertThat(store.hasScreenshots(null)).isFalse();

		System.out.println(BOLD + "---------------------------------" + RESET + "\n");
	}

	@Test
	@Order(7)
	@DisplayName("Should click element on page")
	void shouldClickElement() {
		System.out.println(BOLD + "\n----- Click Test -----" + RESET);

		// Click the "More information..." link on example.com
		String result = client.click("https://example.com", "a");

		System.out.println(BOLD + "Result: " + RESET + result);

		assertThat(result).containsIgnoringCase("clicked");

		System.out.println(BOLD + "----------------------" + RESET + "\n");
	}

	@Test
	@Order(8)
	@DisplayName("Should evaluate JavaScript on page")
	void shouldEvaluateScript() {
		System.out.println(BOLD + "\n----- Evaluate Script Test -----" + RESET);

		String result = client.evaluate("https://example.com", "document.title");

		System.out.println(BOLD + "Result: " + RESET + result);

		assertThat(result).containsIgnoringCase("example");

		System.out.println(BOLD + "--------------------------------" + RESET + "\n");
	}

	@Test
	@Order(9)
	@DisplayName("Should fill form field")
	void shouldFillFormField() {
		System.out.println(BOLD + "\n----- Fill Form Test -----" + RESET);

		// Use DuckDuckGo search which has a simple form
		String result = client.fill("https://duckduckgo.com", "input[name='q']", "test query");

		System.out.println(BOLD + "Result: " + RESET + result);

		assertThat(result).containsIgnoringCase("filled");

		System.out.println(BOLD + "--------------------------" + RESET + "\n");
	}

	@SpringBootApplication(
			exclude = { org.springaicommunity.agentcore.browser.AgentCoreBrowserAutoConfiguration.class })
	static class TestApp {

		@Bean
		BedrockAgentCoreClient bedrockAgentCoreClient() {
			return BedrockAgentCoreClient.create();
		}

		@Bean
		AwsCredentialsProvider awsCredentialsProvider() {
			return DefaultCredentialsProvider.builder().build();
		}

		@Bean
		AgentCoreBrowserConfiguration browserConfiguration() {
			return new AgentCoreBrowserConfiguration(null, null, null, null, null, null, null, null, null, null, null);
		}

		@Bean
		AgentCoreBrowserClient agentCoreBrowserClient(BedrockAgentCoreClient client,
				AgentCoreBrowserConfiguration config, AwsCredentialsProvider credentialsProvider) {
			return new AgentCoreBrowserClient(client, config, credentialsProvider);
		}

	}

}
