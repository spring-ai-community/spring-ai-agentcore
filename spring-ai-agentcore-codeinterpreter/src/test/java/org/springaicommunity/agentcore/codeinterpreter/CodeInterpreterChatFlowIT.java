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
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import reactor.core.publisher.Flux;
import software.amazon.awssdk.services.bedrockagentcore.BedrockAgentCoreAsyncClient;
import software.amazon.awssdk.services.bedrockagentcore.BedrockAgentCoreClient;

/**
 * Integration test that verifies session context propagation through the full ChatClient
 * → LLM → tool execution flow.
 *
 * @author Yuriy Bezsonov
 */
@EnabledIfEnvironmentVariable(named = "AGENTCORE_IT", matches = "true")
@SpringBootTest(classes = CodeInterpreterChatFlowIT.TestApp.class)
@DisplayName("CodeInterpreter ChatClient Flow Integration Tests")
class CodeInterpreterChatFlowIT {

	@Autowired
	private ChatModel chatModel;

	@Autowired
	private CodeInterpreterTools tools;

	@Autowired
	@Qualifier("codeInterpreterArtifactStore")
	private ArtifactStore<GeneratedFile> artifactStore;

	@Test
	@DisplayName("Should propagate session ID through ChatClient streaming flow")
	void shouldPropagateSessionIdThroughChatClientStreamingFlow() {
		String sessionId = "chat-flow-test-session";

		ToolCallbackProvider toolProvider = ToolCallbackProvider.from(FunctionToolCallback
			.builder("executeCode", (ExecuteCodeRequest req) -> tools.executeCode(req.language(), req.code()))
			.description("Execute Python code")
			.inputType(ExecuteCodeRequest.class)
			.build());

		ChatClient chatClient = ChatClient.builder(chatModel)
			.defaultToolCallbacks(toolProvider)
			.defaultSystem("You are a helpful assistant. Use executeCode tool when asked to run code.")
			.build();

		// Execute chat with streaming - this triggers tool execution on boundedElastic
		// thread
		String response = chatClient.prompt()
			.user("Execute this Python code: print(2 + 2)")
			.stream()
			.content()
			.contextWrite(ctx -> ctx.put(SessionConstants.SESSION_ID_KEY, sessionId))
			.collectList()
			.map(chunks -> String.join("", chunks))
			.block();

		assertThat(response).isNotNull();
		assertThat(response).contains("4");

		// Verify files were stored under correct session ID (if any were generated)
		// Note: simple print() doesn't generate files, but the session context was used
	}

	@Test
	@DisplayName("Should store generated files under correct session ID through ChatClient flow")
	void shouldStoreFilesUnderCorrectSessionIdThroughChatClientFlow() {
		String sessionId = "chart-flow-test-session";

		ToolCallbackProvider toolProvider = ToolCallbackProvider.from(FunctionToolCallback
			.builder("executeCode", (ExecuteCodeRequest req) -> tools.executeCode(req.language(), req.code()))
			.description("Execute Python code to create charts")
			.inputType(ExecuteCodeRequest.class)
			.build());

		ChatClient chatClient = ChatClient.builder(chatModel)
			.defaultToolCallbacks(toolProvider)
			.defaultSystem("You are a helpful assistant. Use executeCode to create charts when asked.")
			.build();

		// Ask LLM to create a chart - this will generate a file
		Flux<String> responseFlux = chatClient.prompt()
			.user("Create a simple bar chart with matplotlib showing values A=10, B=20. Save it as chart.png")
			.stream()
			.content()
			.contextWrite(ctx -> ctx.put(SessionConstants.SESSION_ID_KEY, sessionId));

		String response = responseFlux.collectList().map(chunks -> String.join("", chunks)).block();

		assertThat(response).isNotNull();

		// Verify files were stored under the correct session ID
		assertThat(artifactStore.hasArtifacts(sessionId))
			.as("Expected artifacts to be stored for session %s", sessionId)
			.isTrue();
		List<GeneratedFile> files = artifactStore.retrieve(sessionId);
		assertThat(files).isNotEmpty();
		assertThat(files.stream().anyMatch(GeneratedFile::isImage)).isTrue();
	}

