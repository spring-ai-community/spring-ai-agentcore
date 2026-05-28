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

package org.springaicommunity.agentcore.memory;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link AgentCoreMemoryConversationIdParser}.
 */
class AgentCoreMemoryConversationIdParserTests {

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
