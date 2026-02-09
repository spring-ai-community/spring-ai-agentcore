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

package org.springaicommunity.agentcore.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springaicommunity.agentcore.exception.AgentCoreInvocationException;
import org.springaicommunity.agentcore.service.AgentCoreMethodInvoker;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class AgentCoreInvocationsController implements AgentCoreInvocationsHandler {

	private final AgentCoreMethodInvoker invoker;

	private final Logger logger = LoggerFactory.getLogger(AgentCoreInvocationsController.class);

	public AgentCoreInvocationsController(AgentCoreMethodInvoker invoker) {
		this.invoker = invoker;
	}

	@PostMapping(value = "/invocations", consumes = MediaType.APPLICATION_JSON_VALUE,
			produces = { MediaType.APPLICATION_JSON_VALUE, MediaType.TEXT_EVENT_STREAM_VALUE,
					MediaType.APPLICATION_OCTET_STREAM_VALUE })
	public Object handleJsonInvocation(@RequestBody Object request, @RequestHeader HttpHeaders headers)
			throws Exception {
		return handleInvocation(request, headers);
	}

	@PostMapping(value = "/invocations", consumes = MediaType.TEXT_PLAIN_VALUE,
			produces = { MediaType.APPLICATION_JSON_VALUE, MediaType.TEXT_EVENT_STREAM_VALUE,
					MediaType.APPLICATION_OCTET_STREAM_VALUE })
	public Object handleTextInvocation(@RequestBody String request, @RequestHeader HttpHeaders headers)
			throws Exception {
		return handleInvocation(request, headers);
	}

	private Object handleInvocation(Object request, HttpHeaders headers) throws Exception {
		try {
			return invoker.invokeAgentMethod(request, headers);
		}
		catch (AgentCoreInvocationException e) {
			logger.error("Error trying to invoke AgentCoreInvocation method: " + e.getMessage(), e);
			throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
		}
	}

}
