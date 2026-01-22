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

package org.springaicommunity.agentcore.memory;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(AgentCoreLongMemoryProperties.CONFIG_PREFIX)
public class AgentCoreLongMemoryProperties {

	public static final String CONFIG_PREFIX = "agentcore.memory.long-term";

	private final Episodic episodic;

	private final Semantic semantic;

	private final Summary summary;

	private final UserPreference userPreference;

	public AgentCoreLongMemoryProperties(Episodic episodic, Semantic semantic, Summary summary,
			UserPreference userPreference) {
		this.episodic = episodic;
		this.semantic = semantic;
		this.summary = summary;
		this.userPreference = userPreference;
	}

	public Episodic episodic() {
		return episodic;
	}

	public Semantic semantic() {
		return semantic;
	}

	public Summary summary() {
		return summary;
	}

	public UserPreference userPreference() {
		return userPreference;
	}

	public record Episodic(String strategyId, String reflectionsStrategyId, int episodesTopK, int reflectionsTopK,
			AgentCoreLongMemoryScope scope) implements AgentCoreLongMemoryStrategy {

		public static final String CONFIG_PREFIX = AgentCoreLongMemoryProperties.CONFIG_PREFIX + ".episodic";

		public Episodic {
			episodesTopK = episodesTopK > 0 ? episodesTopK : 3;
			reflectionsTopK = reflectionsTopK > 0 ? reflectionsTopK : 2;
			scope = scope != null ? scope : AgentCoreLongMemoryScope.ACTOR;
		}

		/**
		 * Check if reflections are enabled (separate strategy configured).
		 */
		public boolean hasReflections() {
			return reflectionsStrategyId != null && !reflectionsStrategyId.isEmpty();
		}

	}

	public record Semantic(String strategyId, int topK,
			AgentCoreLongMemoryScope scope) implements AgentCoreLongMemoryStrategy {

		public static final String CONFIG_PREFIX = AgentCoreLongMemoryProperties.CONFIG_PREFIX + ".semantic";

		public Semantic {
			topK = topK > 0 ? topK : 3;
			scope = scope != null ? scope : AgentCoreLongMemoryScope.ACTOR;
		}

	}

	public record Summary(String strategyId, int topK,
			AgentCoreLongMemoryScope scope) implements AgentCoreLongMemoryStrategy {

		public static final String CONFIG_PREFIX = AgentCoreLongMemoryProperties.CONFIG_PREFIX + ".summary";

		public Summary {
			topK = topK > 0 ? topK : 3;
			scope = scope != null ? scope : AgentCoreLongMemoryScope.SESSION;
		}

	}

	public record UserPreference(String strategyId,
			AgentCoreLongMemoryScope scope) implements AgentCoreLongMemoryStrategy {

		public static final String CONFIG_PREFIX = AgentCoreLongMemoryProperties.CONFIG_PREFIX + ".user-preference";

		public UserPreference {
			scope = scope != null ? scope : AgentCoreLongMemoryScope.ACTOR;
		}

	}

}
