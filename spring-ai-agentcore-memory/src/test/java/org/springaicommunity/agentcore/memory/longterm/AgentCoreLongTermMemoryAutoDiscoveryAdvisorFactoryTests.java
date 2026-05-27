/*
 * Copyright 2025-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 */

package org.springaicommunity.agentcore.memory.longterm;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springaicommunity.agentcore.memory.longterm.AgentCoreLongTermMemoryProperties.Episodic;
import org.springaicommunity.agentcore.memory.longterm.AgentCoreLongTermMemoryProperties.Namespace;
import org.springaicommunity.agentcore.memory.longterm.AgentCoreLongTermMemoryStrategyDiscovery.DiscoveredStrategy;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Tests for {@link AgentCoreLongTermMemoryAutoDiscoveryAdvisorFactory}.
 *
 * <p>
 * Focus on the resolution-precedence matrix for reflections on the episodic strategy,
 * which is the only variant where discovered and explicit config can disagree. The
 * factory produces advisors; each produced advisor is driven with a chat request so we
 * can verify which retriever calls it makes.
 */
@ExtendWith(MockitoExtension.class)
class AgentCoreLongTermMemoryAutoDiscoveryAdvisorFactoryTests {

	private static final String MEMORY_ID = "mem-1";

	private static final String STRATEGY_ID = "ep-1";

	private static final String DISCOVERED_EPISODES_NS = "/strategy/{memoryStrategyId}/actor/{actorId}/";

	private static final String DISCOVERED_REFLECTIONS_NS = "/strategy/{memoryStrategyId}/";

	@Mock
	AgentCoreLongTermMemoryRetriever retriever;

	@Mock
	CallAdvisorChain chain;

	// ------------------------------------------------------------------
	// Episodic: reflection precedence matrix
	// ------------------------------------------------------------------

	@Test
	void episodicWithDiscoveredReflectionsNoExplicitConfig() {
		// Given: discovery turns up episodes + reflections; no explicit episodic config.
		DiscoveredStrategy discovered = episodicWithReflections(DISCOVERED_EPISODES_NS, DISCOVERED_REFLECTIONS_NS);
		AgentCoreLongTermMemoryProperties config = propertiesWithoutEpisodicExplicit();

		// When: factory creates the advisor and we drive one request.
		var advisor = this.factory(config).createAdvisors(List.of(discovered)).get(0);
		this.stubEmptyMemories();
		advisor.adviseCall(userRequest("how is the weather?"), this.chain);

		// Then: reflections retrieval uses the discovered namespace under the same
		// strategy.
		verify(this.retriever).searchMemories(eq(STRATEGY_ID), anyString(), anyString(), eq("how is the weather?"),
				anyInt(), eq(DISCOVERED_EPISODES_NS));
		verify(this.retriever).searchMemories(eq(STRATEGY_ID), anyString(), anyString(), eq("how is the weather?"),
				anyInt(), eq(DISCOVERED_REFLECTIONS_NS));
	}

	@Test
	void episodicExplicitReflectionsNamespacePatternWinsOverDiscovered() {
		// Given: discovery provides a reflections namespace; explicit config overrides
		// it.
		DiscoveredStrategy discovered = episodicWithReflections(DISCOVERED_EPISODES_NS, DISCOVERED_REFLECTIONS_NS);
		String explicitReflections = "/custom/reflections/";
		Episodic episodic = new Episodic(STRATEGY_ID, null, 3, 2, null, null, null, explicitReflections);
		AgentCoreLongTermMemoryProperties config = propertiesWithEpisodic(episodic);

		// When
		var advisor = this.factory(config).createAdvisors(List.of(discovered)).get(0);
		this.stubEmptyMemories();
		advisor.adviseCall(userRequest("q"), this.chain);

		// Then: reflections retrieval uses the explicit override, not the discovered one.
		verify(this.retriever).searchMemories(eq(STRATEGY_ID), anyString(), anyString(), eq("q"), anyInt(),
				eq(explicitReflections));
		verify(this.retriever, never()).searchMemories(eq(STRATEGY_ID), anyString(), anyString(), eq("q"), anyInt(),
				eq(DISCOVERED_REFLECTIONS_NS));
	}

