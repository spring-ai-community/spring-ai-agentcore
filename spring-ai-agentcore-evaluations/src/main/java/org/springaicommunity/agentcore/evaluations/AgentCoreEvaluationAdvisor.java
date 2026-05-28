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

package org.springaicommunity.agentcore.evaluations;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springaicommunity.agentcore.evaluations.client.AgentCoreEvaluationClient;
import org.springaicommunity.agentcore.evaluations.client.EvaluationEvent;
import org.springaicommunity.agentcore.evaluations.client.EvaluationResult;
import org.springaicommunity.agentcore.evaluations.client.SpanEventBuilder;
import reactor.core.publisher.Flux;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

/**
 * Advisor that evaluates agent responses using the AgentCore Evaluate API.
 *
 * <p>
 * Captures prompt/response pairs, builds OTel-compatible spans, and calls the Evaluate
 * API. Results are stored in the response context and optionally published via callbacks
 * and metrics.
 *
 * @author Andrei Shakirin
 */
public final class AgentCoreEvaluationAdvisor implements CallAdvisor, StreamAdvisor {

	private static final Logger logger = LoggerFactory.getLogger(AgentCoreEvaluationAdvisor.class);

	/**
	 * Context key under which evaluation results are stored on the response.
	 * <p>
	 * Reliable only when {@code async=false}. In async mode, the handler returns before
	 * the evaluation completes, so the key may not be populated by the time the caller
	 * reads {@code response.context()}. Prefer the callback or metrics channels in async
	 * mode.
	 * <p>
	 * Prefer {@link #resultsFrom(ChatClientResponse)} over reading this key directly: the
	 * accessor performs the type check that callers would otherwise have to express as an
	 * unchecked cast.
	 */
	public static final String EVALUATION_RESULTS_KEY = "agentcore.evaluation.results";

	/**
	 * Extract the evaluation results stored on a chat response by this advisor.
	 * <p>
	 * Returns an empty list when the key is absent or the value is not a list of
	 * {@link EvaluationResult}. This is the type-safe replacement for an unchecked cast
	 * on {@link ChatClientResponse#context()}: any non-{@code EvaluationResult} entries
	 * are filtered out rather than triggering a {@code ClassCastException} at use site.
	 * <p>
	 * Reliable only when the advisor is configured with {@code async=false}; in async
	 * mode the handler may return before evaluation completes and this method may return
	 * an empty list even though evaluation will eventually populate the context.
	 * @param response the chat response to read from
	 * @return immutable list of evaluation results, never {@code null}
	 */
	public static List<EvaluationResult> resultsFrom(ChatClientResponse response) {
		Object value = response.context().get(EVALUATION_RESULTS_KEY);
		if (value instanceof List<?> list) {
			return list.stream().filter(EvaluationResult.class::isInstance).map(EvaluationResult.class::cast).toList();
		}
		return List.of();
	}

	private final AgentCoreEvaluationClient client;

	private final List<String> evaluatorIds;

	private final boolean async;

	private final double sampleRate;

	private final AgentCoreEvaluationMetrics metrics;

	private final Consumer<EvaluationEvent> callback;

	private final int order;

	private final Executor executor;

	private final boolean includeHistory;

	/**
	 * Shared default executor used when the caller does not supply one. A single
	 * virtual-thread-per-task executor is kept for the JVM lifetime so that manually
	 * constructed advisors do not each leak their own executor. Production setups should
	 * inject a managed executor via the builder.
	 */
	private static final Executor DEFAULT_EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();

	private AgentCoreEvaluationAdvisor(Builder builder) {
		this.client = builder.client;
		this.evaluatorIds = builder.evaluatorIds;
		this.async = builder.async;
		this.sampleRate = builder.sampleRate;
		this.metrics = builder.metrics;
		this.callback = builder.callback;
		this.order = builder.order;
		this.executor = (builder.executor != null) ? builder.executor : DEFAULT_EXECUTOR;
		this.includeHistory = builder.includeHistory;
	}

	public static Builder builder(AgentCoreEvaluationClient client) {
		return new Builder(client);
	}

	@Override
	public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
		ChatClientResponse response = chain.nextCall(request);

		if (this.shouldSample()) {
			this.runEvaluation(request, response);
		}

