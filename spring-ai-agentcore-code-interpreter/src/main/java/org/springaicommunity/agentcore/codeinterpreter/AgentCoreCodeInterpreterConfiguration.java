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

package org.springaicommunity.agentcore.codeinterpreter;

import org.springaicommunity.agentcore.artifacts.ArtifactStoreFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for AgentCore Code Interpreter.
 *
 * @param sessionTimeoutSeconds session timeout in seconds (default 900)
 * @param codeInterpreterIdentifier identifier for the code interpreter service (default
 * "aws.codeinterpreter.v1")
 * @param fileStoreTtlSeconds TTL for file store cache entries in seconds (default 300)
 * @param asyncTimeoutSeconds timeout for async operations in seconds (default 300)
 * @param artifactStoreMaxSize maximum sessions in artifact store (default 10000)
 * @param toolDescription custom tool description for LLM (optional, uses default if null)
 * @author Yuriy Bezsonov
 */
@ConfigurationProperties(prefix = "agentcore.code-interpreter")
public record AgentCoreCodeInterpreterConfiguration(Integer sessionTimeoutSeconds, String codeInterpreterIdentifier,
		Integer fileStoreTtlSeconds, Integer asyncTimeoutSeconds, Integer artifactStoreMaxSize,
		String toolDescription) {

	public AgentCoreCodeInterpreterConfiguration {
		if (sessionTimeoutSeconds == null || sessionTimeoutSeconds <= 0) {
			sessionTimeoutSeconds = 900;
		}
		if (codeInterpreterIdentifier == null || codeInterpreterIdentifier.isEmpty()) {
			codeInterpreterIdentifier = "aws.codeinterpreter.v1";
		}
		if (fileStoreTtlSeconds == null || fileStoreTtlSeconds <= 0) {
			fileStoreTtlSeconds = 300; // 5 minutes default
		}
		if (asyncTimeoutSeconds == null || asyncTimeoutSeconds <= 0) {
			asyncTimeoutSeconds = 300; // 5 minutes default
		}
		if (artifactStoreMaxSize == null || artifactStoreMaxSize <= 0) {
			artifactStoreMaxSize = ArtifactStoreFactory.DEFAULT_MAX_SIZE;
		}
		// toolDescription can be null - will use DEFAULT_TOOL_DESCRIPTION
	}

}
