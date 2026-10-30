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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springaicommunity.agentcore.memory.AgentCoreMemoryException;
import software.amazon.awssdk.services.bedrockagentcorecontrol.BedrockAgentCoreControlClient;
import software.amazon.awssdk.services.bedrockagentcorecontrol.model.CustomReflectionConfiguration;
import software.amazon.awssdk.services.bedrockagentcorecontrol.model.EpisodicReflectionConfiguration;
import software.amazon.awssdk.services.bedrockagentcorecontrol.model.EpisodicReflectionOverride;
import software.amazon.awssdk.services.bedrockagentcorecontrol.model.GetMemoryRequest;
import software.amazon.awssdk.services.bedrockagentcorecontrol.model.GetMemoryResponse;
import software.amazon.awssdk.services.bedrockagentcorecontrol.model.Memory;
import software.amazon.awssdk.services.bedrockagentcorecontrol.model.MemoryStrategy;
import software.amazon.awssdk.services.bedrockagentcorecontrol.model.MemoryStrategyType;
import software.amazon.awssdk.services.bedrockagentcorecontrol.model.OverrideType;
import software.amazon.awssdk.services.bedrockagentcorecontrol.model.ReflectionConfiguration;
import software.amazon.awssdk.services.bedrockagentcorecontrol.model.StrategyConfiguration;

import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;

/**
 * Unit tests for {@link AgentCoreLongTermMemoryNamespaceValidator}.
 *
 * @author Yuriy Bezsonov
 */
@ExtendWith({ MockitoExtension.class, OutputCaptureExtension.class })
@DisplayName("AgentCore Long-Term Memory Namespace Validator Tests")
class AgentCoreLongTermMemoryNamespaceValidatorTests {

	@Mock
	private BedrockAgentCoreControlClient controlClient;

	@Mock
	private AgentCoreLongTermMemoryNamespaceRegistrar registrar;

	private AgentCoreLongTermMemoryNamespaceValidator validator;

	@BeforeEach
	void setUp() {
		this.validator = new AgentCoreLongTermMemoryNamespaceValidator(this.controlClient, this.registrar, false);
	}

	@Test
	@DisplayName("Should pass validation for correct actor-scoped namespace")
	void shouldPassValidationForCorrectActorScopedNamespace() {
		// Given
		MemoryStrategy strategy = MemoryStrategy.builder()
			.strategyId("semantic-123")
			.namespaces(List.of(AgentCoreLongTermMemoryNamespace.ACTOR.getPattern()))
			.build();

		this.mockGetMemoryResponse(List.of(strategy));

		// When/Then - no exception
		this.validator.validateNamespaces("test-memory",
				Map.of("semantic-123", List.of(AgentCoreLongTermMemoryNamespace.ACTOR.getPattern())));
	}

	@Test
	@DisplayName("Should pass validation for correct session-scoped namespace")
	void shouldPassValidationForCorrectSessionScopedNamespace() {
		// Given
		MemoryStrategy strategy = MemoryStrategy.builder()
			.strategyId("summary-456")
			.namespaces(List.of(AgentCoreLongTermMemoryNamespace.SESSION.getPattern()))
			.build();

		this.mockGetMemoryResponse(List.of(strategy));

		// When/Then - no exception
		this.validator.validateNamespaces("test-memory",
				Map.of("summary-456", List.of(AgentCoreLongTermMemoryNamespace.SESSION.getPattern())));
	}

	@Test
	@DisplayName("Should fail validation for wrong namespace format")
	void shouldFailValidationForWrongNamespaceFormat() {
		// Given - wrong format: /users/{actorId}/preferences instead of expected
		MemoryStrategy strategy = MemoryStrategy.builder()
			.strategyId("semantic-123")
			.namespaces(List.of("/users/{actorId}/preferences"))
			.build();

		this.mockGetMemoryResponse(List.of(strategy));

		// When/Then
		assertThatThrownBy(() -> this.validator.validateNamespaces("test-memory",
				Map.of("semantic-123", List.of(AgentCoreLongTermMemoryNamespace.ACTOR.getPattern()))))
			.isInstanceOf(AgentCoreMemoryException.ConfigurationException.class)
			.hasMessageContaining("Namespace mismatch")
			.hasMessageContaining("semantic-123")
			.hasMessageContaining("/users/{actorId}/preferences");
	}

	@Test
	@DisplayName("Should name the strategy's property and the memory's namespace in the mismatch error")
	void shouldNameStrategyPropertyInMismatchError() {
		MemoryStrategy strategy = MemoryStrategy.builder()
			.strategyId("semantic-123")
			.type(MemoryStrategyType.SEMANTIC)
			.namespaces(List.of("/users/{actorId}/facts"))
			.build();

		this.mockGetMemoryResponse(List.of(strategy));

		assertThatThrownBy(() -> this.validator.validateNamespaces("test-memory",
				Map.of("semantic-123", List.of(AgentCoreLongTermMemoryNamespace.ACTOR.getPattern()))))
			.isInstanceOf(AgentCoreMemoryException.ConfigurationException.class)
			.hasMessageContaining("agentcore.memory.long-term.semantic.namespace-pattern=/users/{actorId}/facts");
	}