	@Test
	void episodicExplicitLegacyReflectionsStrategyIdIsUsedWhenNoModernOverride() {
		// Given: discovery provides nothing for reflections; explicit legacy config only.
		DiscoveredStrategy discovered = episodicWithoutReflections(DISCOVERED_EPISODES_NS);
		String legacyReflectionsStrategy = "legacy-refl-1";
		Episodic episodic = new Episodic(STRATEGY_ID, legacyReflectionsStrategy, 3, 2, null, null, null, null);
		AgentCoreLongTermMemoryProperties config = propertiesWithEpisodic(episodic);

		// When
		var advisor = this.factory(config).createAdvisors(List.of(discovered)).get(0);
		this.stubEmptyMemories();
		advisor.adviseCall(userRequest("q"), this.chain);

		// Then: legacy strategy id is used with the episodes namespace.
		verify(this.retriever).searchMemories(eq(legacyReflectionsStrategy), anyString(), anyString(), eq("q"),
				anyInt(), eq(DISCOVERED_EPISODES_NS));
	}

	@Test
	void episodicExplicitModernReflectionsBeatsLegacyWhenBothPresent() {
		// Given: both legacy and modern reflection config are present.
		DiscoveredStrategy discovered = episodicWithoutReflections(DISCOVERED_EPISODES_NS);
		String legacy = "legacy-refl-1";
		String modern = "/custom/reflections/";
		Episodic episodic = new Episodic(STRATEGY_ID, legacy, 3, 2, null, null, null, modern);
		AgentCoreLongTermMemoryProperties config = propertiesWithEpisodic(episodic);

		// When
		var advisor = this.factory(config).createAdvisors(List.of(discovered)).get(0);
		this.stubEmptyMemories();
		advisor.adviseCall(userRequest("q"), this.chain);

		// Then: modern namespace is used under the main strategy; legacy is never called.
		verify(this.retriever).searchMemories(eq(STRATEGY_ID), anyString(), anyString(), eq("q"), anyInt(), eq(modern));
		verify(this.retriever, never()).searchMemories(eq(legacy), anyString(), anyString(), anyString(), anyInt(),
				anyString());
	}

	@Test
	void episodicWithNoReflectionsAnywhereRunsEpisodesOnly() {
		// Given: nothing reflection-related in either discovery or explicit config.
		DiscoveredStrategy discovered = episodicWithoutReflections(DISCOVERED_EPISODES_NS);
		AgentCoreLongTermMemoryProperties config = propertiesWithoutEpisodicExplicit();

		// When
		var advisor = this.factory(config).createAdvisors(List.of(discovered)).get(0);
		this.stubEmptyMemories();
		advisor.adviseCall(userRequest("q"), this.chain);

		// Then: only one retrieval, for episodes. Never a second call for reflections.
		verify(this.retriever).searchMemories(eq(STRATEGY_ID), anyString(), anyString(), eq("q"), anyInt(),
				eq(DISCOVERED_EPISODES_NS));
		verify(this.retriever, Mockito.times(1)).searchMemories(anyString(), anyString(), anyString(), anyString(),
				anyInt(), anyString());
	}

