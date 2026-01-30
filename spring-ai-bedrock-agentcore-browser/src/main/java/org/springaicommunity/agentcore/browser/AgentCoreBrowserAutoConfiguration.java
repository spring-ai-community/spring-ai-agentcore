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

import com.microsoft.playwright.Playwright;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.services.bedrockagentcore.BedrockAgentCoreClient;

/**
 * Auto-configuration for AgentCore Browser integration.
 *
 * <p>
 * Creates beans for browser session management and Spring AI tool integration.
 *
 * @author Yuriy Bezsonov
 */
@AutoConfiguration
@ConditionalOnClass(Playwright.class)
@EnableConfigurationProperties(AgentCoreBrowserConfiguration.class)
public class AgentCoreBrowserAutoConfiguration {

	private static final Logger logger = LoggerFactory.getLogger(AgentCoreBrowserAutoConfiguration.class);

	@Bean
	@ConditionalOnMissingBean
	BedrockAgentCoreClient bedrockAgentCoreClient() {
		logger.debug("Creating BedrockAgentCoreClient bean");
		return BedrockAgentCoreClient.create();
	}

	@Bean
	@ConditionalOnMissingBean
	AwsCredentialsProvider awsCredentialsProvider() {
		logger.debug("Creating AwsCredentialsProvider bean");
		return DefaultCredentialsProvider.builder().build();
	}

	@Bean
	@ConditionalOnMissingBean
	AgentCoreBrowserClient agentCoreBrowserClient(BedrockAgentCoreClient client, AgentCoreBrowserConfiguration config,
			AwsCredentialsProvider credentialsProvider) {
		logger.debug("Creating AgentCoreBrowserClient bean");
		return new AgentCoreBrowserClient(client, config, credentialsProvider);
	}

	@Bean
	@ConditionalOnMissingBean
	BrowserScreenshotStore browserScreenshotStore(AgentCoreBrowserConfiguration config) {
		logger.debug("Creating BrowserScreenshotStore bean: ttl={}s", config.screenshotTtlSeconds());
		return new BrowserScreenshotStore(config.screenshotTtlSeconds());
	}

	@Bean
	@ConditionalOnMissingBean
	BrowserTools browserTools(AgentCoreBrowserClient client, BrowserScreenshotStore screenshotStore,
			AgentCoreBrowserConfiguration config) {
		logger.debug("Creating BrowserTools bean");
		return new BrowserTools(client, screenshotStore, config);
	}

	@Bean
	@ConditionalOnMissingBean(name = "browserToolCallbackProvider")
	ToolCallbackProvider browserToolCallbackProvider(BrowserTools tools, AgentCoreBrowserConfiguration config) {
		String browseDesc = getDescription(config.browseUrlDescription(), BrowserTools.BROWSE_URL_DESCRIPTION);
		String screenshotDesc = getDescription(config.screenshotDescription(), BrowserTools.SCREENSHOT_DESCRIPTION);
		String clickDesc = getDescription(config.clickDescription(), BrowserTools.CLICK_DESCRIPTION);
		String fillDesc = getDescription(config.fillDescription(), BrowserTools.FILL_DESCRIPTION);
		String evaluateDesc = getDescription(config.evaluateDescription(), BrowserTools.EVALUATE_DESCRIPTION);

		logger.debug("Creating Browser ToolCallbackProvider with 5 tools");

		ToolCallback browseUrlCallback = FunctionToolCallback
			.builder("browseUrl", (BrowseUrlRequest request) -> tools.browseUrl(request.url()))
			.description(browseDesc)
			.inputType(BrowseUrlRequest.class)
			.build();

		ToolCallback screenshotCallback = FunctionToolCallback
			.builder("takeScreenshot",
					(ScreenshotRequest request, org.springframework.ai.chat.model.ToolContext ctx) -> tools
						.takeScreenshot(request.url(), ctx))
			.description(screenshotDesc)
			.inputType(ScreenshotRequest.class)
			.build();

		ToolCallback clickCallback = FunctionToolCallback
			.builder("clickElement", (ClickRequest request) -> tools.clickElement(request.url(), request.selector()))
			.description(clickDesc)
			.inputType(ClickRequest.class)
			.build();

		ToolCallback fillCallback = FunctionToolCallback
			.builder("fillForm",
					(FillRequest request) -> tools.fillForm(request.url(), request.selector(), request.value()))
			.description(fillDesc)
			.inputType(FillRequest.class)
			.build();

		ToolCallback evaluateCallback = FunctionToolCallback
			.builder("evaluateScript",
					(EvaluateRequest request) -> tools.evaluateScript(request.url(), request.script()))
			.description(evaluateDesc)
			.inputType(EvaluateRequest.class)
			.build();

		return ToolCallbackProvider.from(browseUrlCallback, screenshotCallback, clickCallback, fillCallback,
				evaluateCallback);
	}

	private String getDescription(String custom, String defaultDesc) {
		return (custom != null && !custom.isBlank()) ? custom : defaultDesc;
	}

}