	@Test
	@DisplayName("Should not suggest a memory namespace that is not scoped by actor")
	void shouldNotSuggestUnsupportedMemoryNamespace() {
		MemoryStrategy strategy = MemoryStrategy.builder()
			.strategyId("semantic-123")
			.type(MemoryStrategyType.SEMANTIC)
			.namespaces(List.of("/strategies/{memoryStrategyId}/shared"))
			.build();

		this.mockGetMemoryResponse(List.of(strategy));

		assertThatThrownBy(() -> this.validator.validateNamespaces("test-memory",
				Map.of("semantic-123", List.of(AgentCoreLongTermMemoryNamespace.ACTOR.getPattern()))))
			.isInstanceOf(AgentCoreMemoryException.ConfigurationException.class)
			.hasMessageContaining("cannot be used")
			.hasMessageNotContaining("namespace-pattern=");
	}

	@Test
	@DisplayName("Should name the right group when a strategy ID is configured under another one")
	void shouldFailWhenStrategyIsConfiguredUnderAnotherGroup() {
		MemoryStrategy strategy = MemoryStrategy.builder()
			.strategyId("summary-456")
			.type(MemoryStrategyType.SUMMARIZATION)
			.namespaces(List.of(AgentCoreLongTermMemoryNamespace.SESSION.getPattern()))
			.build();
		this.mockGetMemoryResponse(List.of(strategy));
		var validator = new AgentCoreLongTermMemoryNamespaceValidator(this.controlClient, this.registrar, false,
				Map.of("summary-456", AgentCoreLongTermMemoryStrategyType.SEMANTIC));

		assertThatThrownBy(() -> validator.validateNamespaces("test-memory",
				Map.of("summary-456", List.of(AgentCoreLongTermMemoryNamespace.ACTOR.getPattern()))))
			.isInstanceOf(AgentCoreMemoryException.ConfigurationException.class)
			.hasMessageContaining("is a SUMMARIZATION strategy")
			.hasMessageContaining("configured as agentcore.memory.long-term.semantic.strategy-id")
			.hasMessageContaining("Configure it as agentcore.memory.long-term.summary.strategy-id instead")
			.hasMessageNotContaining("namespace-pattern=");
	}

	@Test
	@DisplayName("Should name the configured group in the mismatch hint for a CUSTOM strategy")
	void shouldNameConfiguredGroupInHintForCustomStrategy() {
		MemoryStrategy strategy = MemoryStrategy.builder()
			.strategyId("custom-1")
			.type(MemoryStrategyType.CUSTOM)
			.namespaces(List.of("/custom/{actorId}"))
			.build();
		this.mockGetMemoryResponse(List.of(strategy));
		var validator = new AgentCoreLongTermMemoryNamespaceValidator(this.controlClient, this.registrar, false,
				Map.of("custom-1", AgentCoreLongTermMemoryStrategyType.EPISODIC));

		assertThatThrownBy(() -> validator.validateNamespaces("test-memory",
				Map.of("custom-1", List.of(AgentCoreLongTermMemoryNamespace.ACTOR.getPattern()))))
			.isInstanceOf(AgentCoreMemoryException.ConfigurationException.class)
			.hasMessageContaining("agentcore.memory.long-term.episodic.namespace-pattern=/custom/{actorId}");
	}

	@Test
	@DisplayName("Should type-check a CUSTOM strategy by its override type")
	void shouldFailWhenCustomOverrideIsConfiguredUnderAnotherGroup() {
		this.mockGetMemoryResponse(List.of(customStrategy("custom-sum", OverrideType.SUMMARY_OVERRIDE,
				AgentCoreLongTermMemoryNamespace.SESSION.getPattern())));
		var validator = new AgentCoreLongTermMemoryNamespaceValidator(this.controlClient, this.registrar, false,
				Map.of("custom-sum", AgentCoreLongTermMemoryStrategyType.SEMANTIC));

		assertThatThrownBy(() -> validator.validateNamespaces("test-memory",
				Map.of("custom-sum", List.of(AgentCoreLongTermMemoryNamespace.ACTOR.getPattern()))))
			.isInstanceOf(AgentCoreMemoryException.ConfigurationException.class)
			.hasMessageContaining("Strategy 'custom-sum' is a CUSTOM (SUMMARY_OVERRIDE) strategy")
			.hasMessageContaining("configured as agentcore.memory.long-term.semantic.strategy-id")
			.hasMessageContaining("Configure it as agentcore.memory.long-term.summary.strategy-id instead");
	}

	@Test
	@DisplayName("Should accept a CUSTOM strategy under the group of its override type")
	void shouldAcceptCustomOverrideUnderItsGroup() {
		this.mockGetMemoryResponse(List.of(customStrategy("custom-sem", OverrideType.SEMANTIC_OVERRIDE,
				AgentCoreLongTermMemoryNamespace.ACTOR.getPattern())));
		var validator = new AgentCoreLongTermMemoryNamespaceValidator(this.controlClient, this.registrar, false,
				Map.of("custom-sem", AgentCoreLongTermMemoryStrategyType.SEMANTIC));

		validator.validateNamespaces("test-memory",
				Map.of("custom-sem", List.of(AgentCoreLongTermMemoryNamespace.ACTOR.getPattern())));
	}

