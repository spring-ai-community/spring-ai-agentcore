package org.springaicommunity.agentcore.memory;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class AgentCoreShortTermMemoryRepositoryConfigurationTest {

	@Test
	void shouldHaveDefaultValues() {
		var config = new AgentCoreMemoryProperties(null, null, "default-session", 100, false);

		assertThat(config.defaultSession()).isEqualTo("default-session");
		assertThat(config.pageSize()).isEqualTo(100);
	}

	@Test
	void shouldCreateWithAllProperties() {
		var config = new AgentCoreMemoryProperties("test-memory-id", 500, "custom-session", 50, true);

		assertThat(config.memoryId()).isEqualTo("test-memory-id");
		assertThat(config.totalEventsLimit()).isEqualTo(500);
		assertThat(config.defaultSession()).isEqualTo("custom-session");
		assertThat(config.pageSize()).isEqualTo(50);
		assertThat(config.ignoreUnknownRoles()).isTrue();
	}

}
