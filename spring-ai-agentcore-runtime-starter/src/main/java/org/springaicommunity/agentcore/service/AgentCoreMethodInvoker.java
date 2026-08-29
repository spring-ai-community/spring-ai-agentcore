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

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springaicommunity.agentcore.context.AgentCoreContext;
import org.springaicommunity.agentcore.exception.AgentCoreInvocationException;

import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.http.HttpHeaders;

public class AgentCoreMethodInvoker {

	private static final Logger logger = LoggerFactory.getLogger(AgentCoreMethodInvoker.class);

	private final ObjectMapper objectMapper;

	private final AgentCoreMethodRegistry registry;

	private final List<AgentCoreInvocationCallback> callbacks;

	public AgentCoreMethodInvoker(ObjectMapper objectMapper, AgentCoreMethodRegistry registry) {
		this(objectMapper, registry, List.of());
	}

	public AgentCoreMethodInvoker(ObjectMapper objectMapper, AgentCoreMethodRegistry registry,
			List<AgentCoreInvocationCallback> callbacks) {
		this.objectMapper = objectMapper;
		this.registry = registry;
		List<AgentCoreInvocationCallback> orderedCallbacks = new ArrayList<>(callbacks);
		AnnotationAwareOrderComparator.sort(orderedCallbacks);
		this.callbacks = orderedCallbacks;
	}

	public Object invokeAgentMethod(Object request, HttpHeaders headers) throws Exception {
		if (!this.registry.hasAgentMethod()) {
			throw new AgentCoreInvocationException("No @AgentCoreInvocation method found");
		}

		List<AgentCoreInvocationCallback> startedCallbacks = new ArrayList<>();
		Throwable invocationFailure = null;
		try {
			for (AgentCoreInvocationCallback callback : this.callbacks) {
				startedCallbacks.add(callback);
				callback.beforeInvocation(request, headers);
			}
			var method = this.registry.getAgentMethod();
			var bean = this.registry.getAgentBean();
			var paramTypes = method.getParameterTypes();

			Object[] args = this.prepareArguments(request, headers, paramTypes);

			try {
				Object result = method.invoke(bean, args);
				for (AgentCoreInvocationCallback callback : this.callbacks) {
					result = callback.processResult(request, headers, result);
				}
				return result;
			}
			catch (InvocationTargetException ex) {
				if (ex.getCause() instanceof Exception exception) {
					throw exception;
				}
				throw new AgentCoreInvocationException("Method invocation failed", ex);
			}
		}
		catch (Exception | Error ex) {
			invocationFailure = ex;
			throw ex;
		}
		finally {
			cleanupStartedCallbacks(startedCallbacks, request, headers, invocationFailure);
		}
	}

	public Object invokeAgentMethod(Object request) throws Exception {
		return this.invokeAgentMethod(request, new HttpHeaders());
	}

	private static void cleanupStartedCallbacks(List<AgentCoreInvocationCallback> startedCallbacks, Object request,
			HttpHeaders headers, Throwable invocationFailure) throws Exception {
		Throwable cleanupFailure = null;
		for (int i = startedCallbacks.size() - 1; i >= 0; i--) {
			AgentCoreInvocationCallback callback = startedCallbacks.get(i);
			try {
				callback.afterInvocation(request, headers);
			}
			catch (Throwable ex) {
				logger.warn("AgentCore invocation callback cleanup failed: {}", callback.getClass().getName(), ex);
				if (invocationFailure != null) {
					invocationFailure.addSuppressed(ex);
				}
				else if (cleanupFailure == null) {
					cleanupFailure = ex;
				}
				else {
					cleanupFailure.addSuppressed(ex);
				}
			}
		}
		if (invocationFailure == null && cleanupFailure != null) {
			rethrowCleanupFailure(cleanupFailure);
		}
	}

	private static void rethrowCleanupFailure(Throwable failure) throws Exception {
		if (failure instanceof Exception exception) {
			throw exception;
		}
		if (failure instanceof Error error) {
			throw error;
		}
		throw new AgentCoreInvocationException("Invocation callback cleanup failed", failure);
	}

	private Object[] prepareArguments(Object request, HttpHeaders headers, Class<?>[] paramTypes) {
		if (paramTypes.length == 0) {
			return new Object[0];
		}

		// Find AgentCoreContext parameter index
		int contextIndex = -1;
		for (int i = 0; i < paramTypes.length; i++) {
			if (paramTypes[i] == AgentCoreContext.class) {
				contextIndex = i;
				break;
			}
		}

		if (paramTypes.length == 1) {
			Class<?> paramType = paramTypes[0];

			// Handle AgentCoreContext parameter
			if (paramType == AgentCoreContext.class) {
				return new Object[] { new AgentCoreContext(headers) };
			}

			// Direct assignment if types match
			if (paramType.isAssignableFrom(request.getClass())) {
				return new Object[] { request };
			}

			// JSON conversion for complex types
			return new Object[] { this.convertRequest(request, paramType) };
		}

		if (paramTypes.length == 2 && contextIndex != -1) {
			Object[] args = new Object[2];

			// Set context parameter
			args[contextIndex] = new AgentCoreContext(headers);

			// Set request parameter
			int requestIndex = (contextIndex != 0) ? 0 : 1;
			Class<?> requestType = paramTypes[requestIndex];

			if (requestType.isAssignableFrom(request.getClass())) {
				args[requestIndex] = request;
			}

			else {
				args[requestIndex] = this.convertRequest(request, requestType);
			}

			return args;
		}

		throw new AgentCoreInvocationException("Unsupported parameter combination");
	}

	private Object convertRequest(Object request, Class<?> targetType) {
		try {
			if (request instanceof String json) {
				return this.objectMapper.readValue(json, targetType);
			}

			// Object to JSON to target type conversion
			String json = this.objectMapper.writeValueAsString(request);
			return this.objectMapper.readValue(json, targetType);
		}

		catch (Exception ex) {
			throw new AgentCoreInvocationException("Type conversion failed", ex);
		}
	}

}
