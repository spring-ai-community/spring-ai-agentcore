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

import java.lang.reflect.Method;
import org.aspectj.lang.ProceedingJoinPoint;

/**
 * Resolves HTTP headers from servlet / WebFlux request objects without compile-time
 * dependencies on {@code spring-web} or {@code spring-webflux}, so those jars can be
 * optional for consumers that only need the synchronous
 * {@link org.springframework.ai.chat.model.ChatResponse} path.
 */
final class AgentCoreInvocationHeaderSupport {

	private AgentCoreInvocationHeaderSupport() {
	}

	static String firstHeader(ProceedingJoinPoint joinPoint, String name) {
		Object[] args = joinPoint.getArgs();
		if (args != null) {
			for (Object a : args) {
				if (a == null) {
					continue;
				}
				String v = headerFromServletRequest(a, name);
				if (v != null && !v.isEmpty()) {
					return v;
				}
				v = headerFromServerWebExchange(a, name);
				if (v != null && !v.isEmpty()) {
					return v;
				}
			}
		}
		return headerFromRequestContextHolder(name);
	}

	private static String headerFromServletRequest(Object a, String name) {
		try {
			Class<?> reqType = Class.forName("jakarta.servlet.http.HttpServletRequest", false,
					AgentCoreInvocationHeaderSupport.class.getClassLoader());
			if (!reqType.isInstance(a)) {
				return null;
			}
			return (String) reqType.getMethod("getHeader", String.class).invoke(a, name);
		}
		catch (ClassNotFoundException e) {
			return null;
		}
		catch (ReflectiveOperationException | ClassCastException e) {
			return null;
		}
	}

	private static String headerFromServerWebExchange(Object a, String name) {
		try {
			Class<?> exchangeType = Class.forName("org.springframework.web.server.ServerWebExchange", false,
					AgentCoreInvocationHeaderSupport.class.getClassLoader());
			if (!exchangeType.isInstance(a)) {
				return null;
			}
			Object request = exchangeType.getMethod("getRequest").invoke(a);
			if (request == null) {
				return null;
			}
			Object headers = request.getClass().getMethod("getHeaders").invoke(request);
			if (headers == null) {
				return null;
			}
			Object first = headers.getClass().getMethod("getFirst", String.class).invoke(headers, name);
			if (first instanceof String s && !s.isEmpty()) {
				return s;
			}
		}
		catch (ClassNotFoundException e) {
			return null;
		}
		catch (ReflectiveOperationException | ClassCastException e) {
			return null;
		}
		return null;
	}

	private static String headerFromRequestContextHolder(String name) {
		try {
			Class<?> holder = Class.forName("org.springframework.web.context.request.RequestContextHolder", false,
					AgentCoreInvocationHeaderSupport.class.getClassLoader());
			Object ra = holder.getMethod("getRequestAttributes").invoke(null);
			if (ra == null) {
				return null;
			}
			Class<?> sraType = Class.forName("org.springframework.web.context.request.ServletRequestAttributes", false,
					AgentCoreInvocationHeaderSupport.class.getClassLoader());
			if (!sraType.isInstance(ra)) {
				return null;
			}
			Method getRequest = sraType.getMethod("getRequest");
			Object servletReq = getRequest.invoke(ra);
			return headerFromServletRequest(servletReq, name);
		}
		catch (ClassNotFoundException e) {
			return null;
		}
		catch (ReflectiveOperationException e) {
			return null;
		}
	}

}
