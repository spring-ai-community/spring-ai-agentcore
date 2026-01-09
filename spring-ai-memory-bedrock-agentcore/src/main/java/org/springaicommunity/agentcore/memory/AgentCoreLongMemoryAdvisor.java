package org.springaicommunity.agentcore.memory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springaicommunity.agentcore.memory.AgentCoreLongMemoryRepository.MemoryRecord;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;

import reactor.core.publisher.Flux;

/**
 * Unified advisor for all long-term memory strategies. Supports 4 modes:
 * <ul>
 * <li>SEMANTIC: Semantic search for facts -> system message</li>
 * <li>LIST: List all memories for preferences -> system message</li>
 * <li>EPISODIC: Search episodes + reflections -> system message</li>
 * <li>SUMMARY: Search summaries -> user message (augments query)</li>
 * </ul>
 *
 * @author Yuriy Bezsonov
 */
public class AgentCoreLongMemoryAdvisor implements CallAdvisor, StreamAdvisor {

	private static final Logger logger = LoggerFactory.getLogger(AgentCoreLongMemoryAdvisor.class);

	public static final String USER_ID_PARAM = "longTermMemoryUserId";

	public static final String SESSION_ID_PARAM = "longTermMemorySessionId";

	private final AgentCoreLongMemoryRepository repository;

	private final String strategyId;

	private final String contextLabel;

	private final Mode mode;

	private final int order;

	private final int topK;

	private final int reflectionsTopK;

	public enum Mode {

		SEMANTIC, LIST, EPISODIC, SUMMARY

	}

	/**
	 * Constructor for LIST mode (no topK needed).
	 */
	public AgentCoreLongMemoryAdvisor(AgentCoreLongMemoryRepository repository, String strategyId, String contextLabel,
			Mode mode, int order) {
		this(repository, strategyId, contextLabel, mode, order, 3, 2);
	}

	/**
	 * Constructor for SEMANTIC, LIST, SUMMARY modes.
	 */
	public AgentCoreLongMemoryAdvisor(AgentCoreLongMemoryRepository repository, String strategyId, String contextLabel,
			Mode mode, int order, int topK) {
		this(repository, strategyId, contextLabel, mode, order, topK, 2);
	}

	/**
	 * Constructor for EPISODIC mode (needs reflectionsTopK).
	 */
	public AgentCoreLongMemoryAdvisor(AgentCoreLongMemoryRepository repository, String strategyId, String contextLabel,
			Mode mode, int order, int topK, int reflectionsTopK) {
		this.repository = repository;
		this.strategyId = strategyId;
		this.contextLabel = contextLabel;
		this.mode = mode;
		this.order = order;
		this.topK = topK;
		this.reflectionsTopK = reflectionsTopK;
		logger.info("AgentCoreLongMemoryAdvisor initialized: mode={}, strategyId={}", mode, strategyId);
	}

