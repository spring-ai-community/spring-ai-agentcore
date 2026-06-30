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

package org.springaicommunity.agentcore.codeinterpreter;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

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
 * @param fileRetrievalPolicy which files an ephemeral execution returns (default
 * {@link FileRetrievalPolicy#GENERATED})
 * @param retrievableExtensions file extensions treated as retrievable output under the
 * {@link FileRetrievalPolicy#GENERATED} policy (default
 * {@link #DEFAULT_RETRIEVABLE_EXTENSIONS}); entries are normalized to lowercase with a
 * leading dot
 * @author Yuriy Bezsonov
 */
@ConfigurationProperties(prefix = "agentcore.code-interpreter")
public record AgentCoreCodeInterpreterConfiguration(Integer sessionTimeoutSeconds, String codeInterpreterIdentifier,
		Integer fileStoreTtlSeconds, Integer asyncTimeoutSeconds, Integer artifactStoreMaxSize, String toolDescription,
		FileRetrievalPolicy fileRetrievalPolicy, Set<String> retrievableExtensions) {

	/** Default retrievable output extensions for the {@code GENERATED} policy. */
	public static final Set<String> DEFAULT_RETRIEVABLE_EXTENSIONS = Set.of(".png", ".jpg", ".jpeg", ".gif", ".pdf",
			".csv", ".xlsx", ".xls", ".json", ".txt", ".html");

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
		if (fileRetrievalPolicy == null) {
			fileRetrievalPolicy = FileRetrievalPolicy.GENERATED;
		}
		if (retrievableExtensions == null || retrievableExtensions.isEmpty()) {
			retrievableExtensions = DEFAULT_RETRIEVABLE_EXTENSIONS;
		}
		else {
			retrievableExtensions = normalizeExtensions(retrievableExtensions);
		}
	}

	private static Set<String> normalizeExtensions(Set<String> extensions) {
		Set<String> normalized = new LinkedHashSet<>();
		for (String ext : extensions) {
			if (ext == null || ext.isBlank()) {
				continue;
			}
			String e = ext.trim().toLowerCase(Locale.ROOT);
			normalized.add(e.startsWith(".") ? e : "." + e);
		}
		return normalized.isEmpty() ? DEFAULT_RETRIEVABLE_EXTENSIONS : Set.copyOf(normalized);
	}

}
