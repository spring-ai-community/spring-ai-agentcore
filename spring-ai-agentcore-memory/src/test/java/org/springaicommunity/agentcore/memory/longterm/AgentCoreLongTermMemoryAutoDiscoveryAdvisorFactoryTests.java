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

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springaicommunity.agentcore.memory.AgentCoreMemoryException;
import org.springaicommunity.agentcore.memory.longterm.AgentCoreLongTermMemoryProperties.Episodic;
import org.springaicommunity.agentcore.memory.longterm.AgentCoreLongTermMemoryProperties.Namespace;
import org.springaicommunity.agentcore.memory.longterm.AgentCoreLongTermMemoryProperties.Semantic;
import org.springaicommunity.agentcore.memory.longterm.AgentCoreLongTermMemoryStrategyDiscovery.DiscoveredStrategy;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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
@ExtendWith({ MockitoExtension.class, OutputCaptureExtension.class })
class AgentCoreLongTermMemoryAutoDiscoveryAdvisorFactoryTests {

	private static final String MEMORY_ID = "mem-1";

	private static final String STRATEGY_ID = "ep-1";

	private static final String DISCOVERED_EPISODES_NS = "/strategy/{memoryStrategyId}/actor/{actorId}/session/{sessionId}/";

	private static final String DISCOVERED_REFLECTIONS_NS = "/strategy/{memoryStrategyId}/actor/{actorId}/";

	// Not scoped by actor: its records are shared by all users
	private static final String SHARED_NS = "/strategy/{memoryStrategyId}/shared/";

	@Mock
	AgentCoreLongTermMemoryRetriever retriever;

	@Mock
	CallAdvisorChain chain;

