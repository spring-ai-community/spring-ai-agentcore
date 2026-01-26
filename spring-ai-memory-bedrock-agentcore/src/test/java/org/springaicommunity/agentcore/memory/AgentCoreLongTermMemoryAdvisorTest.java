package org.springaicommunity.agentcore.memory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springaicommunity.agentcore.memory.AgentCoreLongTermMemoryAdvisor.MemoryStrategy;
import org.springaicommunity.agentcore.memory.AgentCoreLongTermMemoryRetriever.MemoryRecord;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AgentCoreLongTermMemoryAdvisor}.
 *
 * @author Yuriy Bezsonov
 */
@ExtendWith(MockitoExtension.class)
class AgentCoreLongTermMemoryAdvisorTest {

	@Mock
	private AgentCoreLongTermMemoryRetriever retriever;

	@Mock
	private CallAdvisorChain chain;

	private AgentCoreLongTermMemoryAdvisor semanticAdvisor;

	private AgentCoreLongTermMemoryAdvisor listAdvisor;

	@BeforeEach
	void setUp() {
		semanticAdvisor = AgentCoreLongTermMemoryAdvisor.builder(retriever, MemoryStrategy.SEMANTIC)
			.strategyId("strategy-123")
			.contextLabel("Known facts")
			.order(100)
			.topK(3)
			.build();
		listAdvisor = AgentCoreLongTermMemoryAdvisor.builder(retriever, MemoryStrategy.USER_PREFERENCE)
			.strategyId("strategy-456")
			.contextLabel("User preferences")
			.order(101)
			.topK(3)
			.build();
	}

	@Test
	void shouldThrowExceptionWhenNoConversationId() {
		// Given
		ChatClientRequest request = ChatClientRequest.builder()
			.prompt(new Prompt(List.of(new UserMessage("Hello"))))
			.context(Map.of())
			.build();

		// When/Then
		assertThatThrownBy(() -> semanticAdvisor.adviseCall(request, chain)).isInstanceOf(IllegalStateException.class)
			.hasMessageContaining(ChatMemory.CONVERSATION_ID);
	}

	@Test
	void shouldEnrichWithSemanticMemories() {
		// Given
		List<MemoryRecord> memories = List.of(new MemoryRecord("1", "User likes coffee", 0.9),
				new MemoryRecord("2", "User is from Seattle", 0.85));

		when(retriever.searchMemories(eq("strategy-123"), eq("user-456"), anyString(), eq("What do I like?"), eq(3),
				eq(AgentCoreLongTermMemoryScope.ACTOR)))
			.thenReturn(memories);

		ChatClientRequest request = ChatClientRequest.builder()
			.prompt(new Prompt(List.of(new UserMessage("What do I like?"))))
			.context(Map.of(ChatMemory.CONVERSATION_ID, "user-456"))
			.build();

		// When
		semanticAdvisor.adviseCall(request, chain);

		// Then
		verify(retriever).searchMemories(eq("strategy-123"), eq("user-456"), anyString(), eq("What do I like?"), eq(3),
				eq(AgentCoreLongTermMemoryScope.ACTOR));
		verify(chain).nextCall(org.mockito.ArgumentMatchers.argThat(enrichedRequest -> {
			List<?> messages = enrichedRequest.prompt().getInstructions();
			assertThat(messages).hasSize(2);
			assertThat(messages.get(0)).isInstanceOf(SystemMessage.class);
			assertThat(((SystemMessage) messages.get(0)).getText()).contains("Known facts");
			assertThat(((SystemMessage) messages.get(0)).getText()).contains("User likes coffee");
			assertThat(((SystemMessage) messages.get(0)).getText()).contains("User is from Seattle");
			return true;
		}));
	}

	@Test
	void shouldEnrichWithListedMemories() {
		// Given
		List<MemoryRecord> preferences = List.of(new MemoryRecord("1", "Dark mode enabled", 0.0),
				new MemoryRecord("2", "Metric units", 0.0));

		when(retriever.listMemories(eq("strategy-456"), eq("user-456"))).thenReturn(preferences);

		ChatClientRequest request = ChatClientRequest.builder()
			.prompt(new Prompt(List.of(new UserMessage("Show settings"))))
			.context(Map.of(ChatMemory.CONVERSATION_ID, "user-456:session-1"))
			.build();

		// When
		listAdvisor.adviseCall(request, chain);

		// Then
		verify(retriever).listMemories("strategy-456", "user-456");
		verify(chain).nextCall(org.mockito.ArgumentMatchers.argThat(enrichedRequest -> {
			List<?> messages = enrichedRequest.prompt().getInstructions();
			assertThat(((SystemMessage) messages.get(0)).getText()).contains("User preferences");
			assertThat(((SystemMessage) messages.get(0)).getText()).contains("Dark mode enabled");
			return true;
		}));
	}

	@Test
	void shouldNotEnrichWhenNoMemoriesFound() {
		// Given
		when(retriever.searchMemories(anyString(), anyString(), anyString(), anyString(), anyInt(),
				any(AgentCoreLongTermMemoryScope.class)))
			.thenReturn(List.of());

		ChatClientRequest request = ChatClientRequest.builder()
			.prompt(new Prompt(List.of(new UserMessage("Hello"))))
			.context(Map.of(ChatMemory.CONVERSATION_ID, "user-456"))
			.build();

		// When
		semanticAdvisor.adviseCall(request, chain);

		// Then - should pass original request unchanged
		verify(chain).nextCall(request);
	}

