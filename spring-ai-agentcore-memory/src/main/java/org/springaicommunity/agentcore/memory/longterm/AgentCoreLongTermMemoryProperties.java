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

package org.springaicommunity.agentcore.memory.longterm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(AgentCoreLongTermMemoryProperties.CONFIG_PREFIX)
public class AgentCoreLongTermMemoryProperties {

	/**
	 * configuration prefix for long-term memory properties.
	 */
	public static final String CONFIG_PREFIX = "agentcore.memory.long-term";

	private final boolean autoDiscovery;

	private final Namespace namespace;

	private final Episodic episodic;

	private final Semantic semantic;

	private final Summary summary;

	private final UserPreference userPreference;

	public AgentCoreLongTermMemoryProperties(boolean autoDiscovery, Namespace namespace, Episodic episodic,
			Semantic semantic, Summary summary, UserPreference userPreference) {
		this.autoDiscovery = autoDiscovery;
		this.namespace = (namespace != null) ? namespace : new Namespace(false);
		this.episodic = episodic;
		this.semantic = semantic;
		this.summary = summary;
		this.userPreference = userPreference;
	}

	public boolean autoDiscovery() {
		return this.autoDiscovery;
	}

	public Namespace namespace() {
		return this.namespace;
	}

	public Episodic episodic() {
		return this.episodic;
	}

	public Semantic semantic() {
		return this.semantic;
	}

	public Summary summary() {
		return this.summary;
	}

	public UserPreference userPreference() {
		return this.userPreference;
	}

	/**
	 * Returns the typed per-strategy config record that corresponds to the given memory
	 * strategy kind, or {@code null} if no config applies.
	 * {@link AgentCoreLongTermMemoryStrategyType#CUSTOM} has no matching config record by
	 * design — user-defined handlers provide their own configuration.
	 * @param kind the memory strategy kind
	 * @return the matching strategy config record, or {@code null} if none applies
	 */
	public AgentCoreLongTermMemoryStrategy byKind(AgentCoreLongTermMemoryStrategyType kind) {
		return switch (kind) {
			case SEMANTIC -> this.semantic;
			case USER_PREFERENCE -> this.userPreference;
			case SUMMARY -> this.summary;
			case EPISODIC -> this.episodic;
			case CUSTOM -> null;
		};
	}

	public record Episodic(String strategyId, String reflectionsStrategyId, int episodesTopK, int reflectionsTopK,
			AgentCoreLongTermMemoryNamespace namespace, String namespacePattern,
			AgentCoreLongTermMemoryNamespace reflectionsNamespace,
			String reflectionsNamespacePattern) implements AgentCoreLongTermMemoryStrategy {

		/**
		 * configuration prefix for the episodic strategy.
		 */
		public static final String CONFIG_PREFIX = AgentCoreLongTermMemoryProperties.CONFIG_PREFIX + ".episodic";

		private static final Logger logger = LoggerFactory.getLogger(Episodic.class);

		public Episodic {
			episodesTopK = (episodesTopK > 0) ? episodesTopK : 3;
			reflectionsTopK = (reflectionsTopK > 0) ? reflectionsTopK : 2;
			namespace = (namespace != null) ? namespace : AgentCoreLongTermMemoryNamespace.ACTOR;
			if (reflectionsStrategyId != null && !reflectionsStrategyId.isEmpty()) {
				boolean hasNamespaceOverride = (reflectionsNamespacePattern != null
						&& !reflectionsNamespacePattern.isEmpty()) || reflectionsNamespace != null;
				logger.warn(buildDeprecatedReflectionsWarning(hasNamespaceOverride));
			}
		}

		private static String buildDeprecatedReflectionsWarning(boolean hasNamespaceOverride) {
			String message = "'reflections-strategy-id' is deprecated and will be removed in a future release. "
					+ "In AWS AgentCore Memory, reflections are a namespace under the same episodic strategy, "
					+ "not a separate strategy. Migrate to 'reflections-namespace-pattern' or "
					+ "'reflections-namespace'. See: "
					+ "https://docs.aws.amazon.com/bedrock-agentcore/latest/devguide/"
					+ "episodic-memory-strategy.html";
			if (hasNamespaceOverride) {
				message += " (Note: a reflections namespace is also set and takes precedence.)";
			}
			return message;
		}

		@Override
		public String resolveNamespacePattern() {
			return (this.namespacePattern != null && !this.namespacePattern.isEmpty()) ? this.namespacePattern
					: this.namespace.getPattern();
		}

		/**
		 * Returns the deprecated reflections strategy id.
		 * @deprecated Reflections in AWS AgentCore Memory are a namespace of the same
		 * episodic strategy, not a separate strategy. Use {@link #reflectionsNamespace()}
		 * or {@link #reflectionsNamespacePattern()} instead. Kept for one release for
		 * backward compatibility; will be removed.
		 */
		@Deprecated(forRemoval = true)
		@Override
		public String reflectionsStrategyId() {
			return this.reflectionsStrategyId;
		}

		/**
		 * Resolves the reflections namespace pattern using precedence:
		 * {@code reflectionsNamespacePattern} &gt; {@code reflectionsNamespace} &gt;
		 * {@code null} (no reflections).
		 * @return the reflections namespace pattern, or {@code null} if reflections are
		 * disabled via the modern config
		 */
		public String resolveReflectionsNamespacePattern() {
			if (this.reflectionsNamespacePattern != null && !this.reflectionsNamespacePattern.isEmpty()) {
				return this.reflectionsNamespacePattern;
			}
			if (this.reflectionsNamespace != null) {
				return this.reflectionsNamespace.getPattern();
			}
			return null;
		}

		/**
		 * Returns true if reflections are configured via the deprecated separate-strategy
		 * path and no modern configuration overrides it. Advisor + auto-config branch on
		 * this to keep legacy behaviour alive while warning.
		 * @return {@code true} if the legacy reflections strategy path is in use
		 */
		public boolean usesLegacyReflectionsStrategy() {
			return this.resolveReflectionsNamespacePattern() == null && this.reflectionsStrategyId != null
					&& !this.reflectionsStrategyId.isEmpty();
		}

	}