	@Override
	public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
		return chain.nextCall(enrichRequest(request));
	}

	@Override
	public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
		return chain.nextStream(enrichRequest(request));
	}

	private ChatClientRequest enrichRequest(ChatClientRequest request) {
		String userId = extractParam(request, USER_ID_PARAM);
		if (userId == null || userId.isEmpty()) {
			logger.debug("No user ID found, skipping {} enrichment", this.contextLabel);
			return request;
		}

		if (this.mode == Mode.SUMMARY) {
			String sessionId = extractParam(request, SESSION_ID_PARAM);
			if (sessionId == null || sessionId.isEmpty()) {
				logger.debug("No session ID found, skipping summary enrichment");
				return request;
			}
			return enrichWithSummary(request, userId, sessionId);
		}

		if (this.mode == Mode.EPISODIC) {
			return enrichWithEpisodic(request, userId);
		}

		List<MemoryRecord> memories = fetchMemories(request, userId);
		if (memories.isEmpty()) {
			logger.debug("No {} found for user: {}", this.contextLabel, userId);
			return request;
		}

		String context = formatContext(memories);
		logger.info("Enriched system prompt with {} {} for user: {}", memories.size(), this.contextLabel, userId);
		return addToSystemMessage(request, context);
	}

	private ChatClientRequest enrichWithEpisodic(ChatClientRequest request, String userId) {
		String userPrompt = extractUserText(request);
		if (userPrompt == null || userPrompt.isEmpty()) {
			return request;
		}

		List<MemoryRecord> episodes = this.repository.searchMemories(this.strategyId, userId, userPrompt, this.topK);
		List<MemoryRecord> reflections = this.repository.searchMemories(this.strategyId, userId, userPrompt,
				this.reflectionsTopK);

		if (episodes.isEmpty() && reflections.isEmpty()) {
			logger.debug("No episodes or reflections found for user: {}", userId);
			return request;
		}

		String context = formatEpisodicContext(episodes, reflections);
		logger.info("Enriched system prompt with {} episodes and {} reflections for user: {}", episodes.size(),
				reflections.size(), userId);
		return addToSystemMessage(request, context);
	}

	private ChatClientRequest enrichWithSummary(ChatClientRequest request, String userId, String sessionId) {
		String userPrompt = extractUserText(request);
		if (userPrompt == null || userPrompt.isEmpty()) {
			return request;
		}

		List<MemoryRecord> summaries = this.repository.searchSummaries(this.strategyId, userId, sessionId, userPrompt,
				this.topK);
		if (summaries.isEmpty()) {
			logger.debug("No summaries found for user: {}, session: {}", userId, sessionId);
			return request;
		}

		String augmentedPrompt = formatSummaryContext(userPrompt, summaries);
		logger.info("Enriched user prompt with {} summaries for user: {}", summaries.size(), userId);
		return replaceUserMessage(request, augmentedPrompt);
	}

	private List<MemoryRecord> fetchMemories(ChatClientRequest request, String userId) {
		if (this.mode == Mode.SEMANTIC) {
			String userPrompt = extractUserText(request);
			if (userPrompt == null || userPrompt.isEmpty()) {
				return List.of();
			}
			return this.repository.searchMemories(this.strategyId, userId, userPrompt, this.topK);
		}
		else {
			return this.repository.listMemories(this.strategyId, userId);
		}
	}

	private ChatClientRequest addToSystemMessage(ChatClientRequest request, String context) {
		List<Message> messages = new ArrayList<>();
		messages.add(new SystemMessage(context));
		messages.addAll(request.prompt().getInstructions());
		return request.mutate().prompt(new Prompt(messages, request.prompt().getOptions())).build();
	}

	private ChatClientRequest replaceUserMessage(ChatClientRequest request, String newUserText) {
		List<Message> messages = new ArrayList<>();
		for (Message msg : request.prompt().getInstructions()) {
			if (msg instanceof UserMessage) {
				messages.add(new UserMessage(newUserText));
			}
			else {
				messages.add(msg);
			}
		}
		return request.mutate().prompt(new Prompt(messages, request.prompt().getOptions())).build();
	}

	private String extractUserText(ChatClientRequest request) {
		var userMessage = request.prompt().getUserMessage();
		return userMessage != null ? userMessage.getText() : null;
	}

	private String extractParam(ChatClientRequest request, String paramName) {
		Map<String, Object> context = request.context();
		if (context != null && context.containsKey(paramName)) {
			return context.get(paramName).toString();
		}
		return null;
	}

	private String formatContext(List<MemoryRecord> memories) {
		StringBuilder sb = new StringBuilder();
		sb.append(this.contextLabel).append(":\n");
		for (MemoryRecord memory : memories) {
			sb.append("- ").append(memory.content()).append("\n");
		}
		return sb.toString();
	}

	private String formatEpisodicContext(List<MemoryRecord> episodes, List<MemoryRecord> reflections) {
		StringBuilder sb = new StringBuilder();
		if (!episodes.isEmpty()) {
			sb.append("Relevant past interactions:\n");
			for (MemoryRecord episode : episodes) {
				sb.append("- ").append(episode.content()).append("\n");
			}
		}
		if (!reflections.isEmpty()) {
			if (!episodes.isEmpty()) {
				sb.append("\n");
			}
			sb.append("Lessons learned:\n");
			for (MemoryRecord reflection : reflections) {
				sb.append("- ").append(reflection.content()).append("\n");
			}
		}
		return sb.toString();
	}

	private String formatSummaryContext(String originalPrompt, List<MemoryRecord> summaries) {
		StringBuilder sb = new StringBuilder();
		sb.append("Context from previous conversations:\n");
		for (MemoryRecord summary : summaries) {
			sb.append("- ").append(summary.content()).append("\n");
		}
		sb.append("\nUser question: ").append(originalPrompt);
		return sb.toString();
	}

	@Override
	public String getName() {
		return "AgentCoreLongMemoryAdvisor-" + this.mode;
	}

	@Override
	public int getOrder() {
		return this.order;
	}

}
