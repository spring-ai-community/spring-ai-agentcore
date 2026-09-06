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

package org.springaicommunity.agentcore.memory.session;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

/**
 * Startup diagnostic that emits a WARN when the operator has opted into the Session API
 * stack ({@code agentcore.memory.session.enabled=true}) but the
 * {@code org.springaicommunity:spring-ai-session-management} artifact is not on the
 * classpath. Prevents the silent no-op that would otherwise result from
 * {@code @ConditionalOnClass(SessionRepository.class)} on the main session auto-config.
 * See issue #152 (finding I6).
 *
 * @author Spring AI Community
 */
@AutoConfiguration
@ConditionalOnMissingClass("org.springframework.ai.session.SessionRepository")
@ConditionalOnProperty(prefix = AgentCoreSessionProperties.CONFIG_PREFIX, name = "enabled", havingValue = "true")
public class AgentCoreSessionMissingDepDiagnostics implements InitializingBean {

	private static final Logger logger = LoggerFactory.getLogger(AgentCoreSessionMissingDepDiagnostics.class);

	@Override
	public void afterPropertiesSet() {
		logger.warn("Property 'agentcore.memory.session.enabled=true' is set but "
				+ "'org.springaicommunity:spring-ai-session-management' is not on the classpath. "
				+ "No session beans (AgentCoreSessionRepository, DefaultSessionService, SessionMemoryAdvisor) "
				+ "will be created. Add the dependency to your pom.xml. See "
				+ "https://github.com/spring-ai-community/spring-ai-agentcore/issues/152");
	}

}
