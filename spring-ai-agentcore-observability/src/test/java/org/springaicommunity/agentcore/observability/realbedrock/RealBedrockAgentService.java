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

package org.springaicommunity.agentcore.observability.realbedrock;

import org.springaicommunity.agentcore.annotation.AgentCoreInvocation;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Service;

/**
 * AgentCore handler that delegates to Spring AI's {@link ChatModel} (Bedrock Converse)
 * for live integration tests only.
 */
@Service
public class RealBedrockAgentService {

	private final ChatModel chatModel;

	public RealBedrockAgentService(ChatModel chatModel) {
		this.chatModel = chatModel;
	}

	@AgentCoreInvocation
	public ChatResponse invoke(String prompt) {
		return chatModel.call(new Prompt(new UserMessage(prompt)));
	}

}
