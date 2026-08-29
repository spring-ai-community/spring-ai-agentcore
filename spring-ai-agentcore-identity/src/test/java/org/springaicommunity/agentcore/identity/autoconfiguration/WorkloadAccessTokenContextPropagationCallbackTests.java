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

import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.context.ContextRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springaicommunity.agentcore.annotation.AgentCoreInvocation;
import org.springaicommunity.agentcore.context.AgentCoreHeaders;
import org.springaicommunity.agentcore.identity.core.WorkloadAccessTokenAccessor;
import org.springaicommunity.agentcore.identity.core.WorkloadAccessTokenCallback;
import org.springaicommunity.agentcore.identity.core.WorkloadAccessTokenHolder;
import org.springaicommunity.agentcore.service.AgentCoreMethodInvoker;
import org.springaicommunity.agentcore.service.AgentCoreMethodRegistry;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Hooks;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;

import org.springframework.http.HttpHeaders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

class WorkloadAccessTokenContextPropagationCallbackTests {

	private final WorkloadAccessTokenHolder holder = new WorkloadAccessTokenHolder();

	@BeforeEach
	void setUp() {
		ContextRegistry.getInstance().removeThreadLocalAccessor(WorkloadAccessTokenAccessor.KEY);
		Hooks.disableAutomaticContextPropagation();
	}

	@AfterEach
	void tearDown() {
		this.holder.clear();
	}

	@Test
	void propagatesTokenInReactorContextWithoutGlobalThreadLocalRestoration() throws Exception {
		AgentCoreMethodRegistry methodRegistry = mock(AgentCoreMethodRegistry.class);
		StreamingBean bean = new StreamingBean(this.holder);
		var method = StreamingBean.class.getDeclaredMethod("stream", String.class);
		method.setAccessible(true);
		given(methodRegistry.hasAgentMethod()).willReturn(true);
		given(methodRegistry.getAgentMethod()).willReturn(method);
		given(methodRegistry.getAgentBean()).willReturn(bean);

		AgentCoreMethodInvoker invoker = new AgentCoreMethodInvoker(new ObjectMapper(), methodRegistry, List.of(
				new WorkloadAccessTokenCallback(this.holder),
				new WorkloadAccessTokenContextPropagationCallback(new WorkloadAccessTokenAccessor(this.holder))));
		HttpHeaders headers = new HttpHeaders();
		headers.add(AgentCoreHeaders.WORKLOAD_ACCESS_TOKEN_RUNTIME, "secret-token");

		Object result = invoker.invokeAgentMethod("prompt", headers);

		assertThat(this.holder.get()).isNull();
		assertThat(result).isInstanceOf(Flux.class);
		assertThat(ContextRegistry.getInstance().getThreadLocalAccessors()).extracting((accessor) -> accessor.key())
			.doesNotContain(WorkloadAccessTokenAccessor.KEY);
		assertThat(Hooks.isAutomaticContextPropagationEnabled()).isFalse();
		@SuppressWarnings("unchecked")
		Flux<String> flux = (Flux<String>) result;
		StepVerifier.create(flux).expectNext("secret-token:MISSING").verifyComplete();
	}

	static class StreamingBean {

		private final WorkloadAccessTokenHolder holder;

		StreamingBean(WorkloadAccessTokenHolder holder) {
			this.holder = holder;
		}

		@AgentCoreInvocation
		Flux<String> stream(String prompt) {
			return Flux.just(prompt)
				.publishOn(Schedulers.boundedElastic())
				.flatMap((value) -> Mono.deferContextual((context) -> Mono
					.just(context.get(WorkloadAccessTokenAccessor.KEY) + ":" + this.ambientTokenStatus())));
		}

		private String ambientTokenStatus() {
			return (this.holder.get() != null) ? this.holder.get() : "MISSING";
		}

	}

}
