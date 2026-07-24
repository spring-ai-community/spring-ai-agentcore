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

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springaicommunity.agentcore.memory.AgentCoreMemoryConversationIdParser;
import org.springaicommunity.agentcore.memory.AgentCoreMemoryProperties;
import org.springaicommunity.agentcore.memory.longterm.AgentCoreLongTermMemoryAdvisor;
import org.springaicommunity.agentcore.memory.shortterm.AgentCoreShortTermMemoryProperties;
import org.springaicommunity.agentcore.memory.shortterm.AgentCoreShortTermMemoryRepositoryAutoConfiguration;
import org.springaicommunity.agentcore.memory.shortterm.ShortTermPropertyResolver;
import software.amazon.awssdk.services.bedrockagentcore.BedrockAgentCoreClient;

import org.springframework.ai.session.DefaultSessionService;
import org.springframework.ai.session.SessionRepository;
import org.springframework.ai.session.SessionService;
import org.springframework.ai.session.advisor.SessionMemoryAdvisor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Auto-configuration for the Spring AI Session API bean stack backed by AgentCore. Only
 * enabled when {@code agentcore.memory.session.enabled=true}, the session artifact is on
 * the classpath, the {@code BedrockAgentCoreClient} bean is present (produced by the
 * short-term memory auto-config), and {@code agentcore.memory.memory-id} is set.
 *
 * <p>
 * Sits alongside the ChatMemory-based stack. Consumers on the ChatMemory path see no
 * behavior change. See section 4.1 of the migration spec and issue #152.
 *
 * @author Spring AI Community
 */
@AutoConfiguration(after = AgentCoreShortTermMemoryRepositoryAutoConfiguration.class)
@ConditionalOnClass({ SessionRepository.class, SessionMemoryAdvisor.class })
@ConditionalOnBean(BedrockAgentCoreClient.class)
@ConditionalOnProperty(prefix = AgentCoreMemoryProperties.CONFIG_PREFIX, name = "memory-id")
@ConditionalOnProperty(prefix = AgentCoreSessionProperties.CONFIG_PREFIX, name = "enabled", havingValue = "true")
@EnableConfigurationProperties({ AgentCoreMemoryProperties.class, AgentCoreShortTermMemoryProperties.class,
		AgentCoreSessionProperties.class })
public class AgentCoreSessionRepositoryAutoConfiguration {

	private static final Logger logger = LoggerFactory.getLogger(AgentCoreSessionRepositoryAutoConfiguration.class);

	@Bean
	@ConditionalOnMissingBean
	AgentCoreSessionRepository agentCoreSessionRepository(AgentCoreMemoryProperties memory,
			AgentCoreShortTermMemoryProperties shortTerm, AgentCoreSessionProperties session,
			BedrockAgentCoreClient client) {
		logger.info("Creating AgentCoreSessionRepository bean with memoryId: {}", memory.memoryId());

		// Resolution order for each tunable: session -> short-term -> legacy -> default.
		// The session namespace is the session adopter's front door; when a session value
		// is null we defer to the existing short-term/legacy resolver (unchanged wording,
		// unchanged deprecation warnings).
		Integer totalEventsLimit = sessionFirst("total-events-limit", session.totalEventsLimit(),
				shortTerm.totalEventsLimit(), () -> ShortTermPropertyResolver.resolve("total-events-limit",
						shortTerm.totalEventsLimit(), memory.totalEventsLimit(), null));
		String defaultSession = sessionFirst("default-session", session.defaultSession(), shortTerm.defaultSession(),
				() -> ShortTermPropertyResolver.resolve("default-session", shortTerm.defaultSession(),
						memory.defaultSession(), AgentCoreMemoryConversationIdParser.DEFAULT_SESSION));
		int pageSize = sessionFirst("page-size", session.pageSize(), shortTerm.pageSize(),
				() -> ShortTermPropertyResolver.resolve("page-size", shortTerm.pageSize(), memory.pageSize(),
						ShortTermPropertyResolver.DEFAULT_PAGE_SIZE));
		boolean ignoreUnknownRoles = sessionFirst("ignore-unknown-roles", session.ignoreUnknownRoles(),
				shortTerm.ignoreUnknownRoles(), () -> ShortTermPropertyResolver.resolve("ignore-unknown-roles",
						shortTerm.ignoreUnknownRoles(), memory.ignoreUnknownRoles(), Boolean.TRUE));
		ShortTermPropertyResolver.warnIfIgnoreUnknownRolesExplicitlySet(shortTerm, memory);

		boolean persistSynthetic = (session.persistSynthetic() != null) && session.persistSynthetic();
		boolean branchSwapEnabled = (session.branchSwapEnabled() != null) && session.branchSwapEnabled();
		boolean deleteSupersededBranch = (session.deleteSupersededBranch() != null) && session.deleteSupersededBranch();
		boolean branchCacheEnabled = (session.branchCacheEnabled() != null) && session.branchCacheEnabled();

		return new AgentCoreSessionRepository(memory.memoryId(), client, totalEventsLimit, defaultSession, pageSize,
				ignoreUnknownRoles, persistSynthetic, branchSwapEnabled, deleteSupersededBranch, branchCacheEnabled,
				session.branchCacheTtl());
	}

	// Returns the session-scoped value when it is set, otherwise the short-term/legacy
	// fallback. Emits a DEBUG breadcrumb when both the session and short-term namespaces
	// explicitly set the same tunable, so an operator can see which one won (session).
	private static <T> T sessionFirst(String name, T sessionValue, T shortTermValue,
			java.util.function.Supplier<T> fallback) {
		if (sessionValue != null) {
			if (shortTermValue != null) {
				logger
					.debug("Property '{}' is set in both agentcore.memory.session.* and agentcore.memory.short-term.*;"
							+ " the session value '{}' wins.", name, sessionValue);
			}
			return sessionValue;
		}
		return fallback.get();
	}

	@Bean
	@ConditionalOnMissingBean(SessionService.class)
	DefaultSessionService sessionService(SessionRepository sessionRepository) {
		return DefaultSessionService.builder().sessionRepository(sessionRepository).build();
	}

	@Bean
	@ConditionalOnMissingBean(SessionMemoryAdvisor.class)
	SessionMemoryAdvisor sessionMemoryAdvisor(SessionService sessionService, AgentCoreSessionProperties props) {
		SessionMemoryAdvisor.Builder builder = SessionMemoryAdvisor.builder(sessionService);
		if (props.defaultUserId() != null && !props.defaultUserId().isBlank()) {
			builder.defaultUserId(props.defaultUserId());
		}
		return builder.build();
	}

	@Bean
	@ConditionalOnMissingBean
	AgentCoreSessionMemory agentCoreSessionMemory(SessionMemoryAdvisor sessionMemoryAdvisor,
			List<AgentCoreLongTermMemoryAdvisor> ltmAdvisors) {
		return new AgentCoreSessionMemory(sessionMemoryAdvisor, ltmAdvisors);
	}

}
