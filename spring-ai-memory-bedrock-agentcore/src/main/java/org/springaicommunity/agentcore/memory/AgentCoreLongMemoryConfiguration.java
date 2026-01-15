package org.springaicommunity.agentcore.memory;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for long-term memory strategies.
 *
 * @author Yuriy Bezsonov
 */
@ConfigurationProperties(prefix = "agentcore.memory.long-term")
public record AgentCoreLongMemoryConfiguration(StrategyConfig semantic, UserPreferenceConfig userPreference,
		StrategyConfig summary, EpisodicConfig episodic) {

	/**
	 * Configuration for strategies that use semantic search (semantic, summary).
	 */
	public record StrategyConfig(String strategyId, int topK) {

		public StrategyConfig {
			topK = topK > 0 ? topK : 3;
		}

		public boolean isEnabled() {
			return strategyId != null && !strategyId.isEmpty();
		}

	}

	/**
	 * Configuration for user preference strategy. No topK because preferences are listed
	 * (not searched) to ensure all preferences apply regardless of query relevance.
	 */
	public record UserPreferenceConfig(String strategyId) {

		public boolean isEnabled() {
			return strategyId != null && !strategyId.isEmpty();
		}

	}

	public record EpisodicConfig(String strategyId, int episodesTopK, int reflectionsTopK) {

		public EpisodicConfig {
			episodesTopK = episodesTopK > 0 ? episodesTopK : 3;
			reflectionsTopK = reflectionsTopK > 0 ? reflectionsTopK : 2;
		}

		public boolean isEnabled() {
			return strategyId != null && !strategyId.isEmpty();
		}

	}

}
