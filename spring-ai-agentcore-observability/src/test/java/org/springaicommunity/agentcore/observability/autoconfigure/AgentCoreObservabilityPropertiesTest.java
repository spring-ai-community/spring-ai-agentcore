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

package org.springaicommunity.agentcore.observability.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySource;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

class AgentCoreObservabilityPropertiesTest {

	@Test
	void bindsEnabledDefault() {
		ConfigurationPropertySource src = new MapConfigurationPropertySource(Map.of());
		AgentCoreObservabilityProperties p = new Binder(src)
			.bind("spring.ai.agentcore.observability", AgentCoreObservabilityProperties.class)
			.orElseGet(AgentCoreObservabilityProperties::new);
		assertThat(p.isEnabled()).isTrue();
	}

	@Test
	void bindsEnabledExplicit() {
		ConfigurationPropertySource src = new MapConfigurationPropertySource(
				Map.of("spring.ai.agentcore.observability.enabled", "false"));
		AgentCoreObservabilityProperties p = new Binder(src)
			.bind("spring.ai.agentcore.observability", AgentCoreObservabilityProperties.class)
			.orElseThrow(IllegalStateException::new);
		assertThat(p.isEnabled()).isFalse();
	}

}
