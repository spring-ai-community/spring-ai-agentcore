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
 *
 * @author Vaquar Khan
 */
final class AgentCoreInvocationHeaderSupport {

	private static final ClassLoader CLASS_LOADER = AgentCoreInvocationHeaderSupport.class.getClassLoader();

	private static final Class<?> SERVLET_REQUEST = resolveClass("jakarta.servlet.http.HttpServletRequest");

	private static final Method SERVLET_GET_HEADER = resolveMethod(SERVLET_REQUEST, "getHeader", String.class);

	private static final Class<?> SERVER_WEB_EXCHANGE = resolveClass(
			"org.springframework.web.server.ServerWebExchange");

	private static final Method EXCHANGE_GET_REQUEST = resolveMethod(SERVER_WEB_EXCHANGE, "getRequest");

	private static final Class<?> SERVER_HTTP_REQUEST = resolveClass(
			"org.springframework.http.server.reactive.ServerHttpRequest");

	private static final Method SERVER_HTTP_REQUEST_GET_HEADERS = resolveMethod(SERVER_HTTP_REQUEST, "getHeaders");

	private static final Class<?> HTTP_HEADERS = resolveClass("org.springframework.http.HttpHeaders");

	private static final Method HTTP_HEADERS_GET_FIRST = resolveMethod(HTTP_HEADERS, "getFirst", String.class);

	private static final Class<?> REQUEST_CONTEXT_HOLDER = resolveClass(
			"org.springframework.web.context.request.RequestContextHolder");

	private static final Method HOLDER_GET_REQUEST_ATTRIBUTES = resolveMethod(REQUEST_CONTEXT_HOLDER,
			"getRequestAttributes");

	private static final Class<?> SERVLET_REQUEST_ATTRIBUTES = resolveClass(
			"org.springframework.web.context.request.ServletRequestAttributes");

	private static final Method ATTRIBUTES_GET_REQUEST = resolveMethod(SERVLET_REQUEST_ATTRIBUTES, "getRequest");

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
		if (SERVLET_REQUEST == null || SERVLET_GET_HEADER == null || !SERVLET_REQUEST.isInstance(a)) {
			return null;
		}
		try {
			return (String) SERVLET_GET_HEADER.invoke(a, name);
		}
		catch (ReflectiveOperationException | ClassCastException ex) {
			return null;
		}
	}

	private static String headerFromServerWebExchange(Object a, String name) {
		if (SERVER_WEB_EXCHANGE == null || EXCHANGE_GET_REQUEST == null || !SERVER_WEB_EXCHANGE.isInstance(a)) {
			return null;
		}
		try {
			Object request = EXCHANGE_GET_REQUEST.invoke(a);
			if (request == null || SERVER_HTTP_REQUEST == null || !SERVER_HTTP_REQUEST.isInstance(request)
					|| SERVER_HTTP_REQUEST_GET_HEADERS == null || HTTP_HEADERS_GET_FIRST == null) {
				return null;
			}
			Object headers = SERVER_HTTP_REQUEST_GET_HEADERS.invoke(request);
			if (headers == null || HTTP_HEADERS == null || !HTTP_HEADERS.isInstance(headers)) {
				return null;
			}
			Object first = HTTP_HEADERS_GET_FIRST.invoke(headers, name);
			if (first instanceof String s && !s.isEmpty()) {
				return s;
			}
		}
		catch (ReflectiveOperationException | ClassCastException ex) {
			return null;
		}
		return null;
	}

	private static String headerFromRequestContextHolder(String name) {
		if (REQUEST_CONTEXT_HOLDER == null || HOLDER_GET_REQUEST_ATTRIBUTES == null) {
			return null;
		}
		try {
			Object ra = HOLDER_GET_REQUEST_ATTRIBUTES.invoke(null);
			if (ra == null || SERVLET_REQUEST_ATTRIBUTES == null || ATTRIBUTES_GET_REQUEST == null
					|| !SERVLET_REQUEST_ATTRIBUTES.isInstance(ra)) {
				return null;
			}
			Object servletReq = ATTRIBUTES_GET_REQUEST.invoke(ra);
			return headerFromServletRequest(servletReq, name);
		}
		catch (ReflectiveOperationException ex) {
			return null;
		}
	}

	private static Class<?> resolveClass(String name) {
		try {
			return Class.forName(name, false, CLASS_LOADER);
		}
		catch (ClassNotFoundException ex) {
			return null;
		}
	}

	private static Method resolveMethod(Class<?> type, String methodName, Class<?>... parameterTypes) {
		if (type == null) {
			return null;
		}
		try {
			return type.getMethod(methodName, parameterTypes);
		}
		catch (NoSuchMethodException ex) {
			return null;
		}
	}

}
