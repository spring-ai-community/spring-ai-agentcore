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
package org.springaicommunity.example.identity;

import java.util.Map;

import org.springaicommunity.agentcore.annotation.AgentCoreInvocation;
import org.springaicommunity.agentcore.context.AgentCoreContext;
import org.springaicommunity.agentcore.identity.core.AgentCoreIdentityTemplate;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class IdentityChatService {

	private final AgentCoreIdentityTemplate identityTemplate;

	private final String resourceName;

	public IdentityChatService(AgentCoreIdentityTemplate identityTemplate,
			@Value("${app.resource-credential-provider-name}") String resourceName) {
		this.identityTemplate = identityTemplate;
		this.resourceName = resourceName;
	}

	@AgentCoreInvocation
	public String chat(Map<String, String> request, AgentCoreContext context) {
		identityTemplate.getApiKey(resourceName);
		return "successfully retrieved api key";
	}

}
