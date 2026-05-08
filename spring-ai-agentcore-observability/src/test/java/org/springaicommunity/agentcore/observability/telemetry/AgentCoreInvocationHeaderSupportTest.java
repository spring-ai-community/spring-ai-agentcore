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

package org.springaicommunity.agentcore.observability.telemetry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import jakarta.servlet.http.HttpServletRequest;
import org.aspectj.lang.ProceedingJoinPoint;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.server.ServerWebExchange;

class AgentCoreInvocationHeaderSupportTest {

	@Test
	void returnsNullWhenArgsNull() {
		ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
		when(pjp.getArgs()).thenReturn(null);
		assertThat(AgentCoreInvocationHeaderSupport.firstHeader(pjp, "x-h")).isNull();
	}

	@Test
	void readsServletRequestHeader() {
		HttpServletRequest req = mock(HttpServletRequest.class);
		when(req.getHeader("x-amz-session")).thenReturn("sid-1");
		ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
		when(pjp.getArgs()).thenReturn(new Object[] { req });
		assertThat(AgentCoreInvocationHeaderSupport.firstHeader(pjp, "x-amz-session")).isEqualTo("sid-1");
	}

	@Test
	void readsWebExchangeHeaderViaGetFirst() {
		ServerWebExchange exchange = mock(ServerWebExchange.class);
		ServerHttpRequest shReq = mock(ServerHttpRequest.class);
		HttpHeaders headers = new HttpHeaders();
		headers.add("x-amz-session", "sid-wx");
		when(exchange.getRequest()).thenReturn(shReq);
		when(shReq.getHeaders()).thenReturn(headers);
		ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
		when(pjp.getArgs()).thenReturn(new Object[] { exchange });
		assertThat(AgentCoreInvocationHeaderSupport.firstHeader(pjp, "x-amz-session")).isEqualTo("sid-wx");
	}

	@Test
	void webExchangeWithNullRequestReturnsNull() {
		ServerWebExchange exchange = mock(ServerWebExchange.class);
		when(exchange.getRequest()).thenReturn(null);
		ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
		when(pjp.getArgs()).thenReturn(new Object[] { exchange });
		assertThat(AgentCoreInvocationHeaderSupport.firstHeader(pjp, "h")).isNull();
	}

	@Test
	void webExchangeWithNullHeadersReturnsNull() {
		ServerHttpRequest shReq = mock(ServerHttpRequest.class);
		when(shReq.getHeaders()).thenReturn(null);
		ServerWebExchange exchange = mock(ServerWebExchange.class);
		when(exchange.getRequest()).thenReturn(shReq);
		ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
		when(pjp.getArgs()).thenReturn(new Object[] { exchange });
		assertThat(AgentCoreInvocationHeaderSupport.firstHeader(pjp, "h")).isNull();
	}

	@Test
	void readsFromRequestContextHolderWhenNotInArgs() {
		MockHttpServletRequest req = new MockHttpServletRequest();
		req.addHeader("x-amzn-request-id", "rid-ctx");
		RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req));
		try {
			ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
			when(pjp.getArgs()).thenReturn(new Object[0]);
			assertThat(AgentCoreInvocationHeaderSupport.firstHeader(pjp, "x-amzn-request-id")).isEqualTo("rid-ctx");
		}
		finally {
			RequestContextHolder.resetRequestAttributes();
		}
	}

	@Test
	void ignoresNonServletArg() {
		ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
		when(pjp.getArgs()).thenReturn(new Object[] { "not-a-request" });
		assertThat(AgentCoreInvocationHeaderSupport.firstHeader(pjp, "x")).isNull();
	}

	@Test
	void skipsNullArgsBeforeValidServletRequest() {
		HttpServletRequest req = mock(HttpServletRequest.class);
		when(req.getHeader("h")).thenReturn("v");
		ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
		when(pjp.getArgs()).thenReturn(new Object[] { null, req });
		assertThat(AgentCoreInvocationHeaderSupport.firstHeader(pjp, "h")).isEqualTo("v");
	}

	@Test
	void requestContextHolderIgnoresNonServletRequestAttributes() {
		RequestAttributes plain = mock(RequestAttributes.class);
		RequestContextHolder.setRequestAttributes(plain);
		try {
			ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
			when(pjp.getArgs()).thenReturn(new Object[0]);
			assertThat(AgentCoreInvocationHeaderSupport.firstHeader(pjp, "x-amzn-request-id")).isNull();
		}
		finally {
			RequestContextHolder.resetRequestAttributes();
		}
	}

	@Test
	void servletGetHeaderReflectiveFailureReturnsNull() {
		HttpServletRequest req = mock(HttpServletRequest.class);
		when(req.getHeader(anyString())).thenThrow(new IllegalStateException("simulated"));
		ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
		when(pjp.getArgs()).thenReturn(new Object[] { req });
		assertThat(AgentCoreInvocationHeaderSupport.firstHeader(pjp, "x")).isNull();
	}

	@Test
	void servletEmptyHeaderFallsThroughToLaterArg() {
		HttpServletRequest emptyHeader = mock(HttpServletRequest.class);
		when(emptyHeader.getHeader("x")).thenReturn("");
		HttpServletRequest withHeader = mock(HttpServletRequest.class);
		when(withHeader.getHeader("x")).thenReturn("second");
		ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
		when(pjp.getArgs()).thenReturn(new Object[] { emptyHeader, withHeader });
		assertThat(AgentCoreInvocationHeaderSupport.firstHeader(pjp, "x")).isEqualTo("second");
	}

}
