package org.springaicommunity.agentcore.memory.longterm;

import java.util.Map;

import org.springaicommunity.agentcore.memory.longterm.AgentCoreLongTermMemoryAdvisor.MemoryStrategy;
import org.springaicommunity.agentcore.memory.longterm.AgentCoreLongTermMemoryStrategyDiscovery.StrategyType;

/**
 * Shared configuration for memory strategy types and their context labels.
 */
public final class MemoryStrategiesMap {

	private MemoryStrategiesMap() {
	}

	public record StrategyLabel(MemoryStrategy strategy, String contextLabel) {
	}

	public static final Map<StrategyType, StrategyLabel> LABELS = Map.of(StrategyType.SEMANTIC,
			new StrategyLabel(MemoryStrategy.SEMANTIC, "Known facts about the user (use naturally in conversation)"),
			StrategyType.USER_PREFERENCE,
			new StrategyLabel(MemoryStrategy.USER_PREFERENCE, "User preferences (apply when relevant)"),
			StrategyType.SUMMARY,
			new StrategyLabel(MemoryStrategy.SUMMARY, "Previous conversation summaries (use for continuity)"),
			StrategyType.EPISODIC,
			new StrategyLabel(MemoryStrategy.EPISODIC, "Past interactions and reflections (reference when relevant)"));

	public static String getContextLabel(StrategyType type) {
		return LABELS.get(type).contextLabel();
	}

}