	@Test
	void explicitConfigWithMismatchedStrategyIdIsIgnored() {
		// Given: explicit config points at a different strategyId than what was
		// discovered.
		DiscoveredStrategy discovered = episodicWithReflections(DISCOVERED_EPISODES_NS, DISCOVERED_REFLECTIONS_NS);
		Episodic episodic = new Episodic("some-other-strategy", null, 7, 9, null, "/explicit/ep/", null,
				"/explicit/refl/");
		AgentCoreLongTermMemoryProperties config = propertiesWithEpisodic(episodic);

		// When
		var advisor = this.factory(config).createAdvisors(List.of(discovered)).get(0);
		this.stubEmptyMemories();
		advisor.adviseCall(userRequest("q"), this.chain);

		// Then: explicit config is ignored; discovered namespaces are used under the
		// discovered strategy id.
		verify(this.retriever).searchMemories(eq(STRATEGY_ID), anyString(), anyString(), eq("q"), anyInt(),
				eq(DISCOVERED_EPISODES_NS));
		verify(this.retriever).searchMemories(eq(STRATEGY_ID), anyString(), anyString(), eq("q"), anyInt(),
				eq(DISCOVERED_REFLECTIONS_NS));
	}

	// ------------------------------------------------------------------
	// Non-episodic sanity check
	// ------------------------------------------------------------------

	@Test
	void semanticStrategyIsBuiltWithDiscoveredNamespace() {
		DiscoveredStrategy discovered = new DiscoveredStrategy("sem-1", AgentCoreLongTermMemoryStrategyType.SEMANTIC,
				List.of(DISCOVERED_EPISODES_NS), List.of());
		AgentCoreLongTermMemoryProperties config = propertiesWithoutEpisodicExplicit();

		var advisor = this.factory(config).createAdvisors(List.of(discovered)).get(0);
		assertThat(advisor.getName())
			.isEqualTo("AgentCoreLongTermMemoryAdvisor-" + AgentCoreLongTermMemoryStrategyType.SEMANTIC);

		this.stubEmptyMemories();
		advisor.adviseCall(userRequest("q"), this.chain);

		verify(this.retriever).searchMemories(eq("sem-1"), anyString(), anyString(), eq("q"), anyInt(),
				eq(DISCOVERED_EPISODES_NS));
	}

	// ------------------------------------------------------------------
	// Helpers
	// ------------------------------------------------------------------

	private AgentCoreLongTermMemoryAutoDiscoveryAdvisorFactory factory(AgentCoreLongTermMemoryProperties config) {
		return new AgentCoreLongTermMemoryAutoDiscoveryAdvisorFactory(this.retriever, config, MEMORY_ID, null);
	}

	private static DiscoveredStrategy episodicWithReflections(String episodesNs, String reflectionsNs) {
		return new DiscoveredStrategy(STRATEGY_ID, AgentCoreLongTermMemoryStrategyType.EPISODIC, List.of(episodesNs),
				List.of(reflectionsNs));
	}

	private static DiscoveredStrategy episodicWithoutReflections(String episodesNs) {
		return new DiscoveredStrategy(STRATEGY_ID, AgentCoreLongTermMemoryStrategyType.EPISODIC, List.of(episodesNs),
				List.of());
	}

	private static AgentCoreLongTermMemoryProperties propertiesWithEpisodic(Episodic episodic) {
		return new AgentCoreLongTermMemoryProperties(true, new Namespace(false), episodic, null, null, null);
	}

	private static AgentCoreLongTermMemoryProperties propertiesWithoutEpisodicExplicit() {
		return new AgentCoreLongTermMemoryProperties(true, new Namespace(false), null, null, null, null);
	}

	private static ChatClientRequest userRequest(String text) {
		return ChatClientRequest.builder()
			.prompt(new Prompt(List.of(new UserMessage(text))))
			.context(Map.of(ChatMemory.CONVERSATION_ID, "user-1"))
			.build();
	}

	/**
	 * Default stubbing: any retriever call returns empty. Tests override where needed.
	 */
	private void stubEmptyMemories() {
		Mockito.lenient()
			.when(this.retriever.searchMemories(anyString(), anyString(), anyString(), anyString(), anyInt(),
					anyString()))
			.thenReturn(List.of());
		Mockito.lenient()
			.when(this.retriever.listMemories(anyString(), anyString(), anyString()))
			.thenReturn(List.of());
	}

}
