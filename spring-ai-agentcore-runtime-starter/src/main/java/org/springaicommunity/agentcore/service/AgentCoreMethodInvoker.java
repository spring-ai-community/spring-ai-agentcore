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

package org.springaicommunity.agentcore.service;

import java.lang.reflect.InvocationTargetException;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springaicommunity.agentcore.context.AgentCoreContext;
import org.springaicommunity.agentcore.exception.AgentCoreInvocationException;

import org.springframework.http.HttpHeaders;

public class AgentCoreMethodInvoker {

	private final ObjectMapper objectMapper;

	private final AgentCoreMethodRegistry registry;

	public AgentCoreMethodInvoker(ObjectMapper objectMapper, AgentCoreMethodRegistry registry) {
		this.objectMapper = objectMapper;
		this.registry = registry;
	}

	public Object invokeAgentMethod(Object request, HttpHeaders headers) throws Exception {
		if (!registry.hasAgentMethod()) {
			throw new AgentCoreInvocationException("No @AgentCoreInvocation method found");
		}

		var method = registry.getAgentMethod();
		var bean = registry.getAgentBean();
		var paramTypes = method.getParameterTypes();

		Object[] args = prepareArguments(request, headers, paramTypes);

		try {
			return method.invoke(bean, args);
		}

		catch (InvocationTargetException e) {
			if (e.getCause() instanceof Exception exception) {
				throw exception;
			}
			throw new AgentCoreInvocationException("Method invocation failed", e);
		}
	}

	public Object invokeAgentMethod(Object request) throws Exception {
		return invokeAgentMethod(request, new HttpHeaders());
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
			return new Object[] { convertRequest(request, paramType) };
		}

		if (paramTypes.length == 2 && contextIndex != -1) {
			Object[] args = new Object[2];

			// Set context parameter
			args[contextIndex] = new AgentCoreContext(headers);

			// Set request parameter
			int requestIndex = contextIndex == 0 ? 1 : 0;
			Class<?> requestType = paramTypes[requestIndex];

			if (requestType.isAssignableFrom(request.getClass())) {
				args[requestIndex] = request;
			}

			else {
				args[requestIndex] = convertRequest(request, requestType);
			}

			return args;
		}

		throw new AgentCoreInvocationException("Unsupported parameter combination");
	}

	private Object convertRequest(Object request, Class<?> targetType) {
		try {
			if (request instanceof String json) {
				return objectMapper.readValue(json, targetType);
			}

			// Object to JSON to target type conversion
			String json = objectMapper.writeValueAsString(request);
			return objectMapper.readValue(json, targetType);
		}

		catch (Exception e) {
			throw new AgentCoreInvocationException("Type conversion failed", e);
		}
	}

}
