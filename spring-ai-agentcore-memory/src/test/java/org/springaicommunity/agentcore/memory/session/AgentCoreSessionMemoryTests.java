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

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springaicommunity.agentcore.memory.longterm.AgentCoreLongTermMemoryAdvisor;

import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.session.advisor.SessionMemoryAdvisor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Unit tests for {@link AgentCoreSessionMemory}.
 */
class AgentCoreSessionMemoryTests {

	@Test
	void constructorOrdersLtmAdvisorsFirstThenSessionAdvisor() {
		SessionMemoryAdvisor sessionAdvisor = mock(SessionMemoryAdvisor.class);
		AgentCoreLongTermMemoryAdvisor ltm1 = mock(AgentCoreLongTermMemoryAdvisor.class);
		AgentCoreLongTermMemoryAdvisor ltm2 = mock(AgentCoreLongTermMemoryAdvisor.class);

		AgentCoreSessionMemory memory = new AgentCoreSessionMemory(sessionAdvisor, List.of(ltm1, ltm2));

		assertThat(memory.shortTermMemoryAdvisor).isSameAs(sessionAdvisor);
		assertThat(memory.longTermMemoryAdvisors).containsExactly(ltm1, ltm2);
		assertThat(memory.advisors).containsExactly((Advisor) ltm1, (Advisor) ltm2, (Advisor) sessionAdvisor);
	}

	@Test
	void constructorHandlesEmptyLtmList() {
		SessionMemoryAdvisor sessionAdvisor = mock(SessionMemoryAdvisor.class);
		AgentCoreSessionMemory memory = new AgentCoreSessionMemory(sessionAdvisor, List.of());
		assertThat(memory.longTermMemoryAdvisors).isEmpty();
		assertThat(memory.advisors).containsExactly((Advisor) sessionAdvisor);
	}

	@Test
	void constructorHandlesNullLtmListAsEmpty() {
		SessionMemoryAdvisor sessionAdvisor = mock(SessionMemoryAdvisor.class);
		AgentCoreSessionMemory memory = new AgentCoreSessionMemory(sessionAdvisor, null);
		assertThat(memory.longTermMemoryAdvisors).isEmpty();
		assertThat(memory.advisors).containsExactly((Advisor) sessionAdvisor);
	}

}