	@Mock
	AgentCoreLongTermMemoryNamespaceRegistrar registrar;

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
	void episodicSkipsDiscoveredReflectionsWithAPlaceholderRetrievalCannotFill() {
		// Given: the reflections namespace uses a custom namespace key
		String reflections = "/reflections/{orgname}/{actorId}/";
		DiscoveredStrategy discovered = episodicWithReflections(DISCOVERED_EPISODES_NS, reflections);
		AgentCoreLongTermMemoryProperties config = propertiesWithoutEpisodicExplicit();

		// When
		var advisor = this.factory(config).createAdvisors(List.of(discovered)).get(0);
		this.stubEmptyMemories();
		advisor.adviseCall(userRequest("q"), this.chain);

		// Then: episodes are still retrieved; reflections are never requested.
		verify(this.retriever).searchMemories(eq(STRATEGY_ID), anyString(), anyString(), eq("q"), anyInt(),
				eq(DISCOVERED_EPISODES_NS));
		verify(this.retriever, never()).searchMemories(anyString(), anyString(), anyString(), anyString(), anyInt(),
				eq(reflections));
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

	@Test
	void episodicSkipsDiscoveredReflectionsWithoutActorId(CapturedOutput output) {
		// Given: the reflections namespace is shared by all actors
		DiscoveredStrategy discovered = episodicWithReflections(DISCOVERED_EPISODES_NS, SHARED_NS);
		AgentCoreLongTermMemoryProperties config = propertiesWithoutEpisodicExplicit();

		// When
		var advisor = this.factory(config).createAdvisors(List.of(discovered)).get(0);
		this.stubEmptyMemories();
		advisor.adviseCall(userRequest("q"), this.chain);

		// Then: episodes are still retrieved; reflections are never requested, and the
		// warning shows how to use them anyway.
		verify(this.retriever).searchMemories(eq(STRATEGY_ID), anyString(), anyString(), eq("q"), anyInt(),
				eq(DISCOVERED_EPISODES_NS));
		verify(this.retriever, never()).searchMemories(anyString(), anyString(), anyString(), anyString(), anyInt(),
				eq(SHARED_NS));
		assertThat(output).contains("reflections namespace '" + SHARED_NS + "' does not contain {actorId}")
			.contains("agentcore.memory.long-term.episodic.reflections-namespace-pattern=" + SHARED_NS);
	}

	// ------------------------------------------------------------------
	// Namespaces without {actorId}
	// ------------------------------------------------------------------

	@Test
	void strategyWhoseDiscoveredNamespaceLacksActorIdGetsNoAdvisor(CapturedOutput output) {
		DiscoveredStrategy discovered = new DiscoveredStrategy("sem-1", AgentCoreLongTermMemoryStrategyType.SEMANTIC,
				List.of(SHARED_NS), List.of());

		var advisors = this.factory(propertiesWithoutEpisodicExplicit()).createAdvisors(List.of(discovered));

		assertThat(advisors).isEmpty();
		assertThat(output).contains("Strategy 'sem-1' namespace '" + SHARED_NS + "' does not contain {actorId}")
			.contains("agentcore.memory.long-term.semantic.strategy-id=sem-1")
			.contains("agentcore.memory.long-term.semantic.namespace-pattern=" + SHARED_NS);
	}

	@Test
	void namespaceWithoutActorIdIsUsedWhenConfiguredExplicitly() {
		// Naming the namespace in the config shares it on purpose
		DiscoveredStrategy discovered = new DiscoveredStrategy("sem-1", AgentCoreLongTermMemoryStrategyType.SEMANTIC,
				List.of(SHARED_NS), List.of());
		AgentCoreLongTermMemoryProperties config = propertiesWithSemantic(new Semantic("sem-1", 5, null, SHARED_NS));

		var advisors = this.factory(config).createAdvisors(List.of(discovered));
		this.stubEmptyMemories();
		advisors.get(0).adviseCall(userRequest("q"), this.chain);

		verify(this.retriever).searchMemories(eq("sem-1"), anyString(), anyString(), eq("q"), eq(5), eq(SHARED_NS));
	}

	@Test
	void configuredNamespaceWithoutActorIdIsNotRegistered() {
		// Registering it would make all actors share the strategy's only namespace
		DiscoveredStrategy discovered = new DiscoveredStrategy("sem-1", AgentCoreLongTermMemoryStrategyType.SEMANTIC,
				List.of(DISCOVERED_REFLECTIONS_NS), List.of());
		AgentCoreLongTermMemoryProperties config = propertiesWithSemantic(new Semantic("sem-1", 5, null, SHARED_NS));
		var factory = new AgentCoreLongTermMemoryAutoDiscoveryAdvisorFactory(this.retriever, config, MEMORY_ID,
				this.registrar);

		assertThatThrownBy(() -> factory.createAdvisors(List.of(discovered)))
			.isInstanceOf(AgentCoreMemoryException.ConfigurationException.class)
			.hasMessageContaining("not registered because it does not contain {actorId}");
		verify(this.registrar, never()).registerNamespace(anyString(), anyString(), anyString());
	}

	@Test
	void configuredActorScopedNamespaceIsStillRegistered() {
		DiscoveredStrategy discovered = new DiscoveredStrategy("sem-1", AgentCoreLongTermMemoryStrategyType.SEMANTIC,
				List.of(DISCOVERED_REFLECTIONS_NS), List.of());
		String configured = "/custom/{actorId}/";
		AgentCoreLongTermMemoryProperties config = propertiesWithSemantic(new Semantic("sem-1", 5, null, configured));
		var factory = new AgentCoreLongTermMemoryAutoDiscoveryAdvisorFactory(this.retriever, config, MEMORY_ID,
				this.registrar);

		assertThat(factory.createAdvisors(List.of(discovered))).hasSize(1);
		verify(this.registrar).registerNamespace(MEMORY_ID, "sem-1", configured);
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

	private static AgentCoreLongTermMemoryProperties propertiesWithSemantic(Semantic semantic) {
		return new AgentCoreLongTermMemoryProperties(true, new Namespace(false), null, semantic, null, null);
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
			.when(this.retriever.listMemories(anyString(), anyString(), anyString(), anyString()))
			.thenReturn(List.of());
	}

}
