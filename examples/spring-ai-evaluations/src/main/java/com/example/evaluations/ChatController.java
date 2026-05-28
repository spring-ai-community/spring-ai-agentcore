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
package com.example.evaluations;

import java.util.List;

import jakarta.annotation.PostConstruct;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springaicommunity.agentcore.evaluations.AgentCoreEvaluationAdvisor;
import org.springaicommunity.agentcore.evaluations.client.EvaluationResult;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Chat controller demonstrating AgentCore Evaluations integration.
 *
 * <p>
 * Reads evaluation results from the response context and returns them in the HTTP
 * response. This works only when evaluations run synchronously (so results are in the
 * context before the handler returns). The constructor fails fast if the advisor is
 * configured to run asynchronously.
 */
@RestController
public class ChatController {

	private static final Logger logger = LoggerFactory.getLogger(ChatController.class);

	private final ChatClient chatClient;

	private final boolean async;

	public ChatController(ChatClient.Builder chatClientBuilder, AgentCoreEvaluationAdvisor evaluationAdvisor,
			@Value("${spring.ai.agentcore.evaluations.async:true}") boolean async) {
		this.chatClient = chatClientBuilder.defaultAdvisors(evaluationAdvisor).build();
		this.async = async;
	}

	@PostConstruct
	void assertSyncEvaluation() {
		if (this.async) {
			throw new IllegalStateException(
					"This example reads evaluation results from the HTTP response and therefore requires "
							+ "spring.ai.agentcore.evaluations.async=false. "
							+ "Set it to false in application.properties, or switch to the callback pattern.");
		}
	}

	@PostMapping("/chat")
	public ChatResponse chat(@RequestBody ChatRequest request) {
		logger.info("Received chat request: {}", request.message());

		ChatClientResponse response = chatClient.prompt().user(request.message()).call().chatClientResponse();

		List<EvaluationResult> evaluations = AgentCoreEvaluationAdvisor.resultsFrom(response);

		String content = response.chatResponse() != null && response.chatResponse().getResult() != null
				? response.chatResponse().getResult().getOutput().getText() : "";
		logger.info("Response generated with {} evaluation result(s)", evaluations.size());

		return new ChatResponse(content, evaluations);
	}

	public record ChatRequest(String message) {
	}

	public record ChatResponse(String content, List<EvaluationResult> evaluations) {
	}

}
