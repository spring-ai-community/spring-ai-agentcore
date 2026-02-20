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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springaicommunity.agentcore.artifacts.ArtifactStore;
import org.springaicommunity.agentcore.artifacts.GeneratedFile;
import org.springaicommunity.agentcore.artifacts.SessionConstants;
import org.springframework.ai.model.tool.internal.ToolCallReactiveContextHolder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import reactor.util.context.Context;

/**
 * Integration test verifying auto-configuration wiring and screenshot storage with
 * {@code agentcore.browser.mode=local}. Lets {@link AgentCoreBrowserAutoConfiguration}
 * create all beans — no manual wiring. Browser operation behavior is covered by
 * {@link LocalBrowserClientTest}.
 *
 * @author Yuriy Bezsonov
 */
@SpringBootTest(classes = LocalBrowserToolsIT.TestApp.class, properties = "agentcore.browser.mode=local")
@DisplayName("Local Browser Tools Wiring Integration Tests")
class LocalBrowserToolsIT {

	@Autowired
	private BrowserClient client;

	@Autowired
	@Qualifier("browserArtifactStore")
	private ArtifactStore<GeneratedFile> artifactStore;

	@Autowired
	private BrowserTools tools;

	@AfterEach
	void tearDown() {
		ToolCallReactiveContextHolder.clearContext();
	}

	@Test
	@DisplayName("Should wire LocalBrowserClient when mode is local")
	void shouldWireLocalBrowserClient() {
		assertThat(client).isInstanceOf(LocalBrowserClient.class);
	}

	@Test
	@DisplayName("Should store screenshot under session ID via BrowserTools")
	void shouldStoreScreenshotUnderSessionId() {
		String sessionId = "local-wiring-session";
		ToolCallReactiveContextHolder.setContext(Context.of(SessionConstants.SESSION_ID_KEY, sessionId));

		String result = tools.takeScreenshot("https://example.com");

		assertThat(result).contains("Screenshot captured:");
		assertThat(artifactStore.hasArtifacts(sessionId)).isTrue();

		List<GeneratedFile> screenshots = artifactStore.retrieve(sessionId);
		assertThat(screenshots).hasSize(1);
		assertThat(screenshots.get(0).isImage()).isTrue();
		assertThat(artifactStore.hasArtifacts(sessionId)).isFalse();
	}

	@SpringBootApplication
	static class TestApp {

	}

}
