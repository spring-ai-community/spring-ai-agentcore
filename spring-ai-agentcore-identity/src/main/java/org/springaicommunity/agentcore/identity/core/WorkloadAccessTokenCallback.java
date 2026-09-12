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

package org.springaicommunity.agentcore.identity.core;

import org.springaicommunity.agentcore.context.AgentCoreHeaders;
import org.springaicommunity.agentcore.service.AgentCoreInvocationCallback;

import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;

/**
 * Invocation callback that extracts the workload access token from the AgentCore Runtime
 * request headers and stores it in a {@link WorkloadAccessTokenHolder} for the duration
 * of the invocation.
 *
 * <p>
 * Runs at {@link Ordered#HIGHEST_PRECEDENCE} so the token is available to all subsequent
 * callbacks and the invocation method itself.
 *
 * @author Matej Nedic
 */
public class WorkloadAccessTokenCallback implements AgentCoreInvocationCallback {

	private final WorkloadAccessTokenHolder holder;

	public WorkloadAccessTokenCallback(WorkloadAccessTokenHolder holder) {
		this.holder = holder;
	}

	@Override
	public void beforeInvocation(Object request, HttpHeaders headers) {
		String token = headers.getFirst(AgentCoreHeaders.WORKLOAD_ACCESS_TOKEN_RUNTIME);
		if (token != null) {
			this.holder.set(token);
		}
	}

	@Override
	public void afterInvocation(Object request, HttpHeaders headers) {
		this.holder.clear();
	}

	@Override
	public int getOrder() {
		return Ordered.HIGHEST_PRECEDENCE;
	}

}
