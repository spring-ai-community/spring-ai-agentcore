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

package org.springaicommunity.agentcore.context;

/**
 * Constants for well-known AgentCore HTTP headers.
 *
 * <p>
 * This class provides constants for headers commonly used in Amazon Bedrock AgentCore
 * requests, organized by functional groups for easy discovery and usage.
 *
 * @author Andrei Shakirin
 */
public final class AgentCoreHeaders {

	// Core AgentCore Headers
	/** session identifier header. */
	public static final String SESSION_ID = "X-Amzn-Bedrock-AgentCore-Runtime-Session-Id";

	/** user identifier header. */
	public static final String USER_ID = "X-Amzn-Bedrock-AgentCore-Runtime-User-Id";

	/** prefix for caller-supplied custom headers. */
	public static final String CUSTOM_HEADER_PREFIX = "X-Amzn-Bedrock-AgentCore-Runtime-Custom-";

	// Authentication & Authorization
	/** standard HTTP authorization header. */
	public static final String AUTHORIZATION = "Authorization";

	/** workload access token header. */
	public static final String WORKLOAD_ACCESS_TOKEN = "workloadaccesstoken";

	/** runtime-specific workload access token header. */
	public static final String WORKLOAD_ACCESS_TOKEN_RUNTIME = "x-amzn-bedrock-agentcore-runtime-workload-accesstoken";

	/** guest authentication header. */
	public static final String GUEST_AUTH = "x-aws-guest-auth";

	// AWS Infrastructure
	/** AWS request id header. */
	public static final String REQUEST_ID = "x-amzn-requestid";

	/** AWS trace id header. */
	public static final String TRACE_ID = "x-amzn-trace-id";

	/** w3c baggage propagation header. */
	public static final String BAGGAGE = "baggage";

	// Proxy Information
	/** proxy ip header. */
	public static final String PROXY_IP = "x-aws-proxy-ip";

	/** proxy port header. */
	public static final String PROXY_PORT = "x-aws-proxy-port";

	private AgentCoreHeaders() {
		// Prevent instantiation
	}

}
