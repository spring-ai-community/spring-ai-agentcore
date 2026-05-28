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

package org.springaicommunity.agentcore.context;

import org.junit.jupiter.api.Test;

import org.springframework.http.HttpHeaders;

import static org.assertj.core.api.Assertions.assertThat;

class AgentCoreContextTests {

	@Test
	void shouldReturnEmptyHeadersWhenNullProvided() {
		var context = new AgentCoreContext(null);
		var headers = context.getHeaders();

		assertThat(headers).isNotNull();
		assertThat(headers.isEmpty()).isTrue();
	}

	@Test
	void shouldReturnNullWithNullHeaderName() {
		var context = new AgentCoreContext(new HttpHeaders());
		var value = context.getHeader(null);

		assertThat(value).isNull();
	}

	@Test
	void shouldReturnNullForNonExistentHeader() {
		var context = new AgentCoreContext(new HttpHeaders());
		var value = context.getHeader("non-existent-header");

		assertThat(value).isNull();
	}

	@Test
	void shouldGetHeadersCorrectly() {
		var originalHeaders = new HttpHeaders();
		originalHeaders.add("test-header", "test-value");
		originalHeaders.add(AgentCoreHeaders.SESSION_ID, "session-123");

		var context = new AgentCoreContext(originalHeaders);

		var retrievedHeaders = context.getHeaders();
		assertThat(retrievedHeaders.getFirst("test-header")).isEqualTo("test-value");
		assertThat(retrievedHeaders.getFirst(AgentCoreHeaders.SESSION_ID)).isEqualTo("session-123");
	}

	@Test
	void shouldGetHeaderCorrectly() {
		var headers = new HttpHeaders();
		headers.add(AgentCoreHeaders.SESSION_ID, "session-456");
		headers.add(AgentCoreHeaders.REQUEST_ID, "req-789");

		var context = new AgentCoreContext(headers);

		assertThat(context.getHeader(AgentCoreHeaders.SESSION_ID)).isEqualTo("session-456");
		assertThat(context.getHeader(AgentCoreHeaders.REQUEST_ID)).isEqualTo("req-789");
		assertThat(context.getHeader("non-existent-header")).isNull();
	}

	@Test
	void shouldHandleEmptyHeaders() {
		var headers = new HttpHeaders();
		var context = new AgentCoreContext(headers);

		assertThat(context.getHeader("test-header")).isNull();
		assertThat(context.getHeaders().isEmpty()).isTrue();
	}

}