	@Test
	@DisplayName("Should not type-check a SELF_MANAGED strategy")
	void shouldNotTypeCheckSelfManagedStrategy() {
		this.mockGetMemoryResponse(List.of(customStrategy("self-1", OverrideType.SELF_MANAGED,
				AgentCoreLongTermMemoryNamespace.ACTOR.getPattern())));
		var validator = new AgentCoreLongTermMemoryNamespaceValidator(this.controlClient, this.registrar, false,
				Map.of("self-1", AgentCoreLongTermMemoryStrategyType.SEMANTIC));

		validator.validateNamespaces("test-memory",
				Map.of("self-1", List.of(AgentCoreLongTermMemoryNamespace.ACTOR.getPattern())));
	}

	@Test
	@DisplayName("Should name the override type's group in the mismatch hint without a configured type")
	void shouldNameOverrideGroupInHintWithoutConfiguredType() {
		this.mockGetMemoryResponse(
				List.of(customStrategy("custom-ep", OverrideType.EPISODIC_OVERRIDE, "/custom/{actorId}")));

		assertThatThrownBy(() -> this.validator.validateNamespaces("test-memory",
				Map.of("custom-ep", List.of(AgentCoreLongTermMemoryNamespace.ACTOR.getPattern()))))
			.isInstanceOf(AgentCoreMemoryException.ConfigurationException.class)
			.hasMessageContaining("agentcore.memory.long-term.episodic.namespace-pattern=/custom/{actorId}");
	}

	@Test
	@DisplayName("Should fail validation when strategy not found")
	void shouldFailValidationWhenStrategyNotFound() {
		// Given - empty strategies
		this.mockGetMemoryResponse(List.of());

		// When/Then
		assertThatThrownBy(() -> this.validator.validateNamespaces("test-memory",
				Map.of("semantic-123", List.of(AgentCoreLongTermMemoryNamespace.ACTOR.getPattern()))))
			.isInstanceOf(AgentCoreMemoryException.ConfigurationException.class)
			.hasMessageContaining("no strategies configured");
	}

	@Test
	@DisplayName("Should fail validation when configured strategy ID not in memory")
	void shouldFailValidationWhenConfiguredStrategyNotInMemory() {
		// Given - different strategy ID
		MemoryStrategy strategy = MemoryStrategy.builder()
			.strategyId("other-strategy")
			.namespaces(List.of(AgentCoreLongTermMemoryNamespace.ACTOR.getPattern()))
			.build();

		this.mockGetMemoryResponse(List.of(strategy));

		// When/Then
		assertThatThrownBy(() -> this.validator.validateNamespaces("test-memory",
				Map.of("semantic-123", List.of(AgentCoreLongTermMemoryNamespace.ACTOR.getPattern()))))
			.isInstanceOf(AgentCoreMemoryException.ConfigurationException.class)
			.hasMessageContaining("not found")
			.hasMessageContaining("semantic-123");
	}

	@Test
	@DisplayName("Should fail validation when namespace is empty")
	void shouldFailValidationWhenNamespaceIsEmpty() {
		// Given
		MemoryStrategy strategy = MemoryStrategy.builder().strategyId("semantic-123").namespaces(List.of()).build();

		this.mockGetMemoryResponse(List.of(strategy));

		// When/Then
		assertThatThrownBy(() -> this.validator.validateNamespaces("test-memory",
				Map.of("semantic-123", List.of(AgentCoreLongTermMemoryNamespace.ACTOR.getPattern()))))
			.isInstanceOf(AgentCoreMemoryException.ConfigurationException.class)
			.hasMessageContaining("no namespaces configured");
	}

	@Test
	@DisplayName("Should validate against namespace templates when the legacy namespaces field is empty")
	void shouldValidateAgainstNamespaceTemplatesWhenNamespacesAreEmpty() {
		// Given - AWS marks the strategy's namespaces field as legacy
		String actor = AgentCoreLongTermMemoryNamespace.ACTOR.getPattern();
		MemoryStrategy strategy = MemoryStrategy.builder()
			.strategyId("semantic-123")
			.namespaceTemplates(List.of(actor))
			.build();

		this.mockGetMemoryResponse(List.of(strategy));

		// When/Then - passes for the template, and a mismatch names it
		this.validator.validateNamespaces("test-memory", Map.of("semantic-123", List.of(actor)));
		assertThatThrownBy(() -> this.validator.validateNamespaces("test-memory",
				Map.of("semantic-123", List.of(AgentCoreLongTermMemoryNamespace.SESSION.getPattern()))))
			.isInstanceOf(AgentCoreMemoryException.ConfigurationException.class)
			.hasMessageContaining("In AWS Memory:    [" + actor + "]");
	}

	@Test
	@DisplayName("Should prefer namespace templates over a stale legacy namespaces field")
	void shouldPreferNamespaceTemplatesOverStaleLegacyNamespaces() {
		// Given - the legacy field may stop being updated
		String actor = AgentCoreLongTermMemoryNamespace.ACTOR.getPattern();
		MemoryStrategy strategy = MemoryStrategy.builder()
			.strategyId("semantic-123")
			.namespaceTemplates(List.of(actor))
			.namespaces(List.of("/legacy/{actorId}"))
			.build();

		this.mockGetMemoryResponse(List.of(strategy));

		// When/Then - no exception
		this.validator.validateNamespaces("test-memory", Map.of("semantic-123", List.of(actor)));
	}

