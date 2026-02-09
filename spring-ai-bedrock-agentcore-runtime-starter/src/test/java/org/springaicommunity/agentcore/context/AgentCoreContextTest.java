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

package org.springaicommunity.agentcore.context;

import org.junit.jupiter.api.Test;

import org.springframework.http.HttpHeaders;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentCoreContextTest {

	@Test
	void shouldReturnEmptyHeadersWhenNullProvided() {
		var context = new AgentCoreContext(null);
		var headers = context.getHeaders();

		assertNotNull(headers);
		assertTrue(headers.isEmpty());
	}

	@Test
	void shouldReturnNullWithNullHeaderName() {
		var context = new AgentCoreContext(new HttpHeaders());
		var value = context.getHeader(null);

		assertNull(value);
	}

	@Test
	void shouldReturnNullForNonExistentHeader() {
		var context = new AgentCoreContext(new HttpHeaders());
		var value = context.getHeader("non-existent-header");

		assertNull(value);
	}

	@Test
	void shouldGetHeadersCorrectly() {
		var originalHeaders = new HttpHeaders();
		originalHeaders.add("test-header", "test-value");
		originalHeaders.add(AgentCoreHeaders.SESSION_ID, "session-123");

		var context = new AgentCoreContext(originalHeaders);

		var retrievedHeaders = context.getHeaders();
		assertEquals("test-value", retrievedHeaders.getFirst("test-header"));
		assertEquals("session-123", retrievedHeaders.getFirst(AgentCoreHeaders.SESSION_ID));
	}

	@Test
	void shouldGetHeaderCorrectly() {
		var headers = new HttpHeaders();
		headers.add(AgentCoreHeaders.SESSION_ID, "session-456");
		headers.add(AgentCoreHeaders.REQUEST_ID, "req-789");

		var context = new AgentCoreContext(headers);

		assertEquals("session-456", context.getHeader(AgentCoreHeaders.SESSION_ID));
		assertEquals("req-789", context.getHeader(AgentCoreHeaders.REQUEST_ID));
		assertNull(context.getHeader("non-existent-header"));
	}

	@Test
	void shouldHandleEmptyHeaders() {
		var headers = new HttpHeaders();
		var context = new AgentCoreContext(headers);

		assertNull(context.getHeader("test-header"));
		assertTrue(context.getHeaders().isEmpty());
	}

}