	@Test
	void shouldHaveCorrectNameAndOrder() {
		assertThat(semanticAdvisor.getName()).isEqualTo("AgentCoreLongTermMemoryAdvisor-SEMANTIC");
		assertThat(semanticAdvisor.getOrder()).isEqualTo(100);

		assertThat(listAdvisor.getName()).isEqualTo("AgentCoreLongTermMemoryAdvisor-USER_PREFERENCE");
		assertThat(listAdvisor.getOrder()).isEqualTo(101);
	}

	@Test
	void shouldEnrichWithEpisodicMemoriesFromSeparateStrategies() {
		// Given - separate strategies for episodes and reflections
		AgentCoreLongTermMemoryAdvisor episodicAdvisor = AgentCoreLongTermMemoryAdvisor
			.builder(retriever, MemoryStrategy.EPISODIC)
			.strategyId("episodes-strategy")
			.reflectionsStrategyId("reflections-strategy")
			.contextLabel("Episodic context")
			.order(103)
			.topK(3)
			.reflectionsTopK(2)
			.scope(AgentCoreLongTermMemoryScope.ACTOR)
			.build();

		List<MemoryRecord> episodes = List.of(new MemoryRecord("1", "User asked about weather yesterday", 0.9));
		List<MemoryRecord> reflections = List.of(new MemoryRecord("2", "User prefers detailed answers", 0.85));

		when(retriever.searchMemories(eq("episodes-strategy"), eq("user-456"), anyString(), eq("How's the weather?"),
				eq(3), eq(AgentCoreLongTermMemoryScope.ACTOR)))
			.thenReturn(episodes);
		when(retriever.searchMemories(eq("reflections-strategy"), eq("user-456"), anyString(), eq("How's the weather?"),
				eq(2), eq(AgentCoreLongTermMemoryScope.ACTOR)))
			.thenReturn(reflections);

		ChatClientRequest request = ChatClientRequest.builder()
			.prompt(new Prompt(List.of(new UserMessage("How's the weather?"))))
			.context(Map.of(ChatMemory.CONVERSATION_ID, "user-456"))
			.build();

		// When
		episodicAdvisor.adviseCall(request, chain);

		// Then - verify both strategies are called
		verify(retriever).searchMemories(eq("episodes-strategy"), eq("user-456"), anyString(), eq("How's the weather?"),
				eq(3), eq(AgentCoreLongTermMemoryScope.ACTOR));
		verify(retriever).searchMemories(eq("reflections-strategy"), eq("user-456"), anyString(),
				eq("How's the weather?"), eq(2), eq(AgentCoreLongTermMemoryScope.ACTOR));
		verify(chain).nextCall(org.mockito.ArgumentMatchers.argThat(enrichedRequest -> {
			List<?> messages = enrichedRequest.prompt().getInstructions();
			assertThat(((SystemMessage) messages.get(0)).getText()).contains("Relevant past interactions");
			assertThat(((SystemMessage) messages.get(0)).getText()).contains("User asked about weather yesterday");
			assertThat(((SystemMessage) messages.get(0)).getText()).contains("Lessons learned");
			assertThat(((SystemMessage) messages.get(0)).getText()).contains("User prefers detailed answers");
			return true;
		}));
	}

	@Test
	void shouldEnrichWithEpisodesOnlyWhenNoReflectionsStrategy() {
		// Given - only episodes strategy, no reflections
		AgentCoreLongTermMemoryAdvisor episodicAdvisor = AgentCoreLongTermMemoryAdvisor
			.builder(retriever, MemoryStrategy.EPISODIC)
			.strategyId("episodes-strategy")
			.contextLabel("Episodic context")
			.order(103)
			.topK(3)
			.reflectionsTopK(2)
			.scope(AgentCoreLongTermMemoryScope.ACTOR)
			.build();

		List<MemoryRecord> episodes = List.of(new MemoryRecord("1", "Previous interaction", 0.9));

		when(retriever.searchMemories(eq("episodes-strategy"), eq("user-456"), anyString(), eq("Hello"), eq(3),
				eq(AgentCoreLongTermMemoryScope.ACTOR)))
			.thenReturn(episodes);

		ChatClientRequest request = ChatClientRequest.builder()
			.prompt(new Prompt(List.of(new UserMessage("Hello"))))
			.context(Map.of(ChatMemory.CONVERSATION_ID, "user-456"))
			.build();

		// When
		episodicAdvisor.adviseCall(request, chain);

		// Then - only episodes strategy should be called
		verify(retriever).searchMemories(eq("episodes-strategy"), eq("user-456"), anyString(), eq("Hello"), eq(3),
				eq(AgentCoreLongTermMemoryScope.ACTOR));
		verify(chain).nextCall(org.mockito.ArgumentMatchers.argThat(enrichedRequest -> {
			List<?> messages = enrichedRequest.prompt().getInstructions();
			assertThat(((SystemMessage) messages.get(0)).getText()).contains("Relevant past interactions");
			assertThat(((SystemMessage) messages.get(0)).getText()).doesNotContain("Lessons learned");
			return true;
		}));
	}

}
