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

import org.jspecify.annotations.Nullable;

/**
 * Thread-local holder for the workload access token extracted from AgentCore Runtime
 * invocation headers. Populated automatically by {@link WorkloadAccessTokenCallback}
 * before each invocation and cleared afterwards.
 *
 * <p>
 * Each holder instance owns its thread-local state, so overlapping application contexts
 * remain isolated. Reactive invocation results carry the token in their Reactor
 * {@code Context} without registering JVM-global propagation hooks.
 *
 * @author Matej Nedic
 */
public class WorkloadAccessTokenHolder {

	private final ThreadLocal<String> token = new ThreadLocal<>();

	/**
	 * Sets the workload access token for the current thread.
	 * @param workloadAccessToken workload access token
	 */
	public void set(String workloadAccessToken) {
		this.token.set(workloadAccessToken);
	}

	/**
	 * Returns the workload access token associated with the current thread.
	 * @return the workload access token, or {@code null} when none is associated
	 */
	public @Nullable String get() {
		return this.token.get();
	}

	/**
	 * Removes the workload access token associated with the current thread.
	 */
	public void clear() {
		this.token.remove();
	}

}