	@Test
	@DisplayName("Should skip validation when no strategies configured")
	void shouldSkipValidationWhenNoStrategiesConfigured() {
		// When/Then - no exception, no API call
		this.validator.validateNamespaces("test-memory", Map.of());
	}

	@Test
	@DisplayName("Should pass validation for resolved namespace with actual values")
	void shouldPassValidationForResolvedNamespaceWithActualValues() {
		// Given - actual namespace from AWS with real strategy ID (not template)
		MemoryStrategy strategy = MemoryStrategy.builder()
			.strategyId("semantic-123")
			.namespaces(List.of("/strategies/semantic-123/actors/{actorId}"))
			.build();

		this.mockGetMemoryResponse(List.of(strategy));

		// When/Then - should match ACTOR pattern despite having resolved strategyId
		this.validator.validateNamespaces("test-memory",
				Map.of("semantic-123", List.of(AgentCoreLongTermMemoryNamespace.ACTOR.getPattern())));
	}

	@Test
	@DisplayName("Should pass validation for fully resolved session namespace")
	void shouldPassValidationForFullyResolvedSessionNamespace() {
		// Given - namespace with resolved strategy ID
		MemoryStrategy strategy = MemoryStrategy.builder()
			.strategyId("summary-456")
			.namespaces(List.of("/strategies/summary-456/actors/{actorId}/sessions/{sessionId}"))
			.build();

		this.mockGetMemoryResponse(List.of(strategy));

		// When/Then - should match SESSION pattern
		this.validator.validateNamespaces("test-memory",
				Map.of("summary-456", List.of(AgentCoreLongTermMemoryNamespace.SESSION.getPattern())));
	}

	@Test
	@DisplayName("Should pass validation for custom namespace pattern")
	void shouldPassValidationForCustomNamespacePattern() {
		// Given - custom namespace pattern in both AWS and config
		String customPattern = "custom-namespace/strategies/{memoryStrategyId}/actors/{actorId}/sessions/{sessionId}";
		MemoryStrategy strategy = MemoryStrategy.builder()
			.strategyId("summary-123")
			.namespaces(List.of(customPattern))
			.build();

		this.mockGetMemoryResponse(List.of(strategy));

		// When/Then - should pass when config matches AWS
		this.validator.validateNamespaces("test-memory", Map.of("summary-123", List.of(customPattern)));
	}

	@Test
	@DisplayName("Should fail when custom namespace in AWS but default in config")
	void shouldFailWhenCustomNamespaceInAwsButDefaultInConfig() {
		// Given - custom namespace in AWS, but default pattern in Spring config
		String customPattern = "custom-namespace/strategies/{memoryStrategyId}/actors/{actorId}/sessions/{sessionId}";
		MemoryStrategy strategy = MemoryStrategy.builder()
			.strategyId("summary-123")
			.namespaces(List.of(customPattern))
			.build();

		this.mockGetMemoryResponse(List.of(strategy));

		// When/Then - should fail because patterns don't match
		assertThatThrownBy(() -> this.validator.validateNamespaces("test-memory",
				Map.of("summary-123", List.of(AgentCoreLongTermMemoryNamespace.SESSION.getPattern()))))
			.isInstanceOf(AgentCoreMemoryException.ConfigurationException.class)
			.hasMessageContaining("Namespace mismatch")
			.hasMessageContaining("custom-namespace");
	}

	@Test
	@DisplayName("Should fail when actor namespace used for session scope")
	void shouldFailWhenActorNamespaceUsedForSessionScope() {
		// Given - actor-scoped namespace but expecting session scope
		MemoryStrategy strategy = MemoryStrategy.builder()
			.strategyId("summary-456")
			.namespaces(List.of("/strategies/summary-456/actors/{actorId}"))
			.build();

		this.mockGetMemoryResponse(List.of(strategy));

		// When/Then - should fail because missing /sessions/{sessionId}
		assertThatThrownBy(() -> this.validator.validateNamespaces("test-memory",
				Map.of("summary-456", List.of(AgentCoreLongTermMemoryNamespace.SESSION.getPattern()))))
			.isInstanceOf(AgentCoreMemoryException.ConfigurationException.class)
			.hasMessageContaining("Namespace mismatch");
	}

	@Test
	@DisplayName("Should fail when placeholders are swapped between AWS and config")
	void shouldFailWhenPlaceholdersAreSwapped() {
		// Given - AWS scopes the slot by session, config fills it with the actor id
		MemoryStrategy strategy = MemoryStrategy.builder()
			.strategyId("semantic-123")
			.namespaces(List.of("/strategies/{memoryStrategyId}/actors/{sessionId}"))
			.build();

		this.mockGetMemoryResponse(List.of(strategy));

		// When/Then
		assertThatThrownBy(() -> this.validator.validateNamespaces("test-memory",
				Map.of("semantic-123", List.of(AgentCoreLongTermMemoryNamespace.ACTOR.getPattern()))))
			.isInstanceOf(AgentCoreMemoryException.ConfigurationException.class)
			.hasMessageContaining("Namespace mismatch")
			.hasMessageContaining("{sessionId}");
	}

