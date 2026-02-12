package org.springaicommunity.agentcore.memory;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import software.amazon.awssdk.services.bedrockagentcorecontrol.BedrockAgentCoreControlClient;
import software.amazon.awssdk.services.bedrockagentcorecontrol.model.GetMemoryRequest;
import software.amazon.awssdk.services.bedrockagentcorecontrol.model.GetMemoryResponse;
import software.amazon.awssdk.services.bedrockagentcorecontrol.model.Memory;
import software.amazon.awssdk.services.bedrockagentcorecontrol.model.MemoryStrategy;

/**
 * Unit tests for {@link AgentCoreLongTermMemoryNamespaceValidator}.
 *
 * @author Yuriy Bezsonov
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AgentCore Long-Term Memory Namespace Validator Tests")
class AgentCoreLongTermMemoryNamespaceValidatorTest {

	@Mock
	private BedrockAgentCoreControlClient controlClient;

	private AgentCoreLongTermMemoryNamespaceValidator validator;

	@BeforeEach
	void setUp() {
		validator = new AgentCoreLongTermMemoryNamespaceValidator(controlClient);
	}

	@Test
	@DisplayName("Should pass validation for correct actor-scoped namespace")
	void shouldPassValidationForCorrectActorScopedNamespace() {
		// Given
		MemoryStrategy strategy = MemoryStrategy.builder()
			.strategyId("semantic-123")
			.namespaces(List.of(AgentCoreLongTermMemoryNamespace.ACTOR.getPattern()))
			.build();

		mockGetMemoryResponse(List.of(strategy));

		// When/Then - no exception
		validator.validateNamespaces("test-memory",
				Map.of("semantic-123", AgentCoreLongTermMemoryNamespace.ACTOR.getPattern()));
	}

	@Test
	@DisplayName("Should pass validation for correct session-scoped namespace")
	void shouldPassValidationForCorrectSessionScopedNamespace() {
		// Given
		MemoryStrategy strategy = MemoryStrategy.builder()
			.strategyId("summary-456")
			.namespaces(List.of(AgentCoreLongTermMemoryNamespace.SESSION.getPattern()))
			.build();

		mockGetMemoryResponse(List.of(strategy));

		// When/Then - no exception
		validator.validateNamespaces("test-memory",
				Map.of("summary-456", AgentCoreLongTermMemoryNamespace.SESSION.getPattern()));
	}

	@Test
	@DisplayName("Should fail validation for wrong namespace format")
	void shouldFailValidationForWrongNamespaceFormat() {
		// Given - wrong format: /users/{actorId}/preferences instead of expected
		MemoryStrategy strategy = MemoryStrategy.builder()
			.strategyId("semantic-123")
			.namespaces(List.of("/users/{actorId}/preferences"))
			.build();

		mockGetMemoryResponse(List.of(strategy));

		// When/Then
		assertThatThrownBy(() -> validator.validateNamespaces("test-memory",
				Map.of("semantic-123", AgentCoreLongTermMemoryNamespace.ACTOR.getPattern())))
			.isInstanceOf(AgentCoreMemoryException.ConfigurationException.class)
			.hasMessageContaining("Namespace mismatch")
			.hasMessageContaining("semantic-123")
			.hasMessageContaining("/users/{actorId}/preferences");
	}

	@Test
	@DisplayName("Should fail validation when strategy not found")
	void shouldFailValidationWhenStrategyNotFound() {
		// Given - empty strategies
		mockGetMemoryResponse(List.of());

		// When/Then
		assertThatThrownBy(() -> validator.validateNamespaces("test-memory",
				Map.of("semantic-123", AgentCoreLongTermMemoryNamespace.ACTOR.getPattern())))
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

		mockGetMemoryResponse(List.of(strategy));

		// When/Then
		assertThatThrownBy(() -> validator.validateNamespaces("test-memory",
				Map.of("semantic-123", AgentCoreLongTermMemoryNamespace.ACTOR.getPattern())))
			.isInstanceOf(AgentCoreMemoryException.ConfigurationException.class)
			.hasMessageContaining("not found")
			.hasMessageContaining("semantic-123");
	}

	@Test
	@DisplayName("Should fail validation when namespace is empty")
	void shouldFailValidationWhenNamespaceIsEmpty() {
		// Given
		MemoryStrategy strategy = MemoryStrategy.builder().strategyId("semantic-123").namespaces(List.of()).build();

		mockGetMemoryResponse(List.of(strategy));

		// When/Then
		assertThatThrownBy(() -> validator.validateNamespaces("test-memory",
				Map.of("semantic-123", AgentCoreLongTermMemoryNamespace.ACTOR.getPattern())))
			.isInstanceOf(AgentCoreMemoryException.ConfigurationException.class)
			.hasMessageContaining("no namespaces configured");
	}

	@Test
	@DisplayName("Should skip validation when no strategies configured")
	void shouldSkipValidationWhenNoStrategiesConfigured() {
		// When/Then - no exception, no API call
		validator.validateNamespaces("test-memory", Map.of());
	}

	@Test
	@DisplayName("Should pass validation for resolved namespace with actual values")
	void shouldPassValidationForResolvedNamespaceWithActualValues() {
		// Given - actual namespace from AWS with real strategy ID (not template)
		MemoryStrategy strategy = MemoryStrategy.builder()
			.strategyId("semantic-123")
			.namespaces(List.of("/strategies/semantic-123/actors/{actorId}"))
			.build();

		mockGetMemoryResponse(List.of(strategy));

		// When/Then - should match ACTOR pattern despite having resolved strategyId
		validator.validateNamespaces("test-memory",
				Map.of("semantic-123", AgentCoreLongTermMemoryNamespace.ACTOR.getPattern()));
	}

	@Test
	@DisplayName("Should pass validation for fully resolved session namespace")
	void shouldPassValidationForFullyResolvedSessionNamespace() {
		// Given - namespace with resolved strategy ID
		MemoryStrategy strategy = MemoryStrategy.builder()
			.strategyId("summary-456")
			.namespaces(List.of("/strategies/summary-456/actors/{actorId}/sessions/{sessionId}"))
			.build();

		mockGetMemoryResponse(List.of(strategy));

		// When/Then - should match SESSION pattern
		validator.validateNamespaces("test-memory",
				Map.of("summary-456", AgentCoreLongTermMemoryNamespace.SESSION.getPattern()));
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

		mockGetMemoryResponse(List.of(strategy));

		// When/Then - should pass when config matches AWS
		validator.validateNamespaces("test-memory", Map.of("summary-123", customPattern));
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

		mockGetMemoryResponse(List.of(strategy));

		// When/Then - should fail because patterns don't match
		assertThatThrownBy(() -> validator.validateNamespaces("test-memory",
				Map.of("summary-123", AgentCoreLongTermMemoryNamespace.SESSION.getPattern())))
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

		mockGetMemoryResponse(List.of(strategy));

		// When/Then - should fail because missing /sessions/{sessionId}
		assertThatThrownBy(() -> validator.validateNamespaces("test-memory",
				Map.of("summary-456", AgentCoreLongTermMemoryNamespace.SESSION.getPattern())))
			.isInstanceOf(AgentCoreMemoryException.ConfigurationException.class)
			.hasMessageContaining("Namespace mismatch");
	}

	private void mockGetMemoryResponse(List<MemoryStrategy> strategies) {
		Memory memory = Memory.builder().strategies(strategies).build();
		GetMemoryResponse response = GetMemoryResponse.builder().memory(memory).build();
		when(controlClient.getMemory(any(GetMemoryRequest.class))).thenReturn(response);
	}

}
