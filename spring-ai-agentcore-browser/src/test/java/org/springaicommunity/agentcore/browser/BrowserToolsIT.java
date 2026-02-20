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

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springaicommunity.agentcore.artifacts.ArtifactStore;
import org.springaicommunity.agentcore.artifacts.CaffeineArtifactStore;
import org.springaicommunity.agentcore.artifacts.GeneratedFile;
import org.springaicommunity.agentcore.artifacts.SessionConstants;
import org.springframework.ai.model.tool.internal.ToolCallReactiveContextHolder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import reactor.util.context.Context;

/**
 * Integration tests for BrowserTools.
 *
 * @author Yuriy Bezsonov
 */
@EnabledIfEnvironmentVariable(named = "AGENTCORE_IT", matches = "true")
@SpringBootTest(classes = BrowserToolsIT.TestApp.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("BrowserTools Integration Tests")
class BrowserToolsIT {

	@Autowired
	private AgentCoreBrowserClient client;

	@Autowired
	private AgentCoreBrowserConfiguration config;

	private ArtifactStore<GeneratedFile> store;

	private BrowserTools tools;

	@BeforeEach
	void setUp() {
		store = new CaffeineArtifactStore<>(300, "BrowserArtifactStore");
		tools = new BrowserTools(client, store, config);
	}

	@AfterEach
	void tearDown() {
		ToolCallReactiveContextHolder.clearContext();
	}

	private void setSessionId(String sessionId) {
		Context ctx = sessionId != null ? Context.of(SessionConstants.SESSION_ID_KEY, sessionId) : Context.empty();
		ToolCallReactiveContextHolder.setContext(ctx);
	}

	// ========== browseUrl tests ==========

	@Test
	@Order(1)
	@DisplayName("Should browse URL and return content")
	void shouldBrowseUrlAndReturnContent() {
		String result = tools.browseUrl("https://docs.aws.amazon.com");

		assertThat(result).contains("Title:");
		assertThat(result).containsIgnoringCase("aws");
	}

	@Test
	@Order(2)
	@DisplayName("Should return error for invalid URL")
	void shouldBrowseUrlReturnErrorForInvalidUrl() {
		String result = tools.browseUrl("https://this-domain-does-not-exist-12345.com");

		assertThat(result).startsWith("Error:");
	}

	// ========== takeScreenshot tests ==========

	@Test
	@Order(3)
	@DisplayName("Should take screenshot and store by session ID")
	void shouldTakeScreenshotAndStoreBySessionId() {
		setSessionId("session-A");

		String result = tools.takeScreenshot("https://docs.aws.amazon.com");

		assertThat(result).contains("Screenshot captured:");
		assertThat(result).contains("bytes");
		assertThat(store.hasArtifacts("session-A")).isTrue();
	}

	@Test
	@Order(4)
	@DisplayName("Should use default session when null")
	void shouldTakeScreenshotUseDefaultSessionWhenNull() {
		setSessionId(null);

		String result = tools.takeScreenshot("https://docs.aws.amazon.com");

		assertThat(result).contains("Screenshot captured:");
		assertThat(store.hasArtifacts(SessionConstants.DEFAULT_SESSION_ID)).isTrue();
	}

	@Test
	@Order(5)
	@DisplayName("Should return error on screenshot failure")
	void shouldTakeScreenshotReturnErrorOnFailure() {
		String result = tools.takeScreenshot("https://this-domain-does-not-exist-12345.com");

		assertThat(result).startsWith("Error:");
	}

	// ========== Session isolation tests ==========

	@Test
	@Order(6)
	@DisplayName("Should retrieve screenshot only from own session")
	void shouldRetrieveScreenshotOnlyFromOwnSession() {
		setSessionId("session-X");
		tools.takeScreenshot("https://docs.aws.amazon.com");

		setSessionId("session-Y");
		tools.takeScreenshot("https://docs.aws.amazon.com");

		List<GeneratedFile> xScreenshots = store.retrieve("session-X");
		List<GeneratedFile> yScreenshots = store.retrieve("session-Y");

		assertThat(xScreenshots).hasSize(1);
		assertThat(yScreenshots).hasSize(1);
		assertThat(store.hasArtifacts("session-X")).isFalse();
		assertThat(store.hasArtifacts("session-Y")).isFalse();
	}

	@Test
	@Order(7)
	@DisplayName("Should clear screenshots after retrieve")
	void shouldClearScreenshotsAfterRetrieve() {
		setSessionId("session-clear");
		tools.takeScreenshot("https://docs.aws.amazon.com");

		assertThat(store.hasArtifacts("session-clear")).isTrue();
		store.retrieve("session-clear");
		assertThat(store.hasArtifacts("session-clear")).isFalse();
		assertThat(store.retrieve("session-clear")).isNull();
	}

	@Test
	@Order(8)
	@DisplayName("Should hasArtifacts return correctly")
	void shouldHasArtifactsReturnCorrectly() {
		assertThat(store.hasArtifacts("nonexistent")).isFalse();

		setSessionId("session-has");
		tools.takeScreenshot("https://docs.aws.amazon.com");

		assertThat(store.hasArtifacts("session-has")).isTrue();
	}

	@Test
	@Order(9)
	@DisplayName("Should screenshot toDataUrl return valid format")
	void shouldScreenshotToDataUrlReturnValidFormat() {
		setSessionId("session-dataurl");
		tools.takeScreenshot("https://docs.aws.amazon.com");

		List<GeneratedFile> screenshots = store.retrieve("session-dataurl");
		assertThat(screenshots).hasSize(1);

		GeneratedFile screenshot = screenshots.get(0);
		String dataUrl = screenshot.toDataUrl();

		assertThat(dataUrl).startsWith("data:image/png;base64,");
		assertThat(screenshot.mimeType()).isEqualTo("image/png");
		assertThat(screenshot.size()).isGreaterThan(0);
	}

	// ========== clickElement tests ==========

	@Test
	@Order(10)
	@DisplayName("Should click element")
	void shouldClickElement() {
		// Use httpbin which has a simple, reliable link structure
		String result = tools.clickElement("https://httpbin.org", "a[href='/forms/post']");

		assertThat(result).containsIgnoringCase("clicked");
	}

	@Test
	@Order(11)
	@DisplayName("Should return error on click failure")
	void shouldClickElementReturnErrorOnFailure() {
		String result = tools.clickElement("https://this-domain-does-not-exist-12345.com", "a");

		assertThat(result).startsWith("Error:");
	}

	// ========== fillForm tests ==========

	@Test
	@Order(12)
	@DisplayName("Should fill form")
	void shouldFillForm() {
		String result = tools.fillForm("https://duckduckgo.com", "input[name='q']", "test query");

		assertThat(result).containsIgnoringCase("filled");
	}

	@Test
	@Order(13)
	@DisplayName("Should return error on fill failure")
	void shouldFillFormReturnErrorOnFailure() {
		String result = tools.fillForm("https://this-domain-does-not-exist-12345.com", "input", "value");

		assertThat(result).startsWith("Error:");
	}

	// ========== evaluateScript tests ==========

	@Test
	@Order(14)
	@DisplayName("Should evaluate script")
	void shouldEvaluateScript() {
		String result = tools.evaluateScript("https://docs.aws.amazon.com", "document.title");

		assertThat(result).containsIgnoringCase("aws");
	}

	@Test
	@Order(15)
	@DisplayName("Should return error on evaluate failure")
	void shouldEvaluateScriptReturnErrorOnFailure() {
		String result = tools.evaluateScript("https://this-domain-does-not-exist-12345.com", "document.title");

		assertThat(result).startsWith("Error:");
	}

	// ========== Parallel session isolation test ==========

	@Test
	@Order(16)
	@DisplayName("Should isolate screenshots between concurrent sessions using shared store")
	void shouldIsolateScreenshotsBetweenParallelSessions() {
		ArtifactStore<GeneratedFile> sharedStore = new CaffeineArtifactStore<>(300, "SharedBrowserArtifactStore");

		// Session 1: takes screenshot
		ToolCallReactiveContextHolder.setContext(Context.of(SessionConstants.SESSION_ID_KEY, "concurrent-session-1"));
		BrowserTools tools1 = new BrowserTools(client, sharedStore, config);
		tools1.takeScreenshot("https://docs.aws.amazon.com");
		ToolCallReactiveContextHolder.clearContext();

		// Session 2: takes screenshot (before session 1 retrieves)
		ToolCallReactiveContextHolder.setContext(Context.of(SessionConstants.SESSION_ID_KEY, "concurrent-session-2"));
		BrowserTools tools2 = new BrowserTools(client, sharedStore, config);
		tools2.takeScreenshot("https://aws.amazon.com");
		ToolCallReactiveContextHolder.clearContext();

		// Both sessions have screenshots in shared store
		assertThat(sharedStore.hasArtifacts("concurrent-session-1")).isTrue();
		assertThat(sharedStore.hasArtifacts("concurrent-session-2")).isTrue();

		// Session 1 retrieves - should only get its own
		List<GeneratedFile> session1Screenshots = sharedStore.retrieve("concurrent-session-1");
		assertThat(session1Screenshots).hasSize(1);
		assertThat(session1Screenshots.get(0).isImage()).isTrue();

		// Session 2 retrieves - should only get its own
		List<GeneratedFile> session2Screenshots = sharedStore.retrieve("concurrent-session-2");
		assertThat(session2Screenshots).hasSize(1);
		assertThat(session2Screenshots.get(0).isImage()).isTrue();

		// Store is empty for both
		assertThat(sharedStore.hasArtifacts("concurrent-session-1")).isFalse();
		assertThat(sharedStore.hasArtifacts("concurrent-session-2")).isFalse();
	}

	@SpringBootApplication
	@Import(AgentCoreBrowserTestConfiguration.class)
	static class TestApp {

	}

}
