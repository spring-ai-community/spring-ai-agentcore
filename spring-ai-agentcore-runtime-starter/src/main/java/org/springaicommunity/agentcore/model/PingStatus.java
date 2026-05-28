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

package org.springaicommunity.agentcore.model;

/**
 * AgentCore Runtime ping status values.
 *
 * @author Maximilian Schellhorn
 */
public enum PingStatus {

	/** ping status when the runtime is idle and ready to accept requests. */
	HEALTHY("Healthy"),
	/** ping status when the runtime is processing a long-running task. */
	HEALTHY_BUSY("HealthyBusy"),
	/** ping status when the runtime cannot serve traffic. */
	UNHEALTHY("Unhealthy");

	private final String value;

	PingStatus(String value) {
		this.value = value;
	}

	@Override
	public String toString() {
		return this.value;
	}

}
