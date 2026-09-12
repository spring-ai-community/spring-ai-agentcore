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
package org.springaicommunity.example;

import org.springaicommunity.agentcore.memory.session.AgentCoreSessionMemory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.session.advisor.SessionMemoryAdvisor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller demonstrating the Spring AI Session API bean stack backed by
 * AgentCore. The sessionId follows the {@code userId:sessionSuffix} convention
 * documented on {@link org.springaicommunity.agentcore.memory.session.AgentCoreSessionProperties}
 * so the repository can derive {@code Session.userId} from the actor segment.
 */
@RestController
public class ChatController {

	/**
	 * Session identifier passed via {@link SessionMemoryAdvisor#SESSION_ID_CONTEXT_KEY}.
	 *
	 * <p>
	 * {@code AgentCoreSessionRepository} requires the {@code "userId:sessionSuffix"}
	 * format. Here {@code "testActor"} is the user id (actor segment) and
	 * {@code "testSession"} is the session suffix. AgentCore has no session-metadata
	 * store, so the repository derives {@code Session.userId} from the {@code "testActor"}
	 * prefix.
	 *
	 * <p>
	 * If you also pass {@code SessionMemoryAdvisor.USER_ID_CONTEXT_KEY} on the request,
	 * its value must equal the actor prefix ({@code "testActor"} here). If it differs, the
	 * advisor's turn-2 ownership check throws
	 * {@code IllegalStateException("...does not belong to user...")}. This controller does
	 * not set {@code USER_ID_CONTEXT_KEY}, so the check passes; if you add it, keep the
	 * actor prefix and the USER_ID value in sync.
	 *
	 * <p>
	 * <strong>Security.</strong> This example hardcodes the id for brevity. In a real
	 * application, build the actor prefix from the authenticated principal (for example
	 * the Spring Security username), never from unvalidated request input: a caller who
	 * controls the conversationId chooses whose session they read.
	 */
	private static final String SESSION_ID = "testActor:testSession";

	private final ChatClient sessionChatClient;

	private final AgentCoreSessionMemory sessionMemory;

	public ChatController(ChatClient.Builder chatClientBuilder, AgentCoreSessionMemory sessionMemory) {
		this.sessionMemory = sessionMemory;
		this.sessionChatClient = chatClientBuilder.build();
	}

	@PostMapping("/api/chat")
	public ChatResponse chat(@RequestBody ChatRequest request) {
		String response = this.sessionChatClient.prompt()
			.user(request.message())
			.advisors(this.sessionMemory.advisors)
			// SESSION_ID must be "userId:sessionSuffix"; the repository derives
			// Session.userId from the "userId" prefix. If you also set
			// USER_ID_CONTEXT_KEY here, it must equal that prefix (see SESSION_ID Javadoc).
			.advisors(a -> a.param(SessionMemoryAdvisor.SESSION_ID_CONTEXT_KEY, SESSION_ID))
			.call()
			.content();

		return new ChatResponse(response);
	}

	public record ChatRequest(String message) {
	}

	public record ChatResponse(String response) {
	}

}
