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

import org.springaicommunity.agentcore.annotation.AgentCoreInvocation;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Lazy;

/**
 * BeanPostProcessor that scans for @AgentCoreInvocation annotated methods and registers
 * them with the AgentCoreMethodRegistry.
 */
public class AgentCoreMethodScanner implements BeanPostProcessor {

	private final AgentCoreMethodRegistry registry;

	public AgentCoreMethodScanner(@Lazy AgentCoreMethodRegistry registry) {
		this.registry = registry;
	}

	@Override
	public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
		var methods = bean.getClass().getDeclaredMethods();
		for (var method : methods) {
			if (method.isAnnotationPresent(AgentCoreInvocation.class)) {
				registry.registerMethod(bean, method);
			}
		}
		return bean;
	}

}