		return response;
	}

	@Override
	public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
		// Decide once, up-front, whether this turn is sampled. Chunks pass through
		// untouched so streaming to the caller is preserved; text is accumulated on the
		// side and the evaluation is triggered on completion.
		boolean sampled = this.shouldSample();
		StringBuilder text = (sampled) ? new StringBuilder() : null;
		AtomicReference<ChatClientResponse> last = new AtomicReference<>();

		return chain.nextStream(request).doOnNext((chunk) -> {
			last.set(chunk);
			if (sampled) {
				String t = this.extractAssistantResponse(chunk);
				if (t != null) {
					text.append(t);
				}
			}
		}).doOnComplete(() -> {
			if (sampled && last.get() != null) {
				this.runEvaluation(request, this.aggregated(last.get(), text.toString()));
			}
		});
	}

	private ChatClientResponse aggregated(ChatClientResponse last, String text) {
		ChatResponse chat = new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
		return ChatClientResponse.builder().chatResponse(chat).context(last.context()).build();
	}

	private boolean shouldSample() {
		return this.sampleRate >= 1.0 || ThreadLocalRandom.current().nextDouble() < this.sampleRate;
	}

	private void runEvaluation(ChatClientRequest request, ChatClientResponse response) {
		if (this.async) {
			CompletableFuture.runAsync(() -> this.doEvaluate(request, response), this.executor);
		}
		else {
			this.doEvaluate(request, response);
		}
	}

	private void doEvaluate(ChatClientRequest request, ChatClientResponse response) {
		String sessionId = this.extractSessionId(request);
		String traceId = UUID.randomUUID().toString().replace("-", "").substring(0, 32);

		try {
			String userPrompt = this.extractUserPrompt(request);
			String assistantResponse = this.extractAssistantResponse(response);

			if (userPrompt == null || assistantResponse == null) {
				logger.debug("Skipping evaluation: missing prompt or response");
				return;
			}

			// Build OTel-compatible spans and events
			SpanEventBuilder spanBuilder = SpanEventBuilder.agentInvocation(traceId, sessionId)
				.promptEvent(userPrompt)
				.completionEvent(assistantResponse)
				.modelId(this.extractModelId(response))
				.finishReason(this.extractFinishReason(response))
				.tokenUsage(this.extractInputTokens(response), this.extractOutputTokens(response))
				.history((this.includeHistory) ? this.extractHistory(request) : List.of());

			List<Map<String, Object>> spans = spanBuilder.buildSessionSpans();

			Instant start = Instant.now();
			// Run all configured evaluators in parallel on the advisor's executor.
			// Latency drops from sum(per-evaluator) to max(per-evaluator) on the
			// synchronous path and reduces wall-clock work on the async path too.
			List<EvaluationResult> results = this.client.evaluateAll(this.evaluatorIds, spans, this.executor);
			Duration latency = Duration.between(start, Instant.now());

			// Record metrics
			if (this.metrics != null) {
				for (EvaluationResult r : results) {
					String evaluatorId = (r.evaluatorId() != null) ? r.evaluatorId() : "unknown";
					if (r.isError()) {
						this.metrics.recordError(evaluatorId, r.errorCode());
					}
					else {
						this.metrics.record(evaluatorId, r, latency);
					}
				}
			}

			// Store results in response context
			response.context().put(EVALUATION_RESULTS_KEY, results);

			// Invoke callback
			if (this.callback != null) {
				EvaluationEvent event = new EvaluationEvent(sessionId, traceId, results, Instant.now(), latency);
				this.callback.accept(event);
			}

			logger.debug("Evaluation completed: {} results for session {}", results.size(), sessionId);

		}
		catch (Exception ex) {
			logger.error("Evaluation failed for session {}: {}", sessionId, ex.getMessage());
			if (this.metrics != null) {
				for (String evaluatorId : this.evaluatorIds) {
					this.metrics.recordError(evaluatorId, ex.getClass().getSimpleName());
				}
			}
		}
	}

	private String extractSessionId(ChatClientRequest request) {
		Object sessionId = request.context().get("sessionId");
		if (sessionId != null) {
			return sessionId.toString();
		}
		Object conversationId = request.context().get("conversationId");
		if (conversationId != null) {
			return conversationId.toString();
		}
		return UUID.randomUUID().toString();
	}

	private String extractUserPrompt(ChatClientRequest request) {
		List<Message> all = request.prompt().getInstructions();
		// Use the last UserMessage — in multi-turn chats with ChatMemory, earlier
		// UserMessages are prior turns. Consistent with extractHistory's exclusion.
		for (int i = all.size() - 1; i >= 0; i--) {
			if (all.get(i) instanceof UserMessage) {
				return all.get(i).getText();
			}
		}
		return null;
	}

	/**
	 * Convert all messages in {@code request.prompt().getInstructions()} — except the
	 * final {@code UserMessage}, which is emitted separately as the current user prompt —
	 * into the ADOT body-message shape so they can be attached to {@code input.messages}.
	 * System messages, prior user/assistant turns (spliced in by a {@code ChatMemory}
	 * advisor), and {@code ToolResponseMessage} entries are included. Any intermediate
	 * {@code AssistantMessage.toolCalls} from the current turn are not visible here, see
	 * design doc Extension 5.1.
	 * @param request the chat client request whose prompt history is being extracted
	 * @return body-message entries for prior history (excluding the trailing user prompt)
	 */
	private List<Map<String, Object>> extractHistory(ChatClientRequest request) {
		List<Message> all = request.prompt().getInstructions();
		int lastUserIdx = -1;
		for (int i = all.size() - 1; i >= 0; i--) {
			if (all.get(i) instanceof UserMessage) {
				lastUserIdx = i;
				break;
			}
		}
		List<Map<String, Object>> out = new ArrayList<>();
		for (int i = 0; i < all.size(); i++) {
			if (i == lastUserIdx) {
				continue;
			}
			Message msg = all.get(i);
			if (msg instanceof SystemMessage sm) {
				out.add(Map.of("role", "system", "content", sm.getText()));
			}
			else if (msg instanceof UserMessage um) {
				out.add(Map.of("role", "user", "content", um.getText()));
			}
			else if (msg instanceof AssistantMessage am) {
				String text = am.getText();
				out.add(Map.of("role", "assistant", "content", (text != null) ? text : ""));
			}
			else if (msg instanceof ToolResponseMessage trm) {
				for (ToolResponseMessage.ToolResponse tr : trm.getResponses()) {
					out.add(Map.of("role", "tool", "content", Map.of("name", tr.name(), "result", tr.responseData())));
				}
			}
		}
		return out;
	}

	// ChatResponse.getResult() is declared non-null by Spring AI's @NonNullApi package
	// annotation but returns null when the generations list is empty. The guard below
	// is deliberate runtime defence against that contract-vs-source mismatch; the
	// suppression silences the false "always true" analyser warning.
	@SuppressWarnings("ConstantConditions")
	private String extractAssistantResponse(ChatClientResponse response) {
		if (response.chatResponse() == null || response.chatResponse().getResult() == null) {
			return null;
		}
		Message output = response.chatResponse().getResult().getOutput();
		return (output instanceof AssistantMessage) ? output.getText() : null;
	}

	private String extractModelId(ChatClientResponse response) {
		if (response.chatResponse() == null) {
			return null;
		}
		String model = response.chatResponse().getMetadata().getModel();
		return (model != null && !model.isBlank()) ? model : null;
	}

	// ChatResponse.getResult() is declared non-null but returns null when generations
	// is empty (same contract-vs-source mismatch as extractAssistantResponse above).
	@SuppressWarnings("ConstantConditions")
	private String extractFinishReason(ChatClientResponse response) {
		if (response.chatResponse() == null || response.chatResponse().getResult() == null) {
			return null;
		}
		String reason = response.chatResponse().getResult().getMetadata().getFinishReason();
		return (reason != null && !reason.isBlank()) ? reason : null;
	}

	private Integer extractInputTokens(ChatClientResponse response) {
		if (response.chatResponse() == null) {
			return null;
		}
		return response.chatResponse().getMetadata().getUsage().getPromptTokens();
	}

	private Integer extractOutputTokens(ChatClientResponse response) {
		if (response.chatResponse() == null) {
			return null;
		}
		return response.chatResponse().getMetadata().getUsage().getCompletionTokens();
	}

	@Override
	public String getName() {
		return "AgentCoreEvaluationAdvisor";
	}

	@Override
	public int getOrder() {
		return this.order;
	}

	public static final class Builder {

		private final AgentCoreEvaluationClient client;

		private List<String> evaluatorIds = List.of("Builtin.Helpfulness");

		private boolean async = true;

		private double sampleRate = 1.0;

		private AgentCoreEvaluationMetrics metrics;

		private Consumer<EvaluationEvent> callback;

		private int order = 1000; // Run after most advisors

		private Executor executor;

		private boolean includeHistory = false;

		private Builder(AgentCoreEvaluationClient client) {
			Objects.requireNonNull(client, "AgentCoreEvaluationClient is required");
			this.client = client;
		}

		public Builder evaluatorIds(List<String> evaluatorIds) {
			this.evaluatorIds = evaluatorIds;
			return this;
		}

		public Builder async(boolean async) {
			this.async = async;
			return this;
		}

		public Builder sampleRate(double sampleRate) {
			this.sampleRate = Math.max(0.0, Math.min(1.0, sampleRate));
			return this;
		}

		public Builder metrics(AgentCoreEvaluationMetrics metrics) {
			this.metrics = metrics;
			return this;
		}

		public Builder callback(Consumer<EvaluationEvent> callback) {
			this.callback = callback;
			return this;
		}

		public Builder order(int order) {
			this.order = order;
			return this;
		}

		public Builder executor(Executor executor) {
			this.executor = executor;
			return this;
		}

		public Builder includeHistory(boolean includeHistory) {
			this.includeHistory = includeHistory;
			return this;
		}

		public AgentCoreEvaluationAdvisor build() {
			return new AgentCoreEvaluationAdvisor(this);
		}

	}

}
