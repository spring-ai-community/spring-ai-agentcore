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

package org.springaicommunity.agentcore.identity.autoconfiguration;

import io.micrometer.context.ContextRegistry;
import io.micrometer.context.ContextSnapshot;
import io.micrometer.context.ContextSnapshotFactory;
import org.reactivestreams.Publisher;
import org.springaicommunity.agentcore.identity.core.WorkloadAccessTokenAccessor;
import org.springaicommunity.agentcore.service.AgentCoreInvocationCallback;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.context.ReactorContextAccessor;

import org.springframework.http.HttpHeaders;

/**
 * Captures the current identity context with an application-context-scoped registry and
 * attaches it to a reactive invocation result before invocation-scoped thread-local state
 * is cleared. It does not alter Reactor's JVM-global hooks.
 *
 * @author Matej Nedic
 */
class WorkloadAccessTokenContextPropagationCallback implements AgentCoreInvocationCallback {

	private final ContextSnapshotFactory snapshotFactory;

	WorkloadAccessTokenContextPropagationCallback(WorkloadAccessTokenAccessor accessor) {
		ContextRegistry registry = new ContextRegistry().registerContextAccessor(new ReactorContextAccessor())
			.registerThreadLocalAccessor(accessor);
		this.snapshotFactory = ContextSnapshotFactory.builder().contextRegistry(registry).build();
	}

	@Override
	public void beforeInvocation(Object request, HttpHeaders headers) {
	}

	@Override
	public Object processResult(Object request, HttpHeaders headers, Object result) {
		if (result instanceof Flux<?> flux) {
			ContextSnapshot snapshot = this.snapshotFactory.captureAll();
			return flux.contextWrite(snapshot::updateContext);
		}
		if (result instanceof Mono<?> mono) {
			ContextSnapshot snapshot = this.snapshotFactory.captureAll();
			return mono.contextWrite(snapshot::updateContext);
		}
		if (result instanceof Publisher<?> publisher) {
			ContextSnapshot snapshot = this.snapshotFactory.captureAll();
			return Flux.from(publisher).contextWrite(snapshot::updateContext);
		}
		return result;
	}

	@Override
	public void afterInvocation(Object request, HttpHeaders headers) {
	}

}
