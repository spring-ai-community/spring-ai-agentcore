package org.springaicommunity.agentcore.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * Tests for {@link AgentCoreMemoryConversationIdParser}.
 */
class AgentCoreMemoryConversationIdParserTest {

	@Test
	void shouldParseActorOnly() {
		var result = AgentCoreMemoryConversationIdParser.parse("user123");

		assertThat(result.actor()).isEqualTo("user123");
		assertThat(result.session()).isEqualTo(AgentCoreMemoryConversationIdParser.DEFAULT_SESSION);
	}

	@Test
	void shouldParseActorAndSession() {
		var result = AgentCoreMemoryConversationIdParser.parse("user123:session456");

		assertThat(result.actor()).isEqualTo("user123");
		assertThat(result.session()).isEqualTo("session456");
	}

	@Test
	void shouldUseCustomDefaultSession() {
		var result = AgentCoreMemoryConversationIdParser.parse("user123", "custom-session");

		assertThat(result.actor()).isEqualTo("user123");
		assertThat(result.session()).isEqualTo("custom-session");
	}

	@Test
	void shouldHandleColonInSessionId() {
		var result = AgentCoreMemoryConversationIdParser.parse("user123:session:with:colons");

		assertThat(result.actor()).isEqualTo("user123");
		assertThat(result.session()).isEqualTo("session:with:colons");
	}

	@Test
	void shouldThrowOnNullConversationId() {
		assertThatThrownBy(() -> AgentCoreMemoryConversationIdParser.parse(null))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("conversationId is required");
	}

	@Test
	void shouldThrowOnEmptyConversationId() {
		assertThatThrownBy(() -> AgentCoreMemoryConversationIdParser.parse(""))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("conversationId is required");
	}

	@Test
	void shouldFallbackToConstantWhenCustomDefaultIsNull() {
		var result = AgentCoreMemoryConversationIdParser.parse("user123", null);

		assertThat(result.actor()).isEqualTo("user123");
		assertThat(result.session()).isEqualTo(AgentCoreMemoryConversationIdParser.DEFAULT_SESSION);
	}

	@Test
	void shouldVerifyDefaultSessionConstant() {
		assertThat(AgentCoreMemoryConversationIdParser.DEFAULT_SESSION).isEqualTo("default-session");
	}

}
