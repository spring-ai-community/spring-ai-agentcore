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

import java.lang.reflect.Method;

import org.springaicommunity.agentcore.exception.AgentCoreInvocationException;

/**
 * Registry that stores exactly one AgentCore method per application. Enforces the single
 * method constraint for MVP.
 */
public class AgentCoreMethodRegistry {

	private Object agentBean;

	private Method agentMethod;

	public void registerMethod(Object bean, Method method) {
		if (agentBean != null) {
			throw new AgentCoreInvocationException(
					"Multiple @AgentCoreInvocation methods found. Only one is allowed in MVP.");
		}
		this.agentBean = bean;
		this.agentMethod = method;
	}

	public boolean hasAgentMethod() {
		return agentMethod != null;
	}

	public Object getAgentBean() {
		return agentBean;
	}

	public Method getAgentMethod() {
		return agentMethod;
	}

}
