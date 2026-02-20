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

package org.springaicommunity.agentcore.codeinterpreter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springaicommunity.agentcore.artifacts.ArtifactStore;
import org.springaicommunity.agentcore.artifacts.ArtifactStoreFactory;
import org.springaicommunity.agentcore.artifacts.CaffeineArtifactStoreFactory;
import org.springaicommunity.agentcore.artifacts.GeneratedFile;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import software.amazon.awssdk.services.bedrockagentcore.BedrockAgentCoreAsyncClient;
import software.amazon.awssdk.services.bedrockagentcore.BedrockAgentCoreClient;

/**
 * Auto-configuration for AgentCore Code Interpreter.
 *
 * @author Yuriy Bezsonov
 */
@AutoConfiguration
@EnableConfigurationProperties(AgentCoreCodeInterpreterConfiguration.class)
public class AgentCoreCodeInterpreterAutoConfiguration {

	private static final Logger logger = LoggerFactory.getLogger(AgentCoreCodeInterpreterAutoConfiguration.class);

	@Bean
	@ConditionalOnMissingBean
	BedrockAgentCoreClient bedrockAgentCoreClient() {
		logger.debug("Creating BedrockAgentCoreClient bean");
		return BedrockAgentCoreClient.create();
	}

	@Bean
	@ConditionalOnMissingBean
	BedrockAgentCoreAsyncClient bedrockAgentCoreAsyncClient() {
		logger.debug("Creating BedrockAgentCoreAsyncClient bean");
		return BedrockAgentCoreAsyncClient.create();
	}

	@Bean
	@ConditionalOnMissingBean
	AgentCoreCodeInterpreterClient agentCoreCodeInterpreterClient(BedrockAgentCoreClient syncClient,
			BedrockAgentCoreAsyncClient asyncClient, AgentCoreCodeInterpreterConfiguration config) {
		logger.debug("Creating AgentCoreCodeInterpreterClient bean");
		return new AgentCoreCodeInterpreterClient(syncClient, asyncClient, config);
	}

	@Bean
	@ConditionalOnMissingBean
	ArtifactStoreFactory artifactStoreFactory() {
		logger.debug("Creating ArtifactStoreFactory bean");
		return new CaffeineArtifactStoreFactory();
	}

	@Bean
	@ConditionalOnMissingBean(name = "codeInterpreterArtifactStore")
	ArtifactStore<GeneratedFile> codeInterpreterArtifactStore(ArtifactStoreFactory factory,
			AgentCoreCodeInterpreterConfiguration config) {
		logger.debug("Creating codeInterpreterArtifactStore bean: ttl={}s, maxSize={}", config.fileStoreTtlSeconds(),
				config.artifactStoreMaxSize());
		return factory.create("CodeInterpreterArtifactStore", config.fileStoreTtlSeconds(),
				config.artifactStoreMaxSize());
	}

	@Bean
	@ConditionalOnMissingBean
	CodeInterpreterTools codeInterpreterTools(AgentCoreCodeInterpreterClient client,
			@Qualifier("codeInterpreterArtifactStore") ArtifactStore<GeneratedFile> codeInterpreterArtifactStore) {
		logger.debug("Creating CodeInterpreterTools bean");
		return new CodeInterpreterTools(client, codeInterpreterArtifactStore);
	}

	@Bean
	@ConditionalOnMissingBean(name = "codeInterpreterToolCallbackProvider")
	ToolCallbackProvider codeInterpreterToolCallbackProvider(CodeInterpreterTools tools,
			AgentCoreCodeInterpreterConfiguration config) {
		String description = config.toolDescription() != null && !config.toolDescription().isBlank()
				? config.toolDescription() : CodeInterpreterTools.DEFAULT_TOOL_DESCRIPTION;

		logger.debug("Creating CodeInterpreter ToolCallbackProvider with description: {}...",
				description.substring(0, Math.min(50, description.length())));

		ToolCallback executeCodeCallback = FunctionToolCallback
			.builder("executeCode",
					(ExecuteCodeRequest request) -> tools.executeCode(request.language(), request.code()))
			.description(description)
			.inputType(ExecuteCodeRequest.class)
			.build();

		return ToolCallbackProvider.from(executeCodeCallback);
	}

}
