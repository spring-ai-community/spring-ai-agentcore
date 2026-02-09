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

package org.springaicommunity.agentcore.autoconfigure;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springaicommunity.agentcore.controller.AgentCoreInvocationsController;
import org.springaicommunity.agentcore.controller.AgentCorePingController;
import org.springaicommunity.agentcore.service.AgentCoreMethodInvoker;
import org.springaicommunity.agentcore.service.AgentCoreMethodRegistry;
import org.springaicommunity.agentcore.service.AgentCoreMethodScanner;

import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class AgentCoreAutoConfigurationTest {

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(AgentCoreAutoConfiguration.class));

	@Test
	void shouldCreateAllBeansWhenAgentCoreInvocationIsPresent() {
		contextRunner.run(context -> {
			assertThat(context).hasSingleBean(ObjectMapper.class);
			assertThat(context).hasSingleBean(AgentCoreMethodRegistry.class);
			assertThat(context).hasSingleBean(AgentCoreMethodScanner.class);
			assertThat(context).hasSingleBean(AgentCoreMethodInvoker.class);
			assertThat(context).hasSingleBean(AgentCoreInvocationsController.class);
			assertThat(context).hasSingleBean(AgentCorePingController.class);
		});
	}

	@Test
	void shouldWireBeansCorrectly() {
		contextRunner.run(context -> {
			var scanner = context.getBean(AgentCoreMethodScanner.class);
			var invoker = context.getBean(AgentCoreMethodInvoker.class);
			var controller = context.getBean(AgentCoreInvocationsController.class);

			assertThat(scanner).isNotNull();
			assertThat(invoker).isNotNull();
			assertThat(controller).isNotNull();
		});
	}

	@Test
	void shouldAllowCustomObjectMapperOverride() {
		contextRunner.withUserConfiguration(CustomObjectMapperConfiguration.class).run(context -> {
			assertThat(context).hasSingleBean(ObjectMapper.class);
			assertThat(context.getBean(ObjectMapper.class)).isSameAs(context.getBean("customObjectMapper"));
		});
	}

	@Test
	void shouldAllowCustomRegistryOverride() {
		contextRunner.withUserConfiguration(CustomRegistryConfiguration.class).run(context -> {
			assertThat(context).hasSingleBean(AgentCoreMethodRegistry.class);
			assertThat(context.getBean(AgentCoreMethodRegistry.class)).isSameAs(context.getBean("customRegistry"));
		});
	}

	@Configuration
	static class CustomObjectMapperConfiguration {

		@Bean
		public ObjectMapper customObjectMapper() {
			return new ObjectMapper();
		}

	}

	@Configuration
	static class CustomRegistryConfiguration {

		@Bean
		public AgentCoreMethodRegistry customRegistry() {
			return new AgentCoreMethodRegistry();
		}

	}

}
