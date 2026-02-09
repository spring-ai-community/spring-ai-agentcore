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

package org.springaicommunity.agentcore.controller;

import org.junit.jupiter.api.Test;
import org.springaicommunity.agentcore.autoconfigure.AgentCoreAutoConfiguration;
import org.springaicommunity.agentcore.model.AgentCorePingResponse;
import org.springaicommunity.agentcore.model.PingStatus;
import org.springaicommunity.agentcore.ping.AgentCorePingService;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = { AgentCorePingController.class })
@Import({ AgentCoreAutoConfiguration.class, AgentCorePingControllerTest.TestConfig.class })
class AgentCorePingControllerTest {

	@SpringBootApplication
	static class TestConfig {

	}

	@Autowired
	private MockMvc mockMvc;

	@MockBean
	private AgentCorePingService mockPingService;

	@Test
	void shouldReturnHealthyStatus() throws Exception {
		when(mockPingService.getPingStatus())
			.thenReturn(new AgentCorePingResponse(PingStatus.HEALTHY, HttpStatus.OK, 1234567890L));

		mockMvc.perform(get("/ping"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("Healthy"))
			.andExpect(jsonPath("$.time_of_last_update").value(1234567890L));
	}

	@Test
	void shouldReturnHealthyBusyStatus() throws Exception {
		when(mockPingService.getPingStatus())
			.thenReturn(new AgentCorePingResponse(PingStatus.HEALTHY_BUSY, HttpStatus.OK, 1234567890L));

		mockMvc.perform(get("/ping"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("HealthyBusy"))
			.andExpect(jsonPath("$.time_of_last_update").value(1234567890L));
	}

	@Test
	void shouldReturnUnhealthyStatus() throws Exception {
		when(mockPingService.getPingStatus())
			.thenReturn(new AgentCorePingResponse(PingStatus.UNHEALTHY, HttpStatus.SERVICE_UNAVAILABLE, 1234567890L));

		mockMvc.perform(get("/ping"))
			.andExpect(status().isServiceUnavailable())
			.andExpect(jsonPath("$.status").value("Unhealthy"))
			.andExpect(jsonPath("$.time_of_last_update").value(1234567890L));
	}

}
