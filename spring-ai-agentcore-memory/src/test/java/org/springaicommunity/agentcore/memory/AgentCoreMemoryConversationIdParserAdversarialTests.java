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
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Adversarial / characterization tests for {@link AgentCoreMemoryConversationIdParser}.
 *
 * <p>
 * The conversationId (a.k.a. sessionId under {@code SessionMemoryAdvisor}) arrives from
 * untrusted user HTTP requests, so this pins the parser's response to hostile input.
 * These are CHARACTERIZATION tests: they lock in the parser's CURRENT behavior so any
 * redesign is a conscious decision. Several assertions document rough edges (empty actor,
 * empty session suffix, un-trimmed whitespace) that should be reviewed by kl-architect;
 * see kl-fuzzer-152.md. The parser never crashes on any of these inputs, which is the
 * primary safety property this suite guarantees.
 */
class AgentCoreMemoryConversationIdParserAdversarialTests {

	// ==================== No-crash guarantee (primary property) ====================

	@ParameterizedTest
	@ValueSource(strings = { ":", "::", ":::", "a:", ":b", "a:b:c", " ", "  ", "\t", "\n", " a : b ", "a:b:", ":a:b",
			":::::", "\u0000", "\u0000:\u0000", "actor:", ":session" })
	void parseNeverThrowsForNonEmptyHostileInput(String hostile) {
		// Any non-null, non-"" string must be handled without an unexpected throw.
		assertThatCode(() -> AgentCoreMemoryConversationIdParser.parse(hostile)).doesNotThrowAnyException();
	}

	@Test
	void parseHandlesVeryLongStringWithoutBlowup() {
		// 1 MB actor segment plus a suffix; split(":", 2) is linear, must not hang/OOM.
		String big = "x".repeat(1_000_000) + ":s";
		var result = AgentCoreMemoryConversationIdParser.parse(big);
		assertThat(result.actor()).hasSize(1_000_000);
		assertThat(result.session()).isEqualTo("s");
	}

	@Test
	void parseHandlesManyColonsWithoutBlowup() {
		// split(":", 2) keeps everything after the first colon in the session segment.
		String many = "a:" + ":".repeat(100_000);
		var result = AgentCoreMemoryConversationIdParser.parse(many);
		assertThat(result.actor()).isEqualTo("a");
		assertThat(result.session()).isEqualTo(":".repeat(100_000));
	}

	// ==================== Unicode / non-ASCII pass-through ====================

	@Test
	void parsePreservesUnicodeActorAndSession() {
		// RTL mark, zero-width space, emoji, CJK; all must round-trip untouched.
		String actor = "\u202Euser\u200B\uD83D\uDE00";
		String session = "\u4F1A\u8BDD";
		var result = AgentCoreMemoryConversationIdParser.parse(actor + ":" + session);
		assertThat(result.actor()).isEqualTo(actor);
		assertThat(result.session()).isEqualTo(session);
	}

	@Test
	void parseHandlesLoneSurrogateWithoutThrowing() {
		// A lone high surrogate is a malformed-UTF16 string the type system allows.
		String loneSurrogate = "a:\uD800";
		var result = AgentCoreMemoryConversationIdParser.parse(loneSurrogate);
		assertThat(result.actor()).isEqualTo("a");
		assertThat(result.session()).isEqualTo("\uD800");
	}

	// ==================== Empty-input rejection ====================

	@Test
	void parseRejectsNull() {
		assertThatThrownBy(() -> AgentCoreMemoryConversationIdParser.parse(null))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("conversationId is required");
	}

	@Test
	void parseRejectsEmptyString() {
		assertThatThrownBy(() -> AgentCoreMemoryConversationIdParser.parse(""))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("conversationId is required");
	}

	// ==================== ROUGH EDGES (characterized, flagged for kl-architect) ====

	@Test
	void roughEdgeColonOnlyProducesEmptyActorAndEmptySession() {
		// FINDING F1 (wrong-behavior): ":" is NOT rejected. It yields an empty actor
		// (== derived userId used by the ownership check) and an empty session suffix.
		// Both later reach AgentCore as blank actorId/sessionId and fail at call time.
		var result = AgentCoreMemoryConversationIdParser.parse(":");
		assertThat(result.actor()).isEmpty();
		assertThat(result.session()).isEmpty();
	}

	@Test
	void roughEdgeLeadingColonProducesEmptyActor() {
		// FINDING F1: empty actor -> empty Session.userId; ownership check compares "".
		var result = AgentCoreMemoryConversationIdParser.parse(":realSession");
		assertThat(result.actor()).isEmpty();
		assertThat(result.session()).isEqualTo("realSession");
	}

	@Test
	void roughEdgeTrailingColonProducesEmptySession() {
		// FINDING F2 (rough-edge): "actor:" -> empty session suffix, sent as blank
		// sessionId to AgentCore.
		var result = AgentCoreMemoryConversationIdParser.parse("realActor:");
		assertThat(result.actor()).isEqualTo("realActor");
		assertThat(result.session()).isEmpty();
	}

	@Test
	void roughEdgeWhitespaceOnlyActorIsAccepted() {
		// FINDING F3 (rough-edge / inconsistency): parse() uses isEmpty() not isBlank(),
		// so a whitespace-only id is accepted here even though the repository's
		// validateSessionId (trim().isEmpty()) would reject the same value.
		var result = AgentCoreMemoryConversationIdParser.parse("   ");
		assertThat(result.actor()).isEqualTo("   ");
		assertThat(result.session()).isEqualTo(AgentCoreMemoryConversationIdParser.DEFAULT_SESSION);
	}

	@Test
	void roughEdgeSegmentsAreNotTrimmed() {
		// FINDING F4 (rough-edge): surrounding whitespace is preserved, so " alice:conv "
		// and "alice:conv" are DIFFERENT actor/session pairs. A stray space silently
		// fragments a user's session history.
		var result = AgentCoreMemoryConversationIdParser.parse(" alice : conv ");
		assertThat(result.actor()).isEqualTo(" alice ");
		assertThat(result.session()).isEqualTo(" conv ");
	}

}