	@Test
	@DisplayName("Should fail when config expects an actor placeholder but AWS has a literal")
	void shouldFailWhenActorPlaceholderMeetsLiteral() {
		// Given - AWS writes every actor's records to one literal namespace
		MemoryStrategy strategy = MemoryStrategy.builder()
			.strategyId("semantic-123")
			.namespaces(List.of("/strategies/{memoryStrategyId}/actors/alice"))
			.build();

		this.mockGetMemoryResponse(List.of(strategy));

		// When/Then
		assertThatThrownBy(() -> this.validator.validateNamespaces("test-memory",
				Map.of("semantic-123", List.of(AgentCoreLongTermMemoryNamespace.ACTOR.getPattern()))))
			.isInstanceOf(AgentCoreMemoryException.ConfigurationException.class)
			.hasMessageContaining("Namespace mismatch");
	}

	@Test
	@DisplayName("Should fail when resolved strategy id in AWS belongs to another strategy")
	void shouldFailWhenResolvedStrategyIdDiffers() {
		// Given
		MemoryStrategy strategy = MemoryStrategy.builder()
			.strategyId("semantic-123")
			.namespaces(List.of("/strategies/other-999/actors/{actorId}"))
			.build();

		this.mockGetMemoryResponse(List.of(strategy));

		// When/Then
		assertThatThrownBy(() -> this.validator.validateNamespaces("test-memory",
				Map.of("semantic-123", List.of(AgentCoreLongTermMemoryNamespace.ACTOR.getPattern()))))
			.isInstanceOf(AgentCoreMemoryException.ConfigurationException.class)
			.hasMessageContaining("Namespace mismatch");
	}

	@Test
	@DisplayName("Should fail when the pattern uses a placeholder that retrieval cannot fill")
	void shouldFailWhenPatternUsesUnfillablePlaceholder() {
		// Given - a custom namespace key, identical in AWS and config; retrieval would
		// send it to AWS as literal braces
		String pattern = "/org/{orgname}/actors/{actorId}";
		MemoryStrategy strategy = MemoryStrategy.builder()
			.strategyId("semantic-123")
			.namespaces(List.of(pattern))
			.build();

		this.mockGetMemoryResponse(List.of(strategy));

		// When/Then
		assertThatThrownBy(
				() -> this.validator.validateNamespaces("test-memory", Map.of("semantic-123", List.of(pattern))))
			.isInstanceOf(AgentCoreMemoryException.ConfigurationException.class)
			.hasMessageContaining("{orgname}")
			.hasMessageContaining("cannot fill");
	}

	private static MemoryStrategy customStrategy(String strategyId, OverrideType override, String namespace) {
		return MemoryStrategy.builder()
			.strategyId(strategyId)
			.type(MemoryStrategyType.CUSTOM)
			.namespaces(List.of(namespace))
			.configuration(StrategyConfiguration.builder().type(override).build())
			.build();
	}

	private void mockGetMemoryResponse(List<MemoryStrategy> strategies) {
		Memory memory = Memory.builder().strategies(strategies).build();
		GetMemoryResponse response = GetMemoryResponse.builder().memory(memory).build();
		given(this.controlClient.getMemory(any(GetMemoryRequest.class))).willReturn(response);
	}

	@Nested
	@DisplayName("Auto-Registration Mode Tests")
	class AutoRegistrationTests {

		@Mock
		private AgentCoreLongTermMemoryNamespaceRegistrar registrar;

		private AgentCoreLongTermMemoryNamespaceValidator validatorWithAutoRegister;

		private AgentCoreLongTermMemoryNamespaceValidator validatorWithoutAutoRegister;

		@BeforeEach
		void setUp() {
			this.registrar = mock(AgentCoreLongTermMemoryNamespaceRegistrar.class);
			this.validatorWithAutoRegister = new AgentCoreLongTermMemoryNamespaceValidator(
					AgentCoreLongTermMemoryNamespaceValidatorTests.this.controlClient, this.registrar, true);
			this.validatorWithoutAutoRegister = new AgentCoreLongTermMemoryNamespaceValidator(
					AgentCoreLongTermMemoryNamespaceValidatorTests.this.controlClient, this.registrar, false);
		}

		@Test
		@DisplayName("Should call registrar on namespace mismatch when autoRegister is true")
		void shouldCallRegistrarOnMismatch() {
			// Given
			MemoryStrategy strategy = MemoryStrategy.builder()
				.strategyId("semantic-123")
				.namespaces(List.of("/wrong/namespace/{actorId}"))
				.build();
			AgentCoreLongTermMemoryNamespaceValidatorTests.this.mockGetMemoryResponse(List.of(strategy));

			String expectedPattern = AgentCoreLongTermMemoryNamespace.ACTOR.getPattern();

			// When
			this.validatorWithAutoRegister.validateNamespaces("test-memory",
					Map.of("semantic-123", List.of(expectedPattern)));

			// Then
			then(this.registrar).should().registerNamespace("test-memory", "semantic-123", expectedPattern);
		}

		@Test
		@DisplayName("Should not call registrar when namespace matches")
		void shouldNotCallRegistrarWhenMatches() {
			// Given
			MemoryStrategy strategy = MemoryStrategy.builder()
				.strategyId("semantic-123")
				.namespaces(List.of(AgentCoreLongTermMemoryNamespace.ACTOR.getPattern()))
				.build();
			AgentCoreLongTermMemoryNamespaceValidatorTests.this.mockGetMemoryResponse(List.of(strategy));

			// When
			this.validatorWithAutoRegister.validateNamespaces("test-memory",
					Map.of("semantic-123", List.of(AgentCoreLongTermMemoryNamespace.ACTOR.getPattern())));

			// Then
			then(this.registrar).should(never()).registerNamespace(any(), any(), any());
		}

