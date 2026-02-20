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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springaicommunity.agentcore.artifacts.ArtifactStore;
import org.springaicommunity.agentcore.artifacts.CaffeineArtifactStore;
import org.springaicommunity.agentcore.artifacts.GeneratedFile;
import org.springaicommunity.agentcore.artifacts.SessionConstants;
import org.springframework.ai.bedrock.converse.BedrockChatOptions;
import org.springframework.ai.bedrock.converse.BedrockProxyChatModel;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

/**
 * Integration test that verifies session context propagation through the full ChatClient
 * → LLM → Browser tool execution flow.
 *
 * @author Yuriy Bezsonov
 */
@EnabledIfEnvironmentVariable(named = "AGENTCORE_IT", matches = "true")
@SpringBootTest(classes = BrowserChatFlowIT.TestApp.class)
@DisplayName("Browser ChatClient Flow Integration Tests")
class BrowserChatFlowIT {

	@Autowired
	private ChatModel chatModel;

	@Autowired
	private BrowserTools tools;

	@Autowired
	@Qualifier("browserArtifactStore")
	private ArtifactStore<GeneratedFile> artifactStore;

	@Test
	@DisplayName("Should propagate session ID through ChatClient streaming flow for screenshots")
	void shouldPropagateSessionIdThroughChatClientStreamingFlow() {
		String sessionId = "browser-chat-flow-session";

		ToolCallback screenshotCallback = FunctionToolCallback
			.builder("takeScreenshot", (ScreenshotRequest req) -> tools.takeScreenshot(req.url()))
			.description("Take a screenshot of a web page")
			.inputType(ScreenshotRequest.class)
			.build();

		ChatClient chatClient = ChatClient.builder(chatModel)
			.defaultToolCallbacks(ToolCallbackProvider.from(screenshotCallback))
			.defaultSystem("You are a helpful assistant. Use takeScreenshot when asked to capture a web page.")
			.build();

		// Execute chat with streaming - triggers tool execution on boundedElastic thread
		String response = chatClient.prompt()
			.user("Take a screenshot of https://docs.aws.amazon.com")
			.stream()
			.content()
			.contextWrite(ctx -> ctx.put(SessionConstants.SESSION_ID_KEY, sessionId))
			.collectList()
			.map(chunks -> String.join("", chunks))
			.block();

		assertThat(response).isNotNull();

		// Verify screenshot was stored under correct session ID
		assertThat(artifactStore.hasArtifacts(sessionId)).isTrue();

		List<GeneratedFile> screenshots = artifactStore.retrieve(sessionId);
		assertThat(screenshots).hasSize(1);
		assertThat(screenshots.get(0).isImage()).isTrue();
		assertThat(BrowserArtifacts.url(screenshots.get(0))).hasValue("https://docs.aws.amazon.com");
	}

	@Test
	@DisplayName("Should isolate screenshots between different sessions through ChatClient flow")
	void shouldIsolateScreenshotsBetweenSessionsThroughChatClientFlow() {
		String session1 = "browser-isolation-session-1";
		String session2 = "browser-isolation-session-2";

		ToolCallback screenshotCallback = FunctionToolCallback
			.builder("takeScreenshot", (ScreenshotRequest req) -> tools.takeScreenshot(req.url()))
			.description("Take a screenshot of a web page")
			.inputType(ScreenshotRequest.class)
			.build();

		ChatClient chatClient = ChatClient.builder(chatModel)
			.defaultToolCallbacks(ToolCallbackProvider.from(screenshotCallback))
			.defaultSystem("Take screenshots when asked.")
			.build();

		// Session 1: screenshot of docs.aws.amazon.com
		chatClient.prompt()
			.user("Take a screenshot of https://docs.aws.amazon.com")
			.stream()
			.content()
			.contextWrite(ctx -> ctx.put(SessionConstants.SESSION_ID_KEY, session1))
			.collectList()
			.block();

		// Session 2: screenshot of aws.amazon.com
		chatClient.prompt()
			.user("Take a screenshot of https://aws.amazon.com")
			.stream()
			.content()
			.contextWrite(ctx -> ctx.put(SessionConstants.SESSION_ID_KEY, session2))
			.collectList()
			.block();

		// Verify session isolation
		assertThat(artifactStore.hasArtifacts(session1)).isTrue();
		assertThat(artifactStore.hasArtifacts(session2)).isTrue();

		List<GeneratedFile> screenshots1 = artifactStore.retrieve(session1);
		assertThat(screenshots1).hasSize(1);
		assertThat(screenshots1.get(0).isImage()).isTrue();

		List<GeneratedFile> screenshots2 = artifactStore.retrieve(session2);
		assertThat(screenshots2).hasSize(1);
		assertThat(screenshots2.get(0).isImage()).isTrue();
	}

	@SpringBootApplication
	@Import(AgentCoreBrowserTestConfiguration.class)
	static class TestApp {

		@Bean
		ChatModel chatModel() {
			return BedrockProxyChatModel.builder()
				.defaultOptions(
						BedrockChatOptions.builder().model("global.anthropic.claude-sonnet-4-5-20250929-v1:0").build())
				.build();
		}

		@Bean
		ArtifactStore<GeneratedFile> browserArtifactStore() {
			return new CaffeineArtifactStore<>(300, "BrowserArtifactStore");
		}

		@Bean
		BrowserTools browserTools(BrowserClient client, ArtifactStore<GeneratedFile> browserArtifactStore,
				AgentCoreBrowserConfiguration config) {
			return new BrowserTools(client, browserArtifactStore, config);
		}

	}

}
