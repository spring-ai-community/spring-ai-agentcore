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

package org.springaicommunity.agentcore.memory;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.context.ContextConfiguration;
import software.amazon.awssdk.services.bedrockagentcorecontrol.BedrockAgentCoreControlClient;
import software.amazon.awssdk.services.bedrockagentcorecontrol.model.CreateMemoryRequest;
import software.amazon.awssdk.services.bedrockagentcorecontrol.model.DeleteMemoryRequest;
import software.amazon.awssdk.services.bedrockagentcorecontrol.model.GetMemoryRequest;
import software.amazon.awssdk.services.bedrockagentcorecontrol.model.MemoryStatus;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@Tag("integration")
@DisplayName("End-to-End Spring AI Chat Client Test - requires AWS account and creates AgentCore memory (2-3 minutes)")
@SpringBootTest(classes = EndToEndSpringAIChatClientIntegrationTest.TestApp.class,
		properties = { "spring.ai.bedrock.converse.chat.options.model=global.amazon.nova-2-lite-v1:0" })
@ContextConfiguration(initializers = EndToEndSpringAIChatClientIntegrationTest.MemoryInitializer.class)
class EndToEndSpringAIChatClientIntegrationTest {

	static String memoryId;
	static BedrockAgentCoreControlClient client = BedrockAgentCoreControlClient.create();

	static class MemoryInitializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {

		@Override
		public void initialize(ConfigurableApplicationContext context) {
			try {
				var createMemoryRequest = CreateMemoryRequest.builder()
					.name("test_memory_" + System.currentTimeMillis())
					.eventExpiryDuration(100)
					.build();
				var createMemoryResponse = client.createMemory(createMemoryRequest);
				memoryId = createMemoryResponse.memory().id();

				await().atMost(Duration.ofMinutes(5)).pollInterval(Duration.ofSeconds(3)).until(() -> {
					System.out.println("Waiting for memory to be ACTIVE...");
					var getMemoryRequest = GetMemoryRequest.builder().memoryId(memoryId).build();
					var getMemoryResponse = client.getMemory(getMemoryRequest);
					return getMemoryResponse.memory().status() == MemoryStatus.ACTIVE;
				});

				context.getEnvironment().getSystemProperties().put("agentcore.memory.memory-id", memoryId);
			}
			catch (Exception e) {
				throw new RuntimeException("Failed to create memory", e);
			}
		}

	}

	@SpringBootApplication(scanBasePackages = "org.springaicommunity.agentcore.memory")
	@EnableConfigurationProperties(AgentCoreMemoryProperties.class)
	static class TestApp {

	}

	@Autowired
	private AgentCoreShortMemoryRepository chatMemoryRepository;

	@Autowired
	private ChatModel chatModel;

	@AfterAll
	static void teardown() {
		System.out.println("Deleting memory: " + memoryId);
		var deleteMemoryRequest = DeleteMemoryRequest.builder().memoryId(memoryId).build();
		var deleteMemoryResponse = client.deleteMemory(deleteMemoryRequest);
		System.out.println("Deleted memory: " + deleteMemoryResponse.statusAsString());
	}

	@Test
	void shouldHandleStringRequest() {

		var chatMemory = MessageWindowChatMemory.builder()
			.chatMemoryRepository(chatMemoryRepository)
			.maxMessages(10)
			.build();

		var conversationId = "testActorId/testSessionId";

		chatMemory.clear(conversationId);

		var chatClient = ChatClient.builder(chatModel)
			.defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
			.build();

		chatClient.prompt()
			.user("My name is James")
			.advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
			.call()
			.content();

		var response = chatClient.prompt()
			.user("What is my name?")
			.advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
			.call()
			.content();

		System.out.println(response);

		assertThat(response).containsIgnoringCase("James");
	}

}