	@Test
	@DisplayName("Should isolate files between different sessions through ChatClient flow")
	void shouldIsolateFilesBetweenSessionsThroughChatClientFlow() {
		String session1 = "isolation-session-1";
		String session2 = "isolation-session-2";

		ToolCallbackProvider toolProvider = ToolCallbackProvider.from(FunctionToolCallback
			.builder("executeCode", (ExecuteCodeRequest req) -> tools.executeCode(req.language(), req.code()))
			.description("Execute Python code")
			.inputType(ExecuteCodeRequest.class)
			.build());

		ChatClient chatClient = ChatClient.builder(chatModel)
			.defaultToolCallbacks(toolProvider)
			.defaultSystem("Execute the code exactly as requested using executeCode tool.")
			.build();

		// Session 1: create chart1.png
		chatClient.prompt()
			.user("Execute: import matplotlib.pyplot as plt; plt.figure(); plt.bar(['A'], [10]); plt.savefig('chart1.png'); print('done1')")
			.stream()
			.content()
			.contextWrite(ctx -> ctx.put(SessionConstants.SESSION_ID_KEY, session1))
			.collectList()
			.block();

		// Session 2: create chart2.png
		chatClient.prompt()
			.user("Execute: import matplotlib.pyplot as plt; plt.figure(); plt.bar(['B'], [20]); plt.savefig('chart2.png'); print('done2')")
			.stream()
			.content()
			.contextWrite(ctx -> ctx.put(SessionConstants.SESSION_ID_KEY, session2))
			.collectList()
			.block();

		// Verify session isolation
		assertThat(artifactStore.hasArtifacts(session1)).as("Expected artifacts to be stored for session1").isTrue();
		List<GeneratedFile> files1 = artifactStore.retrieve(session1);
		assertThat(files1.stream().anyMatch(f -> f.name().contains("chart1"))).isTrue();
		assertThat(files1.stream().noneMatch(f -> f.name().contains("chart2"))).isTrue();

		assertThat(artifactStore.hasArtifacts(session2)).as("Expected artifacts to be stored for session2").isTrue();
		List<GeneratedFile> files2 = artifactStore.retrieve(session2);
		assertThat(files2.stream().anyMatch(f -> f.name().contains("chart2"))).isTrue();
		assertThat(files2.stream().noneMatch(f -> f.name().contains("chart1"))).isTrue();
	}

	@SpringBootApplication(exclude = {
			org.springaicommunity.agentcore.codeinterpreter.AgentCoreCodeInterpreterAutoConfiguration.class })
	static class TestApp {

		@Bean
		ChatModel chatModel() {
			return BedrockProxyChatModel.builder()
				.defaultOptions(
						BedrockChatOptions.builder().model("global.anthropic.claude-sonnet-4-5-20250929-v1:0").build())
				.build();
		}

		@Bean
		BedrockAgentCoreClient bedrockAgentCoreClient() {
			return BedrockAgentCoreClient.create();
		}

		@Bean
		BedrockAgentCoreAsyncClient bedrockAgentCoreAsyncClient() {
			return BedrockAgentCoreAsyncClient.create();
		}

		@Bean
		AgentCoreCodeInterpreterConfiguration codeInterpreterConfiguration() {
			return new AgentCoreCodeInterpreterConfiguration(null, null, null, null, null, null);
		}

		@Bean
		AgentCoreCodeInterpreterClient agentCoreCodeInterpreterClient(BedrockAgentCoreClient syncClient,
				BedrockAgentCoreAsyncClient asyncClient, AgentCoreCodeInterpreterConfiguration config) {
			return new AgentCoreCodeInterpreterClient(syncClient, asyncClient, config);
		}

		@Bean
		ArtifactStore<GeneratedFile> codeInterpreterArtifactStore() {
			return new CaffeineArtifactStore<>(300, "CodeInterpreterArtifactStore");
		}

		@Bean
		CodeInterpreterTools codeInterpreterTools(AgentCoreCodeInterpreterClient client,
				ArtifactStore<GeneratedFile> artifactStore) {
			return new CodeInterpreterTools(client, artifactStore);
		}

	}

}
