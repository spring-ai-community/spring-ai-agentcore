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

package org.springaicommunity.agentcore.artifacts;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link CaffeineArtifactStore}.
 *
 * @author Yuriy Bezsonov
 */
@DisplayName("CaffeineArtifactStore Tests")
class CaffeineArtifactStoreTests {

	private CaffeineArtifactStore<String> store;

	@BeforeEach
	void setUp() {
		this.store = new CaffeineArtifactStore<>(300, "TestStore");
	}

	@Test
	@DisplayName("Should store and retrieve single artifact")
	void shouldStoreAndRetrieveSingleArtifact() {
		this.store.store("session-1", "artifact-1");

		List<String> result = this.store.retrieve("session-1");

		assertThat(result).containsExactly("artifact-1");
	}

	@Test
	@DisplayName("Should accumulate multiple artifacts in same session")
	void shouldAccumulateMultipleArtifactsInSameSession() {
		this.store.store("session-1", "artifact-1");
		this.store.store("session-1", "artifact-2");
		this.store.store("session-1", "artifact-3");

		List<String> result = this.store.retrieve("session-1");

		assertThat(result).containsExactly("artifact-1", "artifact-2", "artifact-3");
	}

	@Test
	@DisplayName("Should store all artifacts at once")
	void shouldStoreAllArtifactsAtOnce() {
		this.store.storeAll("session-1", List.of("a", "b", "c"));

		List<String> result = this.store.retrieve("session-1");

		assertThat(result).containsExactly("a", "b", "c");
	}

	@Test
	@DisplayName("Should clear artifacts after retrieve")
	void shouldClearArtifactsAfterRetrieve() {
		this.store.store("session-1", "artifact-1");

		assertThat(this.store.hasArtifacts("session-1")).isTrue();
		this.store.retrieve("session-1");
		assertThat(this.store.hasArtifacts("session-1")).isFalse();
		assertThat(this.store.retrieve("session-1")).isNull();
	}

	@Test
	@DisplayName("Should isolate artifacts between sessions")
	void shouldIsolateArtifactsBetweenSessions() {
		this.store.store("session-A", "artifact-A");
		this.store.store("session-B", "artifact-B");

		List<String> resultA = this.store.retrieve("session-A");
		List<String> resultB = this.store.retrieve("session-B");

		assertThat(resultA).containsExactly("artifact-A");
		assertThat(resultB).containsExactly("artifact-B");
	}

	@Test
	@DisplayName("Should return null for nonexistent session")
	void shouldReturnNullForNonexistentSession() {
		List<String> result = this.store.retrieve("nonexistent");

		assertThat(result).isNull();
	}

	@Test
	@DisplayName("Should hasArtifacts return false for nonexistent session")
	void shouldHasArtifactsReturnFalseForNonexistentSession() {
		assertThat(this.store.hasArtifacts("nonexistent")).isFalse();
	}

	@Test
	@DisplayName("Should hasArtifacts return true when artifacts exist")
	void shouldHasArtifactsReturnTrueWhenArtifactsExist() {
		this.store.store("session-1", "artifact-1");

		assertThat(this.store.hasArtifacts("session-1")).isTrue();
	}

	@Test
	@DisplayName("Should use default session ID for null sessionId")
	void shouldUseDefaultSessionIdForNull() {
		this.store.store(null, "artifact-1");

		assertThat(this.store.hasArtifacts(SessionConstants.DEFAULT_SESSION_ID)).isTrue();
		List<String> result = this.store.retrieve(null);
		assertThat(result).containsExactly("artifact-1");
	}

	@Test
	@DisplayName("Should use default session ID for blank sessionId")
	void shouldUseDefaultSessionIdForBlank() {
		this.store.store("   ", "artifact-1");

		assertThat(this.store.hasArtifacts(SessionConstants.DEFAULT_SESSION_ID)).isTrue();
		List<String> result = this.store.retrieve("   ");
		assertThat(result).containsExactly("artifact-1");
	}

