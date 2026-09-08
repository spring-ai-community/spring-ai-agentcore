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

package org.springaicommunity.agentcore.memory.session;

import java.util.ArrayList;
import java.util.List;

import org.springaicommunity.agentcore.memory.longterm.AgentCoreLongTermMemoryAdvisor;

import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.session.advisor.SessionMemoryAdvisor;

/**
 * Value object that groups the Spring AI Session API short-term advisor with the existing
 * AgentCore long-term memory advisors. Sibling to
 * {@link org.springaicommunity.agentcore.memory.longterm.AgentCoreMemory} for consumers
 * on the Session path. Order in {@link #advisors} matches
 * {@link org.springaicommunity.agentcore.memory.longterm.AgentCoreMemory}: LTM advisors
 * first, session STM advisor last.
 *
 * @author Spring AI Community
 */
public class AgentCoreSessionMemory {

	/** Session-based short-term memory advisor. */
	public final SessionMemoryAdvisor shortTermMemoryAdvisor;

	/** Ordered list of long-term memory advisors, one per configured strategy. */
	public final List<AgentCoreLongTermMemoryAdvisor> longTermMemoryAdvisors;

	/** Combined advisor list (LTM first, session STM last) for chaining. */
	public final List<Advisor> advisors;

	public AgentCoreSessionMemory(SessionMemoryAdvisor sessionMemoryAdvisor,
			List<AgentCoreLongTermMemoryAdvisor> ltmAdvisors) {
		this.shortTermMemoryAdvisor = sessionMemoryAdvisor;
		this.longTermMemoryAdvisors = (ltmAdvisors != null) ? List.copyOf(ltmAdvisors) : List.of();
		List<Advisor> combined = new ArrayList<>(this.longTermMemoryAdvisors);
		combined.add(sessionMemoryAdvisor);
		this.advisors = List.copyOf(combined);
	}

}
