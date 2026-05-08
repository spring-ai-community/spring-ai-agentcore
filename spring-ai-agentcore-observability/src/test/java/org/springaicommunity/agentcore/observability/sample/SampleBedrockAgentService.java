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

package org.springaicommunity.agentcore.observability.sample;

import java.util.List;
import org.springaicommunity.agentcore.annotation.AgentCoreInvocation;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.stereotype.Service;

/**
 * Example AgentCore entry point used by integration tests and local demos. Returns a
 * synthetic {@link ChatResponse} so token and cache metadata can be asserted without
 * calling AWS.
 */
@Service
public class SampleBedrockAgentService {

	@AgentCoreInvocation
	public ChatResponse invoke(String prompt) {
		ChatResponseMetadata meta = ChatResponseMetadata.builder()
			.model("anthropic.claude-3-haiku-20240307")
			.usage(new DefaultUsage(42, 7))
			.keyValue("cacheReadInputTokens", 3L)
			.build();

		AssistantMessage out = new AssistantMessage("Echo: " + prompt);
		Generation gen = new Generation(out, ChatGenerationMetadata.builder().finishReason("stop").build());
		return ChatResponse.builder().metadata(meta).generations(List.of(gen)).build();
	}

}