	@Test
	@DisplayName("Should ignore null artifact on store")
	void shouldIgnoreNullArtifactOnStore() {
		this.store.store("session-1", null);

		assertThat(this.store.hasArtifacts("session-1")).isFalse();
	}

	@Test
	@DisplayName("Should ignore empty list on storeAll")
	void shouldIgnoreEmptyListOnStoreAll() {
		this.store.storeAll("session-1", List.of());

		assertThat(this.store.hasArtifacts("session-1")).isFalse();
	}

	@Test
	@DisplayName("Should ignore null list on storeAll")
	void shouldIgnoreNullListOnStoreAll() {
		this.store.storeAll("session-1", null);

		assertThat(this.store.hasArtifacts("session-1")).isFalse();
	}

	@Test
	@DisplayName("Should clear artifacts without returning them")
	void shouldClearArtifactsWithoutReturningThem() {
		this.store.store("session-1", "artifact-1");
		this.store.store("session-1", "artifact-2");

		assertThat(this.store.hasArtifacts("session-1")).isTrue();
		this.store.clear("session-1");
		assertThat(this.store.hasArtifacts("session-1")).isFalse();
		assertThat(this.store.retrieve("session-1")).isNull();
	}

	@Test
	@DisplayName("Should clear handle nonexistent session gracefully")
	void shouldClearHandleNonexistentSessionGracefully() {
		this.store.clear("nonexistent");

		assertThat(this.store.hasArtifacts("nonexistent")).isFalse();
	}

	@Test
	@DisplayName("Should expire artifacts after TTL")
	void shouldExpireArtifactsAfterTtl() throws InterruptedException {
		CaffeineArtifactStore<String> shortTtlStore = new CaffeineArtifactStore<>(1, "ShortTtlStore");
		shortTtlStore.store("session-1", "artifact-1");

		assertThat(shortTtlStore.hasArtifacts("session-1")).isTrue();

		// Wait for TTL to expire (1 second + buffer)
		Thread.sleep(1500);

		// Caffeine may need a read to trigger cleanup
		shortTtlStore.hasArtifacts("session-1");

		assertThat(shortTtlStore.retrieve("session-1")).isNull();
	}

	@Test
	@DisplayName("Should use default store name when not specified")
	void shouldUseDefaultStoreNameWhenNotSpecified() {
		CaffeineArtifactStore<String> defaultNameStore = new CaffeineArtifactStore<>(300);
		defaultNameStore.store("session-1", "artifact-1");

		assertThat(defaultNameStore.hasArtifacts("session-1")).isTrue();
		assertThat(defaultNameStore.retrieve("session-1")).containsExactly("artifact-1");
	}

	@Test
	@DisplayName("Should peek artifacts without removing them")
	void shouldPeekArtifactsWithoutRemovingThem() {
		this.store.store("session-1", "artifact-1");
		this.store.store("session-1", "artifact-2");

		List<String> peeked = this.store.peek("session-1");

		assertThat(peeked).containsExactly("artifact-1", "artifact-2");
		assertThat(this.store.hasArtifacts("session-1")).isTrue();
		assertThat(this.store.retrieve("session-1")).containsExactly("artifact-1", "artifact-2");
	}

	@Test
	@DisplayName("Should return null when peeking nonexistent session")
	void shouldReturnNullWhenPeekingNonexistentSession() {
		assertThat(this.store.peek("nonexistent")).isNull();
	}

	@Test
	@DisplayName("Should return unmodifiable list from peek")
	void shouldReturnUnmodifiableListFromPeek() {
		this.store.store("session-1", "artifact-1");

		List<String> peeked = this.store.peek("session-1");

		assertThat(peeked).isUnmodifiable();
	}

	@Test
	@DisplayName("Should count artifacts for session")
	void shouldCountArtifactsForSession() {
		assertThat(this.store.count("session-1")).isZero();

		this.store.store("session-1", "artifact-1");
		assertThat(this.store.count("session-1")).isEqualTo(1);

		this.store.store("session-1", "artifact-2");
		assertThat(this.store.count("session-1")).isEqualTo(2);

		this.store.storeAll("session-1", List.of("a", "b", "c"));
		assertThat(this.store.count("session-1")).isEqualTo(5);
	}