		@Test
		@DisplayName("Should throw exception when autoRegister is false and mismatch occurs")
		void shouldThrowWhenAutoRegisterDisabledAndMismatch() {
			// Given
			MemoryStrategy strategy = MemoryStrategy.builder()
				.strategyId("semantic-123")
				.namespaces(List.of("/wrong/namespace/{actorId}"))
				.build();
			AgentCoreLongTermMemoryNamespaceValidatorTests.this.mockGetMemoryResponse(List.of(strategy));

			// When/Then
			assertThatThrownBy(() -> this.validatorWithoutAutoRegister.validateNamespaces("test-memory",
					Map.of("semantic-123", List.of(AgentCoreLongTermMemoryNamespace.ACTOR.getPattern()))))
				.isInstanceOf(AgentCoreMemoryException.ConfigurationException.class)
				.hasMessageContaining("Namespace mismatch");

			// Verify registrar was never called
			then(this.registrar).should(never()).registerNamespace(any(), any(), any());
		}

		@Test
		@DisplayName("Should not register a pattern without {actorId}, even when only its placeholders differ")
		void shouldNotRegisterPatternWithoutActorId() {
			// Registering it would replace the memory's actor-scoped namespace, so every
			// actor's new records would share one namespace per session id.
			MemoryStrategy strategy = MemoryStrategy.builder()
				.strategyId("semantic-123")
				.namespaces(List.of(AgentCoreLongTermMemoryNamespace.ACTOR.getPattern()))
				.build();
			AgentCoreLongTermMemoryNamespaceValidatorTests.this.mockGetMemoryResponse(List.of(strategy));

			assertThatThrownBy(() -> this.validatorWithAutoRegister.validateNamespaces("test-memory",
					Map.of("semantic-123", List.of("/strategies/{memoryStrategyId}/actors/{sessionId}"))))
				.isInstanceOf(AgentCoreMemoryException.ConfigurationException.class)
				.hasMessageContaining("Namespace mismatch")
				.hasMessageContaining("not registered automatically");
			then(this.registrar).should(never()).registerNamespace(any(), any(), any());
		}

		@Test
		@DisplayName("Should register an actor pattern over a memory namespace with a literal actor")
		void shouldRegisterActorPatternOverLiteralActorNamespace() {
			// The literal used to pass by wildcard; now it is a mismatch, and the
			// actor-scoped pattern is registered in its place.
			MemoryStrategy strategy = MemoryStrategy.builder()
				.strategyId("semantic-123")
				.namespaces(List.of("/strategies/{memoryStrategyId}/actors/alice"))
				.build();
			AgentCoreLongTermMemoryNamespaceValidatorTests.this.mockGetMemoryResponse(List.of(strategy));
			String expectedPattern = AgentCoreLongTermMemoryNamespace.ACTOR.getPattern();

			this.validatorWithAutoRegister.validateNamespaces("test-memory",
					Map.of("semantic-123", List.of(expectedPattern)));

			then(this.registrar).should().registerNamespace("test-memory", "semantic-123", expectedPattern);
		}

		@Test
		@DisplayName("Should not register anything when a later reflection check fails")
		void shouldNotRegisterWhenReflectionCheckFails() {
			EpisodicReflectionConfiguration reflections = EpisodicReflectionConfiguration.builder()
				.namespaces(List.of("/reflections/{actorId}"))
				.build();
			MemoryStrategy strategy = MemoryStrategy.builder()
				.strategyId("episodic-123")
				.namespaces(List.of("/old/episodes/{actorId}"))
				.configuration(StrategyConfiguration.builder()
					.reflection(ReflectionConfiguration.builder().episodicReflectionConfiguration(reflections).build())
					.build())
				.build();
			AgentCoreLongTermMemoryNamespaceValidatorTests.this.mockGetMemoryResponse(List.of(strategy));

			assertThatThrownBy(() -> this.validatorWithAutoRegister.validateNamespaces("test-memory",
					Map.of("episodic-123", List.of("/episodes/{actorId}/{sessionId}")),
					Map.of("episodic-123", "/episodes/{actorId}")))
				.isInstanceOf(AgentCoreMemoryException.ConfigurationException.class)
				.hasMessageContaining("Reflection namespace mismatch");
			then(this.registrar).should(never()).registerNamespace(any(), any(), any());
		}

		@Test
		@DisplayName("Should not register a pattern with a placeholder that retrieval cannot fill")
		void shouldNotRegisterPatternWithUnfillablePlaceholder() {
			MemoryStrategy strategy = MemoryStrategy.builder()
				.strategyId("semantic-123")
				.namespaces(List.of(AgentCoreLongTermMemoryNamespace.ACTOR.getPattern()))
				.build();
			AgentCoreLongTermMemoryNamespaceValidatorTests.this.mockGetMemoryResponse(List.of(strategy));

			assertThatThrownBy(() -> this.validatorWithAutoRegister.validateNamespaces("test-memory",
					Map.of("semantic-123", List.of("/org/{orgname}/actors/{actorId}"))))
				.isInstanceOf(AgentCoreMemoryException.ConfigurationException.class)
				.hasMessageContaining("{orgname}");
			then(this.registrar).should(never()).registerNamespace(any(), any(), any());
		}

	}

