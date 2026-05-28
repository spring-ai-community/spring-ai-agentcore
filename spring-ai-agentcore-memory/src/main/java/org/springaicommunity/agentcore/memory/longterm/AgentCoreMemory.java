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

package org.springaicommunity.agentcore.memory.longterm;

import java.util.ArrayList;
import java.util.List;

import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.api.Advisor;

public class AgentCoreMemory {

	/** combined short-term memory advisor backed by the chat memory repository. */
	public final MessageChatMemoryAdvisor shortTermMemoryAdvisor;

	/** ordered list of long-term memory advisors, one per configured strategy. */
	public final List<AgentCoreLongTermMemoryAdvisor> longTermMemoryAdvisors;

	/** combined advisor list (short-term plus long-term) for chaining. */
	public final List<Advisor> advisors;

	public AgentCoreMemory(MessageChatMemoryAdvisor stmAdvisor, List<AgentCoreLongTermMemoryAdvisor> ltmAdvisors) {
		this.shortTermMemoryAdvisor = stmAdvisor;
		this.longTermMemoryAdvisors = ltmAdvisors;

		this.advisors = new ArrayList<>(ltmAdvisors);
		this.advisors.add(stmAdvisor);
	}

}
