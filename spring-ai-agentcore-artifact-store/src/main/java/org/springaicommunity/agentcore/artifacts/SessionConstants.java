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

package org.springaicommunity.agentcore.artifacts;

/**
 * Constants for session management in artifact storage.
 *
 * @author Yuriy Bezsonov
 */
public final class SessionConstants {

	private SessionConstants() {
	}

	/**
	 * Reactor context key for session ID. Callers should store session ID under this key
	 * via {@code .contextWrite(ctx -> ctx.put(SESSION_ID_KEY, sessionId))}.
	 */
	public static final String SESSION_ID_KEY = "sessionId";

	/**
	 * Default session ID for single-user environments (AgentCore).
	 */
	public static final String DEFAULT_SESSION_ID = "default";

}
