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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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
	void shouldSplitAtLastColonSoActorIdMayContainColons() {
		// AgentCore actor ids may contain ':', session ids may not (#260).
		var result = AgentCoreMemoryConversationIdParser.parse("tenant:alice:s1");

		assertThat(result.actor()).isEqualTo("tenant:alice");
		assertThat(result.session()).isEqualTo("s1");
	}

	@Test
	void shouldKeepUrnStyleActorIdWhole() {
		var result = AgentCoreMemoryConversationIdParser.parse("urn:example:user:42:conv-1");

		assertThat(result.actor()).isEqualTo("urn:example:user:42");
		assertThat(result.session()).isEqualTo("conv-1");
	}

	@Test
	void shouldReadActorOnlyIdWithColonAsActorAndSession() {
		// Documented limitation: without a session, the colon of the actor id is taken
		// as the separator. Actor ids with a colon must use the "actorId:sessionId" form.
		var result = AgentCoreMemoryConversationIdParser.parse("tenant:alice");

		assertThat(result.actor()).isEqualTo("tenant");
		assertThat(result.session()).isEqualTo("alice");
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

	// ==================== D6e: of(...) composition (additive, round-trips) ==========

	@Test
	void ofComposesCompoundIdThatRoundTripsThroughParse() {
		String compound = AgentCoreMemoryConversationIdParser.of("alice", "conv-1");
		assertThat(compound).isEqualTo("alice:conv-1");

		var parsed = AgentCoreMemoryConversationIdParser.parse(compound);
		assertThat(parsed.actor()).isEqualTo("alice");
		assertThat(parsed.session()).isEqualTo("conv-1");
	}

	@Test
	void ofTrimsBothSegments() {
		assertThat(AgentCoreMemoryConversationIdParser.of("  alice  ", "  conv-1  ")).isEqualTo("alice:conv-1");
	}

	@Test
	void ofRejectsBlankUserId() {
		assertThatThrownBy(() -> AgentCoreMemoryConversationIdParser.of("   ", "conv-1"))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("userId");
		assertThatThrownBy(() -> AgentCoreMemoryConversationIdParser.of(null, "conv-1"))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void ofRejectsBlankSessionSuffix() {
		assertThatThrownBy(() -> AgentCoreMemoryConversationIdParser.of("alice", "  "))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("sessionSuffix");
		assertThatThrownBy(() -> AgentCoreMemoryConversationIdParser.of("alice", null))
			.isInstanceOf(IllegalArgumentException.class);
	}

	// ==================== #260: colons in the user id ====================

	@Test
	void ofAcceptsUserIdWithColonAndRoundTripsThroughParse() {
		String compound = AgentCoreMemoryConversationIdParser.of("tenant:alice", "s1");
		assertThat(compound).isEqualTo("tenant:alice:s1");

		var parsed = AgentCoreMemoryConversationIdParser.parse(compound);
		assertThat(parsed.actor()).isEqualTo("tenant:alice");
		assertThat(parsed.session()).isEqualTo("s1");
	}

	@Test
	void usersSharingAColonPrefixKeepDistinctActors() {
		// A first-colon split read the first two users as actor "tenant", so they
		// shared memory with each other and with the user "tenant".
		assertThat(actorOf("tenant:alice")).isEqualTo("tenant:alice");
		assertThat(actorOf("tenant:bob")).isEqualTo("tenant:bob");
		assertThat(actorOf("tenant")).isEqualTo("tenant");
	}

	@Test
	void ofRejectsSessionSuffixContainingColon() {
		// A colon in the suffix would move the split point, and AgentCore rejects it in
		// session ids anyway.
		assertThatThrownBy(() -> AgentCoreMemoryConversationIdParser.of("alice", "conv:1"))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("sessionSuffix must not contain ':'");
		assertThatThrownBy(() -> AgentCoreMemoryConversationIdParser.of("alice", " conv: "))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("sessionSuffix must not contain ':'");
	}

	@ParameterizedTest
	@ValueSource(strings = { ":tenant", "tenant:", "a::b", "a: :b" })
	void ofRejectsUserIdWithEmptyPartBetweenColons(String userId) {
		// The session repository rejects these, and AgentCore never accepts them.
		assertThatThrownBy(() -> AgentCoreMemoryConversationIdParser.of(userId, "s1"))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("empty part between colons");
	}

	@Test
	void hasBlankPartFindsEmptyLeadingTrailingAndDoubledParts() {
		assertThat(AgentCoreMemoryConversationIdParser.hasBlankPart("")).isTrue();
		assertThat(AgentCoreMemoryConversationIdParser.hasBlankPart(":tenant")).isTrue();
		assertThat(AgentCoreMemoryConversationIdParser.hasBlankPart("tenant:")).isTrue();
		assertThat(AgentCoreMemoryConversationIdParser.hasBlankPart("a::b")).isTrue();
		assertThat(AgentCoreMemoryConversationIdParser.hasBlankPart("a: :b")).isTrue();
		assertThat(AgentCoreMemoryConversationIdParser.hasBlankPart("tenant:alice")).isFalse();
		assertThat(AgentCoreMemoryConversationIdParser.hasBlankPart("alice")).isFalse();
	}

	@Test
	void ofRejectsControlCharactersInsideEitherPart() {
		// The session repository rejects control characters, so of() does too.
		assertThatThrownBy(() -> AgentCoreMemoryConversationIdParser.of("al\tice", "s1"))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("userId must not contain control characters (found U+0009 at index 2)");
		assertThatThrownBy(() -> AgentCoreMemoryConversationIdParser.of("alice", "s\u00001"))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("sessionSuffix must not contain control characters");
		assertThatThrownBy(() -> AgentCoreMemoryConversationIdParser.of("alice", "s\u007F"))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("sessionSuffix must not contain control characters");
	}

	@Test
	void ofTrimsBeforeCheckingForControlCharacters() {
		assertThat(AgentCoreMemoryConversationIdParser.of("\talice\n", "s1\r\n")).isEqualTo("alice:s1");
	}

	private static String actorOf(String userId) {
		return AgentCoreMemoryConversationIdParser.parse(AgentCoreMemoryConversationIdParser.of(userId, "s1")).actor();
	}

}
