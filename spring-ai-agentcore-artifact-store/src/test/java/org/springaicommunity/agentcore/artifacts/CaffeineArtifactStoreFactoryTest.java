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

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link CaffeineArtifactStoreFactory}.
 *
 * @author Yuriy Bezsonov
 */
@DisplayName("CaffeineArtifactStoreFactory Tests")
class CaffeineArtifactStoreFactoryTest {

	private ArtifactStoreFactory factory;

	@BeforeEach
	void setUp() {
		factory = new CaffeineArtifactStoreFactory();
	}

	@Test
	@DisplayName("Should create store with name only using defaults")
	void shouldCreateStoreWithNameOnlyUsingDefaults() {
		ArtifactStore<GeneratedFile> store = factory.create("TestStore");

		assertThat(store).isNotNull();
		assertThat(store).isInstanceOf(CaffeineArtifactStore.class);
	}

	@Test
	@DisplayName("Should create store with custom TTL")
	void shouldCreateStoreWithCustomTtl() {
		ArtifactStore<GeneratedFile> store = factory.create("TestStore", 600);

		assertThat(store).isNotNull();
	}

	@Test
	@DisplayName("Should create store with full configuration")
	void shouldCreateStoreWithFullConfiguration() {
		ArtifactStore<GeneratedFile> store = factory.create("TestStore", 600, 5000);

		assertThat(store).isNotNull();
	}

	@Test
	@DisplayName("Should create functional store")
	void shouldCreateFunctionalStore() {
		ArtifactStore<GeneratedFile> store = factory.create("TestStore");
		GeneratedFile file = new GeneratedFile("text/plain", "test".getBytes(), "test.txt");

		store.store("session-1", file);

		assertThat(store.hasArtifacts("session-1")).isTrue();
		List<GeneratedFile> retrieved = store.retrieve("session-1");
		assertThat(retrieved).hasSize(1);
		assertThat(retrieved.get(0).name()).isEqualTo("test.txt");
	}

	@Test
	@DisplayName("Should verify default constants")
	void shouldVerifyDefaultConstants() {
		assertThat(ArtifactStoreFactory.DEFAULT_TTL_SECONDS).isEqualTo(300);
		assertThat(ArtifactStoreFactory.DEFAULT_MAX_SIZE).isEqualTo(10_000);
	}

}
