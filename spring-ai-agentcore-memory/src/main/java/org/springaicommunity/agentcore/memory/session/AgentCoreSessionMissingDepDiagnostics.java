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

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.BeanClassLoaderAware;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.util.ClassUtils;

/**
 * Startup diagnostic that emits a WARN when the operator has opted into the Session API
 * stack ({@code agentcore.memory.session.enabled=true}) but
 * {@code org.springframework.ai:spring-ai-session} or
 * {@code org.springframework.ai:spring-ai-session-advisor} is not on the classpath.
 * Prevents the silent no-op that would otherwise result from {@code @ConditionalOnClass}
 * on the main session auto-config. See issue #152 (finding I6).
 *
 * @author Spring AI Community
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = AgentCoreSessionProperties.CONFIG_PREFIX, name = "enabled", havingValue = "true")
public class AgentCoreSessionMissingDepDiagnostics implements InitializingBean, BeanClassLoaderAware {

	private static final Logger logger = LoggerFactory.getLogger(AgentCoreSessionMissingDepDiagnostics.class);

	// The application's class loader, which the session auto-config conditions use too
	private ClassLoader classLoader = AgentCoreSessionMissingDepDiagnostics.class.getClassLoader();

	@Override
	public void setBeanClassLoader(ClassLoader classLoader) {
		this.classLoader = classLoader;
	}

	@Override
	public void afterPropertiesSet() {
		ClassLoader loader = this.classLoader;
		List<String> missing = new ArrayList<>();
		if (!ClassUtils.isPresent("org.springframework.ai.session.SessionRepository", loader)) {
			missing.add("org.springframework.ai:spring-ai-session");
		}
		if (!ClassUtils.isPresent("org.springframework.ai.session.advisor.SessionMemoryAdvisor", loader)) {
			missing.add("org.springframework.ai:spring-ai-session-advisor");
		}
		if (missing.isEmpty()) {
			return;
		}
		logger.warn("Property 'agentcore.memory.session.enabled=true' is set but {} {} not on the classpath. "
				+ "No session beans (AgentCoreSessionRepository, DefaultSessionService, SessionMemoryAdvisor) "
				+ "will be created. Add {} (versions managed by org.springframework.ai:spring-ai-bom 2.1); they "
				+ "replace the community org.springaicommunity:spring-ai-session artifact. See "
				+ "https://github.com/spring-ai-community/spring-ai-agentcore/issues/152",
				String.join(" and ", missing), (missing.size() == 1) ? "is" : "are", String.join(" and ", missing));
	}

}
