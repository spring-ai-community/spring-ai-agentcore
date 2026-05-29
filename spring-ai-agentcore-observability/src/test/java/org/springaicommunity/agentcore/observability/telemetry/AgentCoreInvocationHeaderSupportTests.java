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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

class AgentCoreInvocationHeaderSupportTests {

	@Test
	void returnsNullWhenArgsNull() {
		ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
		given(pjp.getArgs()).willReturn(null);
		assertThat(AgentCoreInvocationHeaderSupport.firstHeader(pjp, "x-h")).isNull();
	}

	@Test
	void readsServletRequestHeader() {
		HttpServletRequest req = mock(HttpServletRequest.class);
		given(req.getHeader("x-amz-session")).willReturn("sid-1");
		ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
		given(pjp.getArgs()).willReturn(new Object[] { req });
		assertThat(AgentCoreInvocationHeaderSupport.firstHeader(pjp, "x-amz-session")).isEqualTo("sid-1");
	}

	@Test
	void readsWebExchangeHeaderViaGetFirst() {
		ServerWebExchange exchange = mock(ServerWebExchange.class);
		ServerHttpRequest shReq = mock(ServerHttpRequest.class);
		HttpHeaders headers = new HttpHeaders();
		headers.add("x-amz-session", "sid-wx");
		given(exchange.getRequest()).willReturn(shReq);
		given(shReq.getHeaders()).willReturn(headers);
		ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
		given(pjp.getArgs()).willReturn(new Object[] { exchange });
		assertThat(AgentCoreInvocationHeaderSupport.firstHeader(pjp, "x-amz-session")).isEqualTo("sid-wx");
	}

	@Test
	void webExchangeWithNullRequestReturnsNull() {
		ServerWebExchange exchange = mock(ServerWebExchange.class);
		given(exchange.getRequest()).willReturn(null);
		ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
		given(pjp.getArgs()).willReturn(new Object[] { exchange });
		assertThat(AgentCoreInvocationHeaderSupport.firstHeader(pjp, "h")).isNull();
	}

	@Test
	void webExchangeWithNullHeadersReturnsNull() {
		ServerHttpRequest shReq = mock(ServerHttpRequest.class);
		given(shReq.getHeaders()).willReturn(null);
		ServerWebExchange exchange = mock(ServerWebExchange.class);
		given(exchange.getRequest()).willReturn(shReq);
		ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
		given(pjp.getArgs()).willReturn(new Object[] { exchange });
		assertThat(AgentCoreInvocationHeaderSupport.firstHeader(pjp, "h")).isNull();
	}

	@Test
	void readsFromRequestContextHolderWhenNotInArgs() {
		MockHttpServletRequest req = new MockHttpServletRequest();
		req.addHeader("x-amzn-request-id", "rid-ctx");
		RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req));
		try {
			ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
			given(pjp.getArgs()).willReturn(new Object[0]);
			assertThat(AgentCoreInvocationHeaderSupport.firstHeader(pjp, "x-amzn-request-id")).isEqualTo("rid-ctx");
		}
		finally {
			RequestContextHolder.resetRequestAttributes();
		}
	}

	@Test
	void ignoresNonServletArg() {
		ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
		given(pjp.getArgs()).willReturn(new Object[] { "not-a-request" });
		assertThat(AgentCoreInvocationHeaderSupport.firstHeader(pjp, "x")).isNull();
	}

	@Test
	void skipsNullArgsBeforeValidServletRequest() {
		HttpServletRequest req = mock(HttpServletRequest.class);
		given(req.getHeader("h")).willReturn("v");
		ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
		given(pjp.getArgs()).willReturn(new Object[] { null, req });
		assertThat(AgentCoreInvocationHeaderSupport.firstHeader(pjp, "h")).isEqualTo("v");
	}

	@Test
	void requestContextHolderIgnoresNonServletRequestAttributes() {
		RequestAttributes plain = mock(RequestAttributes.class);
		RequestContextHolder.setRequestAttributes(plain);
		try {
			ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
			given(pjp.getArgs()).willReturn(new Object[0]);
			assertThat(AgentCoreInvocationHeaderSupport.firstHeader(pjp, "x-amzn-request-id")).isNull();
		}
		finally {
			RequestContextHolder.resetRequestAttributes();
		}
	}

	@Test
	void servletGetHeaderReflectiveFailureReturnsNull() {
		HttpServletRequest req = mock(HttpServletRequest.class);
		given(req.getHeader(anyString())).willThrow(new IllegalStateException("simulated"));
		ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
		given(pjp.getArgs()).willReturn(new Object[] { req });
		assertThat(AgentCoreInvocationHeaderSupport.firstHeader(pjp, "x")).isNull();
	}

	@Test
	void requestContextHolderReturnsNullWhenAttributesAbsent() {
		RequestContextHolder.resetRequestAttributes();
		ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
		given(pjp.getArgs()).willReturn(new Object[0]);
		assertThat(AgentCoreInvocationHeaderSupport.firstHeader(pjp, "x-amzn-request-id")).isNull();
	}

	@Test
	void webExchangeEmptyHeaderFallsThroughToServletArg() {
		ServerWebExchange exchange = mock(ServerWebExchange.class);
		ServerHttpRequest shReq = mock(ServerHttpRequest.class);
		HttpHeaders headers = new HttpHeaders();
		headers.add("x-h", "");
		given(exchange.getRequest()).willReturn(shReq);
		given(shReq.getHeaders()).willReturn(headers);
		HttpServletRequest servlet = mock(HttpServletRequest.class);
		given(servlet.getHeader("x-h")).willReturn("from-servlet");
		ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
		given(pjp.getArgs()).willReturn(new Object[] { exchange, servlet });
		assertThat(AgentCoreInvocationHeaderSupport.firstHeader(pjp, "x-h")).isEqualTo("from-servlet");
	}

	@Test
	void servletEmptyHeaderFallsThroughToLaterArg() {
		HttpServletRequest emptyHeader = mock(HttpServletRequest.class);
		given(emptyHeader.getHeader("x")).willReturn("");
		HttpServletRequest withHeader = mock(HttpServletRequest.class);
		given(withHeader.getHeader("x")).willReturn("second");
		ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
		given(pjp.getArgs()).willReturn(new Object[] { emptyHeader, withHeader });
		assertThat(AgentCoreInvocationHeaderSupport.firstHeader(pjp, "x")).isEqualTo("second");
	}

}