	public record Semantic(String strategyId, int topK, AgentCoreLongTermMemoryNamespace namespace,
			String namespacePattern) implements AgentCoreLongTermMemoryStrategy {

		/**
		 * configuration prefix for the semantic strategy.
		 */
		public static final String CONFIG_PREFIX = AgentCoreLongTermMemoryProperties.CONFIG_PREFIX + ".semantic";

		public Semantic {
			topK = (topK > 0) ? topK : 3;
			namespace = (namespace != null) ? namespace : AgentCoreLongTermMemoryNamespace.ACTOR;
		}

		@Override
		public String resolveNamespacePattern() {
			return (this.namespacePattern != null && !this.namespacePattern.isEmpty()) ? this.namespacePattern
					: this.namespace.getPattern();
		}

	}

	public record Summary(String strategyId, int topK, AgentCoreLongTermMemoryNamespace namespace,
			String namespacePattern) implements AgentCoreLongTermMemoryStrategy {

		/**
		 * configuration prefix for the summary strategy.
		 */
		public static final String CONFIG_PREFIX = AgentCoreLongTermMemoryProperties.CONFIG_PREFIX + ".summary";

		public Summary {
			topK = (topK > 0) ? topK : 3;
			namespace = (namespace != null) ? namespace : AgentCoreLongTermMemoryNamespace.SESSION;
		}

		@Override
		public String resolveNamespacePattern() {
			return (this.namespacePattern != null && !this.namespacePattern.isEmpty()) ? this.namespacePattern
					: this.namespace.getPattern();
		}

	}

	public record UserPreference(String strategyId, AgentCoreLongTermMemoryNamespace namespace,
			String namespacePattern) implements AgentCoreLongTermMemoryStrategy {

		/**
		 * configuration prefix for the user-preference strategy.
		 */
		public static final String CONFIG_PREFIX = AgentCoreLongTermMemoryProperties.CONFIG_PREFIX + ".user-preference";

		public UserPreference {
			namespace = (namespace != null) ? namespace : AgentCoreLongTermMemoryNamespace.ACTOR;
		}

		@Override
		public String resolveNamespacePattern() {
			return (this.namespacePattern != null && !this.namespacePattern.isEmpty()) ? this.namespacePattern
					: this.namespace.getPattern();
		}

	}

	public record Namespace(boolean autoRegister) {
	}

}
