/*
 * Copyright 2025-2026 the original author or authors.
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

package com.unicorn.browser;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springaicommunity.agentcore.artifacts.ArtifactStore;
import org.springaicommunity.agentcore.artifacts.GeneratedFile;
import org.springaicommunity.agentcore.artifacts.SessionConstants;
import org.springaicommunity.agentcore.browser.BrowserArtifacts;
import org.springaicommunity.agentcore.browser.BrowserTools;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

/**
 * Example demonstrating browser tools with artifact storage.
 * <p>
 * Uses the auto-configured "browserArtifactStore" bean. The artifact store now supports
 * category-based isolation, allowing multiple tools to share a single store while keeping
 * artifacts separate. This example uses the default category.
 */
@SpringBootApplication
public class BrowserExampleApplication {

	private static final Logger logger = LoggerFactory.getLogger(BrowserExampleApplication.class);

	public static void main(String[] args) {
		SpringApplication.run(BrowserExampleApplication.class, args);
	}

	@Bean
	CommandLineRunner run(ChatClient.Builder chatClientBuilder,
			@Qualifier("browserToolCallbackProvider") ToolCallbackProvider browserTools,
			@Qualifier("browserArtifactStore") ArtifactStore<GeneratedFile> artifactStore,
			@Value("${app.url}") String url, @Value("${app.output-dir}") String outputDir) {

		ChatClient chatClient = chatClientBuilder.defaultToolCallbacks(browserTools).build();

		return args -> {
			String sessionId = "example-session";

			// Ask the LLM to browse and take a screenshot
			String prompt = "Browse " + url + " and take a screenshot of it. Tell me what the page is about.";
			logger.info("Prompt: {}", prompt);

			// Use streaming with contextWrite to pass session ID to tools
			String response = chatClient.prompt()
				.user(prompt)
				.stream()
				.content()
				.contextWrite(ctx -> ctx.put(SessionConstants.SESSION_ID_KEY, sessionId))
				.collectList()
				.map(chunks -> String.join("", chunks))
				.block();

			logger.info("Response: {}", response);

			// Retrieve screenshots from artifact store (using default category)
			List<GeneratedFile> screenshots = artifactStore.retrieve(sessionId);
			if (screenshots == null || screenshots.isEmpty()) {
				logger.warn("No screenshots captured");
				return;
			}

			// Save screenshots to disk
			Path dir = Path.of(outputDir);
			Files.createDirectories(dir);

			for (int i = 0; i < screenshots.size(); i++) {
				GeneratedFile screenshot = screenshots.get(i);
				String filename = screenshots.size() == 1 ? "screenshot.png" : "screenshot-" + i + ".png";
				Path file = dir.resolve(filename);
				Files.write(file, screenshot.data());

				String screenshotUrl = BrowserArtifacts.url(screenshot).orElse("unknown");
				logger.info("Screenshot saved: {} ({} bytes) from {}", file.toAbsolutePath(), screenshot.data().length,
						screenshotUrl);
			}

			System.exit(0);
		};
	}

}