	// ========== Category-aware tests ==========

	@Test
	@DisplayName("Should isolate artifacts by category within same session")
	void shouldIsolateArtifactsByCategoryWithinSameSession() {
		this.store.store("session-1", "browser", "screenshot-1");
		this.store.store("session-1", "codeinterpreter", "chart-1");
		this.store.store("session-1", "browser", "screenshot-2");

		List<String> browserArtifacts = this.store.retrieve("session-1", "browser");
		List<String> codeArtifacts = this.store.retrieve("session-1", "codeinterpreter");

		assertThat(browserArtifacts).containsExactly("screenshot-1", "screenshot-2");
		assertThat(codeArtifacts).containsExactly("chart-1");
	}

	@Test
	@DisplayName("Should storeAll with category")
	void shouldStoreAllWithCategory() {
		this.store.storeAll("session-1", "browser", List.of("s1", "s2", "s3"));

		List<String> result = this.store.retrieve("session-1", "browser");

		assertThat(result).containsExactly("s1", "s2", "s3");
		assertThat(this.store.retrieve("session-1")).isNull(); // default category empty
	}

	@Test
	@DisplayName("Should hasArtifacts check specific category")
	void shouldHasArtifactsCheckSpecificCategory() {
		this.store.store("session-1", "browser", "artifact-1");

		assertThat(this.store.hasArtifacts("session-1", "browser")).isTrue();
		assertThat(this.store.hasArtifacts("session-1", "codeinterpreter")).isFalse();
		assertThat(this.store.hasArtifacts("session-1")).isFalse(); // default category
	}

	@Test
	@DisplayName("Should count artifacts for specific category")
	void shouldCountArtifactsForSpecificCategory() {
		this.store.store("session-1", "browser", "s1");
		this.store.store("session-1", "browser", "s2");
		this.store.store("session-1", "codeinterpreter", "c1");

		assertThat(this.store.count("session-1", "browser")).isEqualTo(2);
		assertThat(this.store.count("session-1", "codeinterpreter")).isEqualTo(1);
		assertThat(this.store.count("session-1")).isZero(); // default category
	}

	@Test
	@DisplayName("Should peek artifacts for specific category")
	void shouldPeekArtifactsForSpecificCategory() {
		this.store.store("session-1", "browser", "s1");
		this.store.store("session-1", "browser", "s2");

		List<String> peeked = this.store.peek("session-1", "browser");

		assertThat(peeked).containsExactly("s1", "s2");
		assertThat(this.store.hasArtifacts("session-1", "browser")).isTrue();
	}

	@Test
	@DisplayName("Should clear artifacts for specific category only")
	void shouldClearArtifactsForSpecificCategoryOnly() {
		this.store.store("session-1", "browser", "s1");
		this.store.store("session-1", "codeinterpreter", "c1");

		this.store.clear("session-1", "browser");

		assertThat(this.store.hasArtifacts("session-1", "browser")).isFalse();
		assertThat(this.store.hasArtifacts("session-1", "codeinterpreter")).isTrue();
	}

	@Test
	@DisplayName("Should use default category when category is null")
	void shouldUseDefaultCategoryWhenCategoryIsNull() {
		this.store.store("session-1", null, "artifact-1");

		assertThat(this.store.hasArtifacts("session-1")).isTrue();
		assertThat(this.store.retrieve("session-1")).containsExactly("artifact-1");
	}

	@Test
	@DisplayName("Should use default category when category is blank")
	void shouldUseDefaultCategoryWhenCategoryIsBlank() {
		this.store.store("session-1", "   ", "artifact-1");

		assertThat(this.store.hasArtifacts("session-1")).isTrue();
		assertThat(this.store.retrieve("session-1")).containsExactly("artifact-1");
	}

}
