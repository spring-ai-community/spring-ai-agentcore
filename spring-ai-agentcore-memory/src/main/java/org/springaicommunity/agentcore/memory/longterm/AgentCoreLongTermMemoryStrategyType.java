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

import software.amazon.awssdk.services.bedrockagentcorecontrol.model.MemoryStrategyType;

/**
 * Discriminator for long-term memory strategies. The first four values ({@code SEMANTIC},
 * {@code USER_PREFERENCE}, {@code SUMMARY}, {@code EPISODIC}) correspond directly to the
 * built-in AWS AgentCore Memory strategy types. {@code CUSTOM} is advisor-side only:
 * user-defined strategy handlers plugged into {@link AgentCoreLongTermMemoryAdvisor}'s
 * builder without a matching AWS strategy type.
 *
 * <p>
 * Each value carries two fields used at runtime:
 * <ul>
 * <li>{@code order} — default advisor ordering (lower runs first), used by the advisor
 * builder when no explicit order is set.</li>
 * <li>{@code contextLabel} — the human-readable label prefixing the injected memory
 * section in the prompt, e.g. {@code "Known facts about the user..."}.</li>
 * </ul>
 *
 * @author Andrei Shakirin
 */
public enum AgentCoreLongTermMemoryStrategyType {

	/** known facts about the user. */
	SEMANTIC(100, "Known facts about the user (use naturally in conversation)"),

	/** user preferences applied during conversation. */
	USER_PREFERENCE(200, "User preferences (apply when relevant)"),

	/** previous conversation summaries used for continuity. */
	SUMMARY(300, "Previous conversation summaries (use for continuity)"),

	/** past interactions and reflections referenced when relevant. */
	EPISODIC(400, "Past interactions and reflections (reference when relevant)"),

	/**
	 * Advisor-side only: placeholder for user-defined strategy handlers with no
	 * corresponding AWS strategy type. Never produced by auto-discovery.
	 */
	CUSTOM(500, "");

	private final int order;

	private final String contextLabel;

	AgentCoreLongTermMemoryStrategyType(int order, String contextLabel) {
		this.order = order;
		this.contextLabel = contextLabel;
	}

	/**
	 * Returns the relative ordering of this strategy in injected prompt sections.
	 * @return the ordering value
	 */
	public int getOrder() {
		return this.order;
	}

	/**
	 * Human-readable label prefixing the injected memory section. Empty for
	 * {@link #CUSTOM}.
	 * @return the context label
	 */
	public String contextLabel() {
		return this.contextLabel;
	}

	/**
	 * Maps an AWS SDK {@link MemoryStrategyType} to this enum. Returns {@code null} for
	 * AWS types that have no direct advisor-side counterpart ({@code CUSTOM} on the AWS
	 * side, or unknown SDK versions) — callers should skip such strategies.
	 * @param awsType the AWS SDK strategy type
	 * @return the matching advisor-side strategy type, or {@code null}
	 */
	public static AgentCoreLongTermMemoryStrategyType fromAwsType(MemoryStrategyType awsType) {
		if (awsType == null) {
			return null;
		}
		return switch (awsType) {
			case SEMANTIC -> SEMANTIC;
			case SUMMARIZATION -> SUMMARY;
			case USER_PREFERENCE -> USER_PREFERENCE;
			case EPISODIC -> EPISODIC;
			case CUSTOM, UNKNOWN_TO_SDK_VERSION -> null;
		};
	}

}
