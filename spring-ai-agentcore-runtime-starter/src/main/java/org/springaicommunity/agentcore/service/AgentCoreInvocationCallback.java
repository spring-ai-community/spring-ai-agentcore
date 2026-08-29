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

package org.springaicommunity.agentcore.service;

import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;

/**
 * Callback interface invoked before and after each {@code @AgentCoreInvocation} method
 * execution. Implementations may inspect or enrich the request context, transform the
 * invocation result, or clean up invocation-scoped state.
 *
 * <p>
 * Multiple callbacks are executed in {@link Ordered} order. Result processing happens
 * before {@link #afterInvocation}, which is guaranteed to run in a {@code finally} block
 * even when the invocation throws.
 *
 * @author Matej Nedic
 */
public interface AgentCoreInvocationCallback extends Ordered {

	/**
	 * Called before the {@code @AgentCoreInvocation} method is invoked.
	 * @param request the deserialized request body
	 * @param headers the HTTP headers from the invocation request
	 */
	void beforeInvocation(Object request, HttpHeaders headers);

	/**
	 * Processes the value returned by the {@code @AgentCoreInvocation} method while the
	 * invocation context is still active.
	 * @param request the deserialized request body
	 * @param headers the HTTP headers from the invocation request
	 * @param result the value returned by the invocation method
	 * @return the value to return from the invocation
	 */
	default Object processResult(Object request, HttpHeaders headers, Object result) {
		return result;
	}

	/**
	 * Called after the {@code @AgentCoreInvocation} method completes (or throws).
	 * Guaranteed to execute in a {@code finally} block.
	 * @param request the deserialized request body
	 * @param headers the HTTP headers from the invocation request
	 */
	void afterInvocation(Object request, HttpHeaders headers);

	@Override
	default int getOrder() {
		return Ordered.LOWEST_PRECEDENCE;
	}

}
