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

package org.springaicommunity.agentcore.memory.shortterm;

import org.junit.jupiter.api.Test;
import org.springaicommunity.agentcore.memory.AgentCoreMemoryProperties;
import org.springaicommunity.agentcore.memory.shorttem.AgentCoreShortTermMemoryProperties;

import static org.assertj.core.api.Assertions.assertThat;

public class AgentCoreShortTermMemoryRepositoryConfigurationTests {

	@Test
	void shouldExposeRawValuesOnLegacyRecord() {
		var config = new AgentCoreMemoryProperties(null, null, "default-session", 100, false);

		assertThat(config.defaultSession()).isEqualTo("default-session");
		assertThat(config.pageSize()).isEqualTo(100);
	}

	@Test
	void shouldCreateWithAllLegacyProperties() {
		var config = new AgentCoreMemoryProperties("test-memory-id", 500, "custom-session", 50, true);

		assertThat(config.memoryId()).isEqualTo("test-memory-id");
		assertThat(config.totalEventsLimit()).isEqualTo(500);
		assertThat(config.defaultSession()).isEqualTo("custom-session");
		assertThat(config.pageSize()).isEqualTo(50);
		assertThat(config.ignoreUnknownRoles()).isTrue();
	}

	@Test
	void shouldCreateShortTermPropertiesWithAllValues() {
		var stm = new AgentCoreShortTermMemoryProperties(500, "custom-session", 50, true);

		assertThat(stm.totalEventsLimit()).isEqualTo(500);
		assertThat(stm.defaultSession()).isEqualTo("custom-session");
		assertThat(stm.pageSize()).isEqualTo(50);
		assertThat(stm.ignoreUnknownRoles()).isTrue();
	}

	@Test
	void shouldExposeNullsOnUnsetShortTermProperties() {
		var stm = new AgentCoreShortTermMemoryProperties(null, null, null, null);

		assertThat(stm.totalEventsLimit()).isNull();
		assertThat(stm.defaultSession()).isNull();
		assertThat(stm.pageSize()).isNull();
		assertThat(stm.ignoreUnknownRoles()).isNull();
	}

}
