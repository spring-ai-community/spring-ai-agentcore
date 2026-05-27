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

package org.springaicommunity.agentcore.evaluations.client;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.core.document.Document;
import software.amazon.awssdk.services.bedrockagentcore.BedrockAgentCoreClient;
import software.amazon.awssdk.services.bedrockagentcore.model.EvaluateRequest;
import software.amazon.awssdk.services.bedrockagentcore.model.EvaluateResponse;
import software.amazon.awssdk.services.bedrockagentcore.model.EvaluationInput;
import software.amazon.awssdk.services.bedrockagentcore.model.EvaluationResultContent;

/**
 * Client for the AgentCore Evaluate API.
 *
 * <p>
 * Wraps the BedrockAgentCoreClient to provide a simplified interface for evaluating agent
 * responses using built-in or custom evaluators.
 *
 * @author Andrei Shakirin
 */
public class AgentCoreEvaluationClient {

	private static final Logger logger = LoggerFactory.getLogger(AgentCoreEvaluationClient.class);

	private final BedrockAgentCoreClient client;

	public AgentCoreEvaluationClient(BedrockAgentCoreClient client) {
		this.client = client;
	}

	/**
	 * Evaluate session spans using the specified evaluator.
	 * @param evaluatorId the evaluator ID (e.g., "Builtin.Helpfulness")
	 * @param sessionSpans the spans in OTel-compatible format (from SpanEventBuilder)
	 * @return list of evaluation results
	 */
	public List<EvaluationResult> evaluate(String evaluatorId, List<Map<String, Object>> sessionSpans) {
		logger.debug("Evaluating with evaluator: {}, spans count: {}", evaluatorId, sessionSpans.size());
		if (logger.isTraceEnabled()) {
			logger.trace("Session spans: {}", sessionSpans);
		}

		List<Document> spanDocuments = convertToDocuments(sessionSpans);

		EvaluationInput evaluationInput = EvaluationInput.builder().sessionSpans(spanDocuments).build();

		EvaluateRequest request = EvaluateRequest.builder()
			.evaluatorId(evaluatorId)
			.evaluationInput(evaluationInput)
			.build();

		EvaluateResponse response = this.client.evaluate(request);

		return this.mapResults(response.evaluationResults());
	}

	/**
	 * Evaluate session spans using multiple evaluators concurrently.
	 * <p>
	 * Each evaluator runs as an independent task on the supplied {@code executor}.
	 * Per-evaluator exceptions are captured into result objects, so the returned list
	 * always contains one or more entries per evaluator regardless of failures.
	 * End-to-end latency is bounded by the slowest evaluator rather than the sum of all
	 * evaluator latencies.
	 * <p>
	 * <strong>Throughput trade-off:</strong> N evaluators issue N concurrent calls to the
	 * AgentCore service. With aggressive sample rates this can surface throttling sooner
	 * than running them one at a time. AWS SDK retry policies handle the retries, but
	 * callers running at high QPS should size {@code executor} accordingly.
	 * <p>
	 * Result ordering follows {@code evaluatorIds}: the entries from
	 * {@code evaluatorIds.get(0)} appear first, then those from
	 * {@code evaluatorIds.get(1)}, and so on. Concurrent execution does not interleave
	 * results.
	 * @param evaluatorIds list of evaluator IDs to run
	 * @param sessionSpans the spans in OTel-compatible format
	 * @param executor executor used to run evaluator calls; typically the virtual-thread
	 * executor wired by the auto-configuration
	 * @return list of evaluation results from all evaluators, in {@code evaluatorIds}
	 * order
	 */
	public List<EvaluationResult> evaluateAll(List<String> evaluatorIds, List<Map<String, Object>> sessionSpans,
			Executor executor) {
		if (evaluatorIds.isEmpty()) {
			return List.of();
		}
		List<CompletableFuture<List<EvaluationResult>>> futures = new ArrayList<>(evaluatorIds.size());
		for (String evaluatorId : evaluatorIds) {
			futures.add(CompletableFuture.supplyAsync(() -> this.evaluateWithErrorCapture(evaluatorId, sessionSpans),
					executor));
		}
		List<EvaluationResult> allResults = new ArrayList<>();
		for (CompletableFuture<List<EvaluationResult>> future : futures) {
			// .join() preserves evaluator order (we wait on futures in submission order).
			// Per-task exceptions are already captured inside evaluateWithErrorCapture,
			// so
			// .join() should not throw unless the executor itself rejects/cancels the
			// task.
			allResults.addAll(future.join());
		}
		return allResults;
	}

	/**
	 * Run a single evaluator and convert any thrown exception into an error result. The
	 * resulting {@link EvaluationResult} carries an
	 * {@code errorCode}/{@code errorMessage} instead of a score so {@link #evaluateAll}
	 * never aborts because of a single evaluator failure.
	 */
	private List<EvaluationResult> evaluateWithErrorCapture(String evaluatorId,
			List<Map<String, Object>> sessionSpans) {
		try {
			return this.evaluate(evaluatorId, sessionSpans);
		}
		catch (Exception ex) {
			logger.error("Evaluation failed for evaluator {}: {}", evaluatorId, ex.getMessage());
			return List.of(new EvaluationResult(evaluatorId, null, null, null, null, null,
					"ClientException:" + ex.getClass().getSimpleName(), ex.getMessage()));
		}
	}

	private static List<Document> convertToDocuments(List<Map<String, Object>> spans) {
		List<Document> documents = new ArrayList<>(spans.size());
		for (Map<String, Object> span : spans) {
			documents.add(mapToDocument(span));
		}
		return documents;
	}

	private static Document mapToDocument(Object value) {
		return switch (value) {
			case null -> Document.fromNull();
			case String s -> Document.fromString(s);
			case Boolean b -> Document.fromBoolean(b);
			case Integer i -> Document.fromNumber(i);
			case Long l -> Document.fromNumber(l);
			case Double d -> Document.fromNumber(d);
			case Float f -> Document.fromNumber(f);
			case BigDecimal bd -> Document.fromNumber(bd);
			case BigInteger bi -> Document.fromNumber(bi);
			case Number n -> Document.fromNumber(n.toString());
			case List<?> list -> {
				List<Document> docs = new ArrayList<>(list.size());
				for (Object item : list) {
					docs.add(mapToDocument(item));
				}
				yield Document.fromList(docs);
			}
			case Map<?, ?> map -> {
				Map<String, Document> docMap = new HashMap<>(map.size());
				map.forEach((k, v) -> docMap.put(k.toString(), mapToDocument(v)));
				yield Document.fromMap(docMap);
			}
			default -> Document.fromString(value.toString());
		};
	}

	private List<EvaluationResult> mapResults(List<EvaluationResultContent> results) {
		List<EvaluationResult> mapped = new ArrayList<>();
		for (EvaluationResultContent result : results) {
			logger.debug(
					"API result - evaluatorId: {}, value: {}, label: {}, explanation: {}, errorCode: {}, errorMessage: {}",
					result.evaluatorId(), result.value(), result.label(), result.explanation(), result.errorCode(),
					result.errorMessage());

			Integer inputTokens = null;
			Integer outputTokens = null;
			if (result.tokenUsage() != null) {
				inputTokens = result.tokenUsage().inputTokens();
				outputTokens = result.tokenUsage().outputTokens();
			}

			mapped.add(new EvaluationResult(result.evaluatorId(), result.value(), result.label(), result.explanation(),
					inputTokens, outputTokens, result.errorCode(), result.errorMessage()));
		}
		return mapped;
	}

}