	@Nested
	@DisplayName("Episodic Reflection Namespace Tests")
	class ReflectionNamespaceTests {

		private static final String EPISODES = "/episodes/{actorId}/{sessionId}";

		private static final String REFLECTIONS = "/episodes/{actorId}";

		@Test
		@DisplayName("Should validate reflections pattern against the reflection namespaces (gh-254)")
		void shouldPassWhenReflectionsPatternMatchesReflectionNamespace() {
			AgentCoreLongTermMemoryNamespaceValidatorTests.this
				.mockGetMemoryResponse(List.of(episodicStrategy(List.of(REFLECTIONS))));

			AgentCoreLongTermMemoryNamespaceValidatorTests.this.validator.validateNamespaces("test-memory",
					Map.of("episodic-123", List.of(EPISODES)), Map.of("episodic-123", REFLECTIONS));
		}

		@Test
		@DisplayName("Should fail on reflections mismatch without auto-registering it as episode namespace")
		void shouldFailOnReflectionsMismatchEvenWithAutoRegister() {
			AgentCoreLongTermMemoryNamespaceValidatorTests.this
				.mockGetMemoryResponse(List.of(episodicStrategy(List.of("/reflections/{actorId}"))));
			var autoRegistering = new AgentCoreLongTermMemoryNamespaceValidator(
					AgentCoreLongTermMemoryNamespaceValidatorTests.this.controlClient,
					AgentCoreLongTermMemoryNamespaceValidatorTests.this.registrar, true);

			assertThatThrownBy(() -> autoRegistering.validateNamespaces("test-memory",
					Map.of("episodic-123", List.of(EPISODES)), Map.of("episodic-123", REFLECTIONS)))
				.isInstanceOf(AgentCoreMemoryException.ConfigurationException.class)
				.hasMessageContaining("Reflection namespace mismatch")
				.hasMessageContaining("/reflections/{actorId}");
			then(AgentCoreLongTermMemoryNamespaceValidatorTests.this.registrar).should(never())
				.registerNamespace(any(), any(), any());
		}

		@Test
		@DisplayName("Should fail when the episodic strategy has no reflection configuration")
		void shouldFailWhenStrategyHasNoReflectionNamespaces() {
			AgentCoreLongTermMemoryNamespaceValidatorTests.this.mockGetMemoryResponse(
					List.of(MemoryStrategy.builder().strategyId("episodic-123").namespaces(List.of(EPISODES)).build()));

			assertThatThrownBy(() -> AgentCoreLongTermMemoryNamespaceValidatorTests.this.validator.validateNamespaces(
					"test-memory", Map.of("episodic-123", List.of(EPISODES)), Map.of("episodic-123", REFLECTIONS)))
				.isInstanceOf(AgentCoreMemoryException.ConfigurationException.class)
				.hasMessageContaining("<none configured in the memory>");
		}

		@Test
		@DisplayName("Should not suggest a reflection namespace with unsupported placeholders")
		void shouldNotSuggestUnsupportedReflectionNamespace() {
			AgentCoreLongTermMemoryNamespaceValidatorTests.this
				.mockGetMemoryResponse(List.of(episodicStrategy(List.of("/reflections/{tenantId}/{actorId}"))));

			assertThatThrownBy(() -> AgentCoreLongTermMemoryNamespaceValidatorTests.this.validator.validateNamespaces(
					"test-memory", Map.of("episodic-123", List.of(EPISODES)), Map.of("episodic-123", REFLECTIONS)))
				.isInstanceOf(AgentCoreMemoryException.ConfigurationException.class)
				.hasMessageContaining("Reflection namespace mismatch")
				.hasMessageContaining("cannot be used")
				.hasMessageNotContaining("reflections-namespace-pattern=");
		}

		@Test
		@DisplayName("Should fail when reflection placeholders are swapped")
		void shouldFailWhenReflectionPlaceholdersAreSwapped() {
			AgentCoreLongTermMemoryNamespaceValidatorTests.this
				.mockGetMemoryResponse(List.of(episodicStrategy(List.of("/episodes/{sessionId}"))));

			assertThatThrownBy(() -> AgentCoreLongTermMemoryNamespaceValidatorTests.this.validator.validateNamespaces(
					"test-memory", Map.of("episodic-123", List.of(EPISODES)), Map.of("episodic-123", REFLECTIONS)))
				.isInstanceOf(AgentCoreMemoryException.ConfigurationException.class)
				.hasMessageContaining("Reflection namespace mismatch");
		}

		@Test
		@DisplayName("Should fail when the reflections pattern uses a placeholder that retrieval cannot fill")
		void shouldFailWhenReflectionsPatternUsesUnfillablePlaceholder() {
			String reflections = "/reflections/{orgname}/{actorId}";
			AgentCoreLongTermMemoryNamespaceValidatorTests.this
				.mockGetMemoryResponse(List.of(episodicStrategy(List.of(reflections))));

			assertThatThrownBy(() -> AgentCoreLongTermMemoryNamespaceValidatorTests.this.validator.validateNamespaces(
					"test-memory", Map.of("episodic-123", List.of(EPISODES)), Map.of("episodic-123", reflections)))
				.isInstanceOf(AgentCoreMemoryException.ConfigurationException.class)
				.hasMessageContaining("{orgname}")
				.hasMessageContaining("cannot fill");
		}

