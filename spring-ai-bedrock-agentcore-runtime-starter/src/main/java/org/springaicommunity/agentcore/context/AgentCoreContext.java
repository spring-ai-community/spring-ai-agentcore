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

package org.springaicommunity.agentcore.context;

import org.springframework.http.HttpHeaders;

/**
 * Context object containing HTTP headers from AgentCore invocation requests.
 *
 * <p>
 * This class provides read-only access to HTTP headers passed to the AgentCore
 * {@code /invocations} endpoint. It can be injected as a method parameter in
 * {@code @AgentCoreInvocation} methods.
 *
 * <p>
 * Usage example: <pre>{@code
 * &#64;AgentCoreInvocation
 * public String handleRequest(String prompt, AgentCoreContext context) {
 *     String sessionId = context.getHeader(AgentCoreHeaders.SESSION_ID);
 *     HttpHeaders allHeaders = context.getHeaders();
 *     return "Processing for session: " + sessionId;
 * }
 * }</pre>
 */
public class AgentCoreContext {

	private final HttpHeaders headers;

	/**
	 * Creates a new AgentCoreContext with the provided headers.
	 * @param headers the HTTP headers from the request
	 */
	public AgentCoreContext(HttpHeaders headers) {
		this.headers = headers != null ? headers : new HttpHeaders();
	}

	/**
	 * Gets all HTTP headers from the AgentCore request as a read-only view.
	 * @return the HTTP headers (read-only)
	 */
	public HttpHeaders getHeaders() {
		return HttpHeaders.readOnlyHttpHeaders(headers);
	}

	/**
	 * Gets the value of a specific HTTP header from the AgentCore request.
	 * @param headerName the name of the header to retrieve
	 * @return the header value, or {@code null} if the header is not found
	 */
	public String getHeader(String headerName) {
		if (headerName == null) {
			return null;
		}
		return headers.getFirst(headerName);
	}

}
