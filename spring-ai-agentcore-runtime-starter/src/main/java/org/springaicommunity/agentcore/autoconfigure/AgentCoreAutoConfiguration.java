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
import org.springaicommunity.agentcore.annotation.AgentCoreInvocation;
import org.springaicommunity.agentcore.controller.AgentCoreInvocationsController;
import org.springaicommunity.agentcore.controller.AgentCoreInvocationsHandler;
import org.springaicommunity.agentcore.controller.AgentCorePingController;
import org.springaicommunity.agentcore.controller.AgentCorePingHandler;
import org.springaicommunity.agentcore.ping.AgentCorePingService;
import org.springaicommunity.agentcore.ping.AgentCoreTaskTracker;
import org.springaicommunity.agentcore.service.AgentCoreMethodInvoker;
import org.springaicommunity.agentcore.service.AgentCoreMethodRegistry;
import org.springaicommunity.agentcore.service.AgentCoreMethodScanner;
import org.springaicommunity.agentcore.throttle.ThrottleConfiguration;

import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Lazy;
import org.springframework.web.bind.annotation.RestController;

/**
 * Auto-configuration for AgentCore runtime support. Automatically configures all
 * necessary beans when AgentCoreInvocation is on the classpath.
 */
@Configuration
@ConditionalOnClass({ AgentCoreInvocation.class, RestController.class })
@Import({ AgentCorePingAutoConfiguration.class, AgentCoreActuatorAutoConfiguration.class, ThrottleConfiguration.class })
public class AgentCoreAutoConfiguration {

	@Bean
	@ConditionalOnMissingBean
	public ObjectMapper objectMapper() {
		return new ObjectMapper();
	}

	@Bean
	@ConditionalOnMissingBean
	public AgentCoreMethodInvoker agentCoreMethodInvoker(ObjectMapper mapper, AgentCoreMethodRegistry registry) {
		return new AgentCoreMethodInvoker(mapper, registry);
	}

	@Bean
	@ConditionalOnMissingBean(AgentCoreInvocationsHandler.class)
	public AgentCoreInvocationsController agentCoreController(AgentCoreMethodInvoker invoker) {
		return new AgentCoreInvocationsController(invoker);
	}

	@Bean
	@ConditionalOnMissingBean
	public AgentCoreTaskTracker agentCoreTaskTracker() {
		return new AgentCoreTaskTracker();
	}

	@Bean
	@ConditionalOnMissingBean(AgentCorePingHandler.class)
	public AgentCorePingController agentCoreHealthController(AgentCorePingService agentCorePingService) {
		return new AgentCorePingController(agentCorePingService);
	}

	@Bean
	@ConditionalOnMissingBean
	public static AgentCoreMethodRegistry agentCoreMethodRegistry() {
		return new AgentCoreMethodRegistry();
	}

	@Bean
	@ConditionalOnMissingBean
	public static AgentCoreMethodScanner agentCoreMethodScanner(@Lazy AgentCoreMethodRegistry registry) {
		return new AgentCoreMethodScanner(registry);
	}

}
