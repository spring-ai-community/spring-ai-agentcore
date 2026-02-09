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

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springaicommunity.agentcore.memory.AgentCoreLongTermMemoryRetriever.MemoryRecord;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import software.amazon.awssdk.services.bedrockagentcore.BedrockAgentCoreClient;

/**
 * Base integration test for AgentCore Memory (STM and LTM).
 *
 * <p>
 * Subclasses must set the protected static fields before tests run:
 * <ul>
 * <li>memoryId - Memory resource ID</li>
 * <li>semanticStrategyId, preferencesStrategyId, summaryStrategyId,
 * episodicStrategyId</li>
 * <li>actorId, sessionId - for conversation tracking</li>
 * </ul>
 *
 * @author Yuriy Bezsonov
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("AgentCore Memory Integration Tests")
public abstract class AgentCoreMemoryIT {

	protected static final String BOLD = "\033[1m";

	protected static final String RESET = "\033[0m";

	// Conversation content constants
	protected static final String USER_MSG_1 = "My name is Alex and I'm a software engineer";

	protected static final String USER_MSG_2 = "I prefer dark mode and use vim as my editor";

	protected static final String USER_MSG_3 = "I'm working on a Spring Boot project with Java 21";

	// Model configuration
	protected static final String CHAT_MODEL = "global.amazon.nova-2-lite-v1:0";

	// Strategy name constants
	protected static final String SEMANTIC_STRATEGY_NAME = "SemanticFacts";

	protected static final String PREFERENCES_STRATEGY_NAME = "UserPreferences";

	protected static final String SUMMARY_STRATEGY_NAME = "ConversationSummary";

	protected static final String EPISODIC_STRATEGY_NAME = "EpisodicMemory";

	// Subclasses must set these before tests run
	protected static String memoryId;

	protected static String semanticStrategyId;

	protected static String preferencesStrategyId;

	protected static String summaryStrategyId;

	protected static String episodicStrategyId;

	protected static String actorId;

	protected static String sessionId;

	@Autowired
	protected ChatModel chatModel;

	@Autowired
	protected BedrockAgentCoreClient agentCoreClient;

	protected static String generateTestId() {
		return UUID.randomUUID().toString().substring(0, 8);
	}

	protected static void logMemoryInfo(String prefix) {
		System.out.println(BOLD + prefix + "Memory ID: " + memoryId + RESET);
		System.out.println(BOLD + "  Semantic: " + semanticStrategyId + RESET);
		System.out.println(BOLD + "  Preferences: " + preferencesStrategyId + RESET);
		System.out.println(BOLD + "  Summary: " + summaryStrategyId + RESET);
		System.out.println(BOLD + "  Episodic: " + episodicStrategyId + RESET);
	}

	protected static void assertStrategyIdsNotNull() {
		if (semanticStrategyId == null || preferencesStrategyId == null || summaryStrategyId == null
				|| episodicStrategyId == null) {
			throw new IllegalStateException("Strategy IDs not fully initialized: semantic=" + semanticStrategyId
					+ ", preferences=" + preferencesStrategyId + ", summary=" + summaryStrategyId + ", episodic="
					+ episodicStrategyId);
		}
	}

	private void sendMessage(ChatClient chatClient, String conversationId, String userMessage) {
		System.out.println(BOLD + "User: " + userMessage + RESET);
		var response = chatClient.prompt()
			.user(userMessage)
			.advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
			.call()
			.content();
		System.out.println(BOLD + "Assistant: " + RESET + response);
		assertThat(response).isNotBlank();
	}

	private void printMemoryRecords(String label, List<MemoryRecord> records) {
		System.out.println(BOLD + label + " (" + records.size() + "):" + RESET);
		records.forEach(r -> System.out.println("  - " + r.content()));
	}

	@Test
	@Order(1)
	@DisplayName("Should have conversation with ChatClient and STM")
	void shouldHaveConversationWithMemory() {
		var stmRepository = new AgentCoreShortTermMemoryRepository(memoryId, agentCoreClient, null, sessionId, 100,
				true);

		// Use Integer.MAX_VALUE for unlimited window - actual limit controlled by
		// agentcore.memory.total-events-limit property
		var chatMemory = MessageWindowChatMemory.builder()
			.chatMemoryRepository(stmRepository)
			.maxMessages(Integer.MAX_VALUE)
			.build();

		var chatClient = ChatClient.builder(chatModel)
			.defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
			.build();

		String conversationId = actorId + ":" + sessionId;

		System.out.println(BOLD + "\n----- Conversation -----" + RESET);

		sendMessage(chatClient, conversationId, USER_MSG_1);
		sendMessage(chatClient, conversationId, USER_MSG_2);
		sendMessage(chatClient, conversationId, USER_MSG_3);

		System.out.println(BOLD + "------------------------" + RESET + "\n");

		// Verify STM has messages
		var messages = stmRepository.findByConversationId(conversationId);
		assertThat(messages).hasSizeGreaterThanOrEqualTo(6);
		System.out.println(BOLD + "STM messages: " + messages.size() + RESET);
	}

	@Test
	@Order(2)
	@DisplayName("Should consolidate to LTM with semantic facts, preferences, summary, and episodic")
	void shouldConsolidateToLTM() {
		var ltmRetriever = new AgentCoreLongTermMemoryRetriever(agentCoreClient, memoryId);

		// Wait for consolidation (max 3 minutes)
		long consolidationStartTime = System.currentTimeMillis();
		await().atMost(Duration.ofMinutes(3)).pollInterval(Duration.ofSeconds(15)).until(() -> {
			var semantic = ltmRetriever.searchMemories(semanticStrategyId, actorId, "Alex engineer", 5);
			var prefs = ltmRetriever.listMemories(preferencesStrategyId, actorId);
			var summary = ltmRetriever.searchSummaries(summaryStrategyId, actorId, sessionId, "conversation", 3,
					AgentCoreLongTermMemoryScope.SESSION);

			long elapsed = (System.currentTimeMillis() - consolidationStartTime) / 1000;
			System.out.printf("Consolidation: semantic=%b prefs=%b summary=%b (elapsed: %d:%02d)%n",
					!semantic.isEmpty(), !prefs.isEmpty(), !summary.isEmpty(), elapsed / 60, elapsed % 60);

			return !semantic.isEmpty() && !prefs.isEmpty() && !summary.isEmpty();
		});

		// Verify semantic facts
		List<MemoryRecord> semanticFacts = ltmRetriever.searchMemories(semanticStrategyId, actorId, "Alex engineer", 5);
		assertThat(semanticFacts).isNotEmpty();
		String allSemantic = semanticFacts.stream().map(MemoryRecord::content).reduce("", String::concat).toLowerCase();
		assertThat(allSemantic).containsAnyOf("alex", "engineer", "software");
		System.out.println(BOLD + "\n=== LTM Content ===" + RESET);
		printMemoryRecords("Semantic facts", semanticFacts);

		// Verify preferences
		List<MemoryRecord> preferences = ltmRetriever.listMemories(preferencesStrategyId, actorId);
		assertThat(preferences).isNotEmpty();
		String allPrefs = preferences.stream().map(MemoryRecord::content).reduce("", String::concat).toLowerCase();
		assertThat(allPrefs).containsAnyOf("dark mode", "vim", "spring");
		printMemoryRecords("Preferences", preferences);

		// Verify summary
		List<MemoryRecord> summaries = ltmRetriever.searchSummaries(summaryStrategyId, actorId, sessionId,
				"conversation", 3, AgentCoreLongTermMemoryScope.SESSION);
		assertThat(summaries).isNotEmpty();
		assertThat(summaries.get(0).content()).isNotBlank();
		printMemoryRecords("Summary", summaries);

		// Try to get episodic (don't wait, just check if available)
		List<MemoryRecord> episodes = ltmRetriever.searchMemories(episodicStrategyId, actorId, "Alex engineer", 5);
		System.out.println(BOLD + "Episodic (" + episodes.size() + "):" + RESET);
		if (episodes.isEmpty()) {
			System.out.println("  (not yet consolidated)");
		}
		else {
			episodes.forEach(r -> System.out.println("  - " + r.content()));
		}

		System.out.println(BOLD + "===================" + RESET + "\n");
	}

	@SpringBootApplication(scanBasePackages = "org.springaicommunity.agentcore.memory")
	static class TestApp {

		@org.springframework.context.annotation.Bean
		BedrockAgentCoreClient bedrockAgentCoreClient() {
			return BedrockAgentCoreClient.create();
		}

	}

}
