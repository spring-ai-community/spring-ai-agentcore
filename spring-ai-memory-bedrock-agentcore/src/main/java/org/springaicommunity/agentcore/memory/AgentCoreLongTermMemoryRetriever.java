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

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import software.amazon.awssdk.services.bedrockagentcore.BedrockAgentCoreClient;
import software.amazon.awssdk.services.bedrockagentcore.model.ListMemoryRecordsRequest;
import software.amazon.awssdk.services.bedrockagentcore.model.ListMemoryRecordsResponse;
import software.amazon.awssdk.services.bedrockagentcore.model.MemoryRecordSummary;
import software.amazon.awssdk.services.bedrockagentcore.model.RetrieveMemoryRecordsRequest;
import software.amazon.awssdk.services.bedrockagentcore.model.RetrieveMemoryRecordsResponse;
import software.amazon.awssdk.services.bedrockagentcore.model.SearchCriteria;

/**
 * Retriever for long-term memories from AgentCore Memory. Supports semantic search
 * (facts, summaries, episodes) and listing (preferences).
 *
 * @author Yuriy Bezsonov
 */
public class AgentCoreLongTermMemoryRetriever {

	private static final Logger logger = LoggerFactory.getLogger(AgentCoreLongTermMemoryRetriever.class);

	private final BedrockAgentCoreClient client;

	private final String memoryId;

	public AgentCoreLongTermMemoryRetriever(BedrockAgentCoreClient client, String memoryId) {
		this.client = client;
		this.memoryId = memoryId;
		logger.info("AgentCoreLongTermMemoryRetriever initialized with memoryId: {}", memoryId);
	}

	/**
	 * Semantic search for memories with configurable scope.
	 * @param strategyId the memory strategy ID
	 * @param actorId the actor ID
	 * @param sessionId the session ID (required if scope is SESSION, ignored if ACTOR)
	 * @param query the search query
	 * @param topK maximum number of results
	 * @param scope the namespace scope (ACTOR or SESSION)
	 * @return list of matching memory records
	 */
	public List<MemoryRecord> searchMemories(String strategyId, String actorId, String sessionId, String query,
			int topK, AgentCoreLongTermMemoryScope scope) {
		String namespace = buildNamespace(scope, strategyId, actorId, sessionId);
		return doSearch(namespace, strategyId, query, topK);
	}

	/**
	 * Semantic search for memories (actor-scoped, searches all sessions).
	 */
	public List<MemoryRecord> searchMemories(String strategyId, String actorId, String query, int topK) {
		return searchMemories(strategyId, actorId, null, query, topK, AgentCoreLongTermMemoryScope.ACTOR);
	}

	/**
	 * Search summaries with configurable scope. Unlike searchMemories(), this method
	 * doesn't validate sessionId because the scope itself determines whether sessionId is
	 * needed (SESSION scope includes it, ACTOR scope ignores it).
	 */
	public List<MemoryRecord> searchSummaries(String strategyId, String actorId, String sessionId, String query,
			int topK, AgentCoreLongTermMemoryScope scope) {
		String namespace = scope.buildNamespace(strategyId, actorId, sessionId);
		return doSearch(namespace, strategyId, query, topK);
	}

	/**
	 * List all memories for an actor (no semantic search). Used for preferences.
	 */
	public List<MemoryRecord> listMemories(String strategyId, String actorId) {
		String namespace = AgentCoreLongTermMemoryScope.ACTOR.buildNamespace(strategyId, actorId);
		logger.debug("Listing memories: namespace={}", namespace);

		try {
			ListMemoryRecordsRequest request = ListMemoryRecordsRequest.builder()
				.memoryId(this.memoryId)
				.namespace(namespace)
				.memoryStrategyId(strategyId)
				.build();

			ListMemoryRecordsResponse response = this.client.listMemoryRecords(request);
			List<MemoryRecord> records = extractRecords(response.memoryRecordSummaries());
			logger.debug("Found {} memories in namespace: {}", records.size(), namespace);
			return records;
		}
		catch (Exception e) {
			throw new AgentCoreMemoryException.RetrievalException("Failed to list memories: namespace=" + namespace, e);
		}
	}

	private String buildNamespace(AgentCoreLongTermMemoryScope scope, String strategyId, String actorId,
			String sessionId) {
		// Validation now handled by AgentCoreLongTermMemoryScope.buildNamespace()
		return scope.buildNamespace(strategyId, actorId, sessionId);
	}

	private List<MemoryRecord> doSearch(String namespace, String strategyId, String query, int topK) {
		logger.debug("Searching: namespace={}, query={}, topK={}", namespace, query, topK);

		try {
			RetrieveMemoryRecordsRequest request = RetrieveMemoryRecordsRequest.builder()
				.memoryId(this.memoryId)
				.namespace(namespace)
				.searchCriteria(
						SearchCriteria.builder().searchQuery(query).memoryStrategyId(strategyId).topK(topK).build())
				.build();

			RetrieveMemoryRecordsResponse response = this.client.retrieveMemoryRecords(request);
			List<MemoryRecord> records = extractRecords(response.memoryRecordSummaries());
			logger.debug("Found {} memories for query: {}", records.size(), query);
			return records;
		}
		catch (Exception e) {
			throw new AgentCoreMemoryException.RetrievalException("Failed to search memories: namespace=" + namespace,
					e);
		}
	}

	private List<MemoryRecord> extractRecords(List<MemoryRecordSummary> summaries) {
		if (summaries == null || summaries.isEmpty()) {
			return List.of();
		}
		List<MemoryRecord> records = new ArrayList<>();
		for (MemoryRecordSummary summary : summaries) {
			String contentText = summary.content() != null ? summary.content().text() : "";
			records.add(new MemoryRecord(summary.memoryRecordId(), contentText,
					summary.score() != null ? summary.score() : 0.0));
		}
		return records;
	}

	public record MemoryRecord(String id, String content, double score) {
	}

}