		@Test
		@DisplayName("Should validate reflections pattern against a custom reflection override")
		void shouldPassWhenReflectionsPatternMatchesCustomReflectionOverride(CapturedOutput output) {
			AgentCoreLongTermMemoryNamespaceValidatorTests.this
				.mockGetMemoryResponse(List.of(customReflectionStrategy(List.of(REFLECTIONS))));

			AgentCoreLongTermMemoryNamespaceValidatorTests.this.validator.validateNamespaces("test-memory",
					Map.of("episodic-123", List.of(EPISODES)), Map.of("episodic-123", REFLECTIONS));

			// The auto-discovery hint to set the pattern is wrong here: it is already
			// set.
			assertThat(output).doesNotContain("uses customReflectionConfiguration");
		}

		@Test
		@DisplayName("Should read the namespace templates of a custom reflection override")
		void shouldPassWhenCustomReflectionOverrideHasOnlyNamespaceTemplates() {
			// AWS marks namespaces as legacy; an override may only report templates.
			CustomReflectionConfiguration custom = CustomReflectionConfiguration.builder()
				.episodicReflectionOverride(
						EpisodicReflectionOverride.builder().namespaceTemplates(REFLECTIONS).build())
				.build();
			MemoryStrategy strategy = MemoryStrategy.builder()
				.strategyId("episodic-123")
				.namespaces(List.of(EPISODES))
				.configuration(StrategyConfiguration.builder()
					.reflection(ReflectionConfiguration.builder().customReflectionConfiguration(custom).build())
					.build())
				.build();
			AgentCoreLongTermMemoryNamespaceValidatorTests.this.mockGetMemoryResponse(List.of(strategy));

			AgentCoreLongTermMemoryNamespaceValidatorTests.this.validator.validateNamespaces("test-memory",
					Map.of("episodic-123", List.of(EPISODES)), Map.of("episodic-123", REFLECTIONS));
		}

		@Test
		@DisplayName("Should prefer the templates of a custom reflection override over its legacy namespaces")
		void shouldPreferCustomReflectionOverrideTemplatesOverLegacyNamespaces() {
			CustomReflectionConfiguration custom = CustomReflectionConfiguration.builder()
				.episodicReflectionOverride(EpisodicReflectionOverride.builder()
					.namespaceTemplates(REFLECTIONS)
					.namespaces("/legacy/{actorId}")
					.build())
				.build();
			MemoryStrategy strategy = MemoryStrategy.builder()
				.strategyId("episodic-123")
				.namespaces(List.of(EPISODES))
				.configuration(StrategyConfiguration.builder()
					.reflection(ReflectionConfiguration.builder().customReflectionConfiguration(custom).build())
					.build())
				.build();
			AgentCoreLongTermMemoryNamespaceValidatorTests.this.mockGetMemoryResponse(List.of(strategy));

			AgentCoreLongTermMemoryNamespaceValidatorTests.this.validator.validateNamespaces("test-memory",
					Map.of("episodic-123", List.of(EPISODES)), Map.of("episodic-123", REFLECTIONS));
		}

		@Test
		@DisplayName("Should report custom reflection override namespaces on mismatch")
		void shouldFailOnCustomReflectionOverrideMismatch() {
			AgentCoreLongTermMemoryNamespaceValidatorTests.this
				.mockGetMemoryResponse(List.of(customReflectionStrategy(List.of("/reflections/{actorId}"))));

			assertThatThrownBy(() -> AgentCoreLongTermMemoryNamespaceValidatorTests.this.validator.validateNamespaces(
					"test-memory", Map.of("episodic-123", List.of(EPISODES)), Map.of("episodic-123", REFLECTIONS)))
				.isInstanceOf(AgentCoreMemoryException.ConfigurationException.class)
				.hasMessageContaining("Reflection namespace mismatch")
				.hasMessageContaining("/reflections/{actorId}");
		}

		private static MemoryStrategy customReflectionStrategy(List<String> overrideNamespaces) {
			CustomReflectionConfiguration custom = CustomReflectionConfiguration.builder()
				.episodicReflectionOverride(EpisodicReflectionOverride.builder().namespaces(overrideNamespaces).build())
				.build();
			ReflectionConfiguration reflection = ReflectionConfiguration.builder()
				.customReflectionConfiguration(custom)
				.build();
			return MemoryStrategy.builder()
				.strategyId("episodic-123")
				.namespaces(List.of(EPISODES))
				.configuration(StrategyConfiguration.builder().reflection(reflection).build())
				.build();
		}

		private static MemoryStrategy episodicStrategy(List<String> reflectionNamespaces) {
			EpisodicReflectionConfiguration episodicReflection = EpisodicReflectionConfiguration.builder()
				.namespaces(reflectionNamespaces)
				.build();
			ReflectionConfiguration reflection = ReflectionConfiguration.builder()
				.episodicReflectionConfiguration(episodicReflection)
				.build();
			return MemoryStrategy.builder()
				.strategyId("episodic-123")
				.namespaces(List.of(EPISODES))
				.configuration(StrategyConfiguration.builder().reflection(reflection).build())
				.build();
		}

	}

}
