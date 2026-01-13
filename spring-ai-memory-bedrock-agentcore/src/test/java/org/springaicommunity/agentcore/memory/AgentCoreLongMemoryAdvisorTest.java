package org.springaicommunity.agentcore.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springaicommunity.agentcore.memory.AgentCoreLongMemoryAdvisor.Mode;
import org.springaicommunity.agentcore.memory.AgentCoreLongMemoryRepository.MemoryRecord;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;

@ExtendWith(MockitoExtension.class)
class AgentCoreLongMemoryAdvisorTest {

	@Mock
	private AgentCoreLongMemoryRepository repository;

	@Mock
	private CallAdvisorChain chain;

	private AgentCoreLongMemoryAdvisor semanticAdvisor;

	private AgentCoreLongMemoryAdvisor listAdvisor;

	@BeforeEach
	void setUp() {
		semanticAdvisor = new AgentCoreLongMemoryAdvisor(repository, "strategy-123", "Known facts", Mode.SEMANTIC, 100,
				3);
		listAdvisor = new AgentCoreLongMemoryAdvisor(repository, "strategy-456", "User preferences", Mode.LIST, 101, 3);
	}

	@Test
	void shouldThrowExceptionWhenNoUserId() {
		// Given
		var request = ChatClientRequest.builder()
			.prompt(new Prompt(List.of(new UserMessage("Hello"))))
			.context(Map.of())
			.build();

		// When/Then
		assertThatThrownBy(() -> semanticAdvisor.adviseCall(request, chain)).isInstanceOf(IllegalStateException.class)
			.hasMessageContaining(AgentCoreLongMemoryAdvisor.USER_ID_PARAM);
	}

	@Test
	void shouldEnrichWithSemanticMemories() {
		// Given
		var memories = List.of(new MemoryRecord("1", "User likes coffee", 0.9),
				new MemoryRecord("2", "User is from Seattle", 0.85));

		when(repository.searchMemories(eq("strategy-123"), eq("user-456"), eq("What do I like?"), eq(3)))
			.thenReturn(memories);

		var request = ChatClientRequest.builder()
			.prompt(new Prompt(List.of(new UserMessage("What do I like?"))))
			.context(Map.of(AgentCoreLongMemoryAdvisor.USER_ID_PARAM, "user-456"))
			.build();

		// When
		semanticAdvisor.adviseCall(request, chain);

		// Then
		verify(repository).searchMemories("strategy-123", "user-456", "What do I like?", 3);
		verify(chain).nextCall(org.mockito.ArgumentMatchers.argThat(enrichedRequest -> {
			var messages = enrichedRequest.prompt().getInstructions();
			assertThat(messages).hasSize(2);
			assertThat(messages.get(0)).isInstanceOf(SystemMessage.class);
			assertThat(messages.get(0).getText()).contains("Known facts");
			assertThat(messages.get(0).getText()).contains("User likes coffee");
			assertThat(messages.get(0).getText()).contains("User is from Seattle");
			return true;
		}));
	}

	@Test
	void shouldEnrichWithListedMemories() {
		// Given
		var preferences = List.of(new MemoryRecord("1", "Dark mode enabled", 0.0),
				new MemoryRecord("2", "Metric units", 0.0));

		when(repository.listMemories(eq("strategy-456"), eq("user-456"))).thenReturn(preferences);

		var request = ChatClientRequest.builder()
			.prompt(new Prompt(List.of(new UserMessage("Show settings"))))
			.context(Map.of(AgentCoreLongMemoryAdvisor.USER_ID_PARAM, "user-456"))
			.build();

		// When
		listAdvisor.adviseCall(request, chain);

		// Then
		verify(repository).listMemories("strategy-456", "user-456");
		verify(chain).nextCall(org.mockito.ArgumentMatchers.argThat(enrichedRequest -> {
			var messages = enrichedRequest.prompt().getInstructions();
			assertThat(messages.get(0).getText()).contains("User preferences");
			assertThat(messages.get(0).getText()).contains("Dark mode enabled");
			return true;
		}));
	}

	@Test
	void shouldNotEnrichWhenNoMemoriesFound() {
		// Given
		when(repository.searchMemories(anyString(), anyString(), anyString(), anyInt())).thenReturn(List.of());

		var request = ChatClientRequest.builder()
			.prompt(new Prompt(List.of(new UserMessage("Hello"))))
			.context(Map.of(AgentCoreLongMemoryAdvisor.USER_ID_PARAM, "user-456"))
			.build();

		// When
		semanticAdvisor.adviseCall(request, chain);

		// Then - should pass original request unchanged
		verify(chain).nextCall(request);
	}

	@Test
	void shouldHaveCorrectNameAndOrder() {
		assertThat(semanticAdvisor.getName()).isEqualTo("AgentCoreLongMemoryAdvisor-SEMANTIC");
		assertThat(semanticAdvisor.getOrder()).isEqualTo(100);

		assertThat(listAdvisor.getName()).isEqualTo("AgentCoreLongMemoryAdvisor-LIST");
		assertThat(listAdvisor.getOrder()).isEqualTo(101);
	}

}
