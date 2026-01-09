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
 * Repository for retrieving long-term memories from AgentCore Memory. Supports semantic
 * search (facts, summaries, episodes) and listing (preferences).
 *
 * @author Yuriy Bezsonov
 */
public class AgentCoreLongMemoryRepository {

	private static final Logger logger = LoggerFactory.getLogger(AgentCoreLongMemoryRepository.class);

	private final BedrockAgentCoreClient client;

	private final String memoryId;

	public AgentCoreLongMemoryRepository(BedrockAgentCoreClient client, String memoryId) {
		this.client = client;
		this.memoryId = memoryId;
		logger.info("AgentCoreLongMemoryRepository initialized with memoryId: {}", memoryId);
	}

	/**
	 * Semantic search for memories (facts, episodes).
	 */
	public List<MemoryRecord> searchMemories(String strategyId, String actorId, String query, int topK) {
		return doSearch(buildNamespace(strategyId, actorId), strategyId, query, topK);
	}

	/**
	 * Search summaries for a specific session.
	 */
	public List<MemoryRecord> searchSummaries(String strategyId, String actorId, String sessionId, String query,
			int topK) {
		return doSearch(buildSessionNamespace(strategyId, actorId, sessionId), strategyId, query, topK);
	}

	/**
	 * List all memories for an actor (no semantic search). Used for preferences.
	 */
	public List<MemoryRecord> listMemories(String strategyId, String actorId) {
		String namespace = buildNamespace(strategyId, actorId);
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
			logger.error("Failed to list memories: namespace={}", namespace, e);
			return List.of();
		}
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
			logger.error("Failed to search: namespace={}, query={}", namespace, query, e);
			return List.of();
		}
	}

	private String buildNamespace(String strategyId, String actorId) {
		return "/strategy/" + strategyId + "/actors/" + actorId;
	}

	private String buildSessionNamespace(String strategyId, String actorId, String sessionId) {
		return "/strategy/" + strategyId + "/actors/" + actorId + "/sessions/" + sessionId;
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
