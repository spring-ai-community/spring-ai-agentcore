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

import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.api.Advisor;

import java.util.ArrayList;
import java.util.List;

public class AgentCoreMemory {

	public final MessageChatMemoryAdvisor shortMemoryAdvisor;

	public final List<AgentCoreLongMemoryAdvisor> longMemoryAdvisors;

	public final List<Advisor> advisors;

	AgentCoreMemory(MessageChatMemoryAdvisor stmAdvisor, List<AgentCoreLongMemoryAdvisor> ltmAdvisors) {
		this.shortMemoryAdvisor = stmAdvisor;
		this.longMemoryAdvisors = ltmAdvisors;

		this.advisors = new ArrayList<>(ltmAdvisors);
		this.advisors.add(stmAdvisor);
	}

}
