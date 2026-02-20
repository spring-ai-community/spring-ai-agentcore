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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springaicommunity.agentcore.artifacts.GeneratedFile;
import software.amazon.awssdk.services.bedrockagentcore.BedrockAgentCoreAsyncClient;
import software.amazon.awssdk.services.bedrockagentcore.BedrockAgentCoreClient;
import software.amazon.awssdk.services.bedrockagentcore.model.*;

/**
 * Low-level client for AgentCore Code Interpreter. Manages sessions and executes code.
 * <p>
 * Note: SDK clients (sync and async) are Spring-managed beans and their lifecycle is
 * handled by Spring, not this class.
 *
 * @author Yuriy Bezsonov
 */
public class AgentCoreCodeInterpreterClient {

	private static final Logger logger = LoggerFactory.getLogger(AgentCoreCodeInterpreterClient.class);

	/** File extensions to retrieve from session (user-generated files). */
	private static final Set<String> RETRIEVABLE_EXTENSIONS = Set.of(".png", ".jpg", ".jpeg", ".gif", ".pdf", ".csv",
			".xlsx", ".xls", ".json", ".txt", ".html");

	/** Paths to exclude from file listing (system directories). */
	private static final Set<String> EXCLUDED_PATHS = Set.of("__pycache__", ".cache", ".local", ".config", ".ipython");

	private final BedrockAgentCoreClient syncClient;

	private final BedrockAgentCoreAsyncClient asyncClient;

	private final String codeInterpreterIdentifier;

	private final int sessionTimeoutSeconds;

	private final int asyncTimeoutSeconds;

	public AgentCoreCodeInterpreterClient(BedrockAgentCoreClient syncClient, BedrockAgentCoreAsyncClient asyncClient,
			AgentCoreCodeInterpreterConfiguration config) {
		this.syncClient = syncClient;
		this.asyncClient = asyncClient;
		this.codeInterpreterIdentifier = config.codeInterpreterIdentifier();
		this.sessionTimeoutSeconds = config.sessionTimeoutSeconds();
		this.asyncTimeoutSeconds = config.asyncTimeoutSeconds();
		logger.debug("AgentCoreCodeInterpreterClient initialized: identifier={}, sessionTimeout={}s, asyncTimeout={}s",
				this.codeInterpreterIdentifier, this.sessionTimeoutSeconds, this.asyncTimeoutSeconds);
	}

	/**
	 * Start a new Code Interpreter session.
	 * @param sessionName unique name for the session
	 * @return session ID
	 */
	public String startSession(String sessionName) {
		logger.debug("Starting session: {}", sessionName);
		String sessionId = this.syncClient
			.startCodeInterpreterSession(StartCodeInterpreterSessionRequest.builder()
				.codeInterpreterIdentifier(this.codeInterpreterIdentifier)
				.name(sessionName)
				.sessionTimeoutSeconds(this.sessionTimeoutSeconds)
				.build())
			.sessionId();
		logger.debug("Started session: {} -> {}", sessionName, sessionId);
		return sessionId;
	}

	/**
	 * Stop a Code Interpreter session.
	 * @param sessionId the session ID to stop
	 */
	public void stopSession(String sessionId) {
		logger.debug("Stopping session: {}", sessionId);
		try {
			this.syncClient.stopCodeInterpreterSession(StopCodeInterpreterSessionRequest.builder()
				.codeInterpreterIdentifier(this.codeInterpreterIdentifier)
				.sessionId(sessionId)
				.build());
			logger.debug("Stopped session: {}", sessionId);
		}
		catch (Exception e) {
			logger.warn("Failed to stop session {}: {}", sessionId, e.getMessage());
		}
	}

	/**
	 * Execute code in a session.
	 * @param sessionId the session ID
	 * @param language programming language (python, javascript, typescript)
	 * @param code code to execute
	 * @return execution result with text output and generated files
	 */
	public CodeExecutionResult executeCode(String sessionId, String language, String code) {
		logger.debug("Executing code: language={}, {} chars", language, code.length());

		StringBuffer textOutput = new StringBuffer();
		List<GeneratedFile> files = Collections.synchronizedList(new ArrayList<>());
		AtomicBoolean isError = new AtomicBoolean(false);
		AtomicReference<String> errorMessage = new AtomicReference<>();

		try {
			CompletableFuture<Void> future = this.asyncClient.invokeCodeInterpreter(
					InvokeCodeInterpreterRequest.builder()
						.codeInterpreterIdentifier(this.codeInterpreterIdentifier)
						.sessionId(sessionId)
						.name("executeCode")
						.arguments(ToolArguments.builder().language(language).code(code).build())
						.build(),
					InvokeCodeInterpreterResponseHandler.builder()
						.onEventStream(publisher -> publisher
							.subscribe(event -> event.accept(InvokeCodeInterpreterResponseHandler.Visitor.builder()
								.onResult(result -> processResult(result, isError, textOutput, files))
								.onDefault(defaultEvent -> logger.trace("Default event: {}", defaultEvent))
								.build())))
						.onError(error -> {
							logger.error("Stream error", error);
							errorMessage.set(error.getMessage());
						})
						.build());

			future.get(this.asyncTimeoutSeconds, TimeUnit.SECONDS);
		}
		catch (Exception e) {
			logger.error("Code execution failed", e);
			isError.set(true);
			textOutput.append("Execution error: ").append(e.getMessage());
		}

		if (errorMessage.get() != null) {
			isError.set(true);
			if (textOutput.isEmpty()) {
				textOutput.append("Stream error: ").append(errorMessage.get());
			}
		}

		String output = textOutput.toString();
		logger.debug("Execution result: {} chars text, {} files, isError={}", output.length(), files.size(),
				isError.get());

		return new CodeExecutionResult(output, isError.get(), files);
	}

	private void processResult(CodeInterpreterResult result, AtomicBoolean isError, StringBuffer textOutput,
			List<GeneratedFile> files) {
		if (result.isError() != null && result.isError()) {
			isError.set(true);
		}
		if (result.content() != null) {
			result.content().forEach(content -> processContent(content, textOutput, files));
		}
	}

	private void processContent(software.amazon.awssdk.services.bedrockagentcore.model.ContentBlock content,
			StringBuffer textOutput, List<GeneratedFile> files) {
		// Handle text content
		if (content.text() != null && !content.text().isEmpty()) {
			textOutput.append(content.text());
		}

		// Handle binary content (images, files)
		if (content.data() != null) {
			String mimeType = content.mimeType();
			byte[] data = content.data().asByteArray();
			String name = generateFileName(mimeType, files.size());
			files.add(new GeneratedFile(mimeType, data, name));
			logger.debug("Generated file: {} ({} bytes)", name, data.length);
		}
	}

	private String generateFileName(String mimeType, int index) {
		if (mimeType == null) {
			return "file_" + index;
		}
		String extension = getExtensionForMimeType(mimeType);
		String prefix = mimeType.startsWith("image/") ? "chart_" : "file_";
		return prefix + index + extension;
	}

	private String getExtensionForMimeType(String mimeType) {
		return switch (mimeType) {
			case "image/png" -> ".png";
			case "image/jpeg" -> ".jpg";
			case "image/gif" -> ".gif";
			case "application/pdf" -> ".pdf";
			case "text/csv" -> ".csv";
			case "application/json" -> ".json";
			case "text/plain" -> ".txt";
			case "text/html" -> ".html";
			default -> "";
		};
	}

	/**
	 * Execute code in a new ephemeral session with automatic file retrieval.
	 * <p>
	 * Flow: startSession → executeCode → listFiles → readFiles → stopSession
	 * @param language programming language
	 * @param code code to execute
	 * @return execution result with text output and retrieved files
	 */
	public CodeExecutionResult executeInEphemeralSession(String language, String code) {
		String sessionName = "ephemeral-" + System.currentTimeMillis();
		String sessionId = startSession(sessionName);
		try {
			// Execute code
			CodeExecutionResult execResult = executeCode(sessionId, language, code);

			// Retrieve generated files
			List<GeneratedFile> files = new ArrayList<>(execResult.files());
			List<String> sessionFiles = listFiles(sessionId, "");
			List<String> filesToRetrieve = filterRetrievableFiles(sessionFiles);

			if (!filesToRetrieve.isEmpty()) {
				logger.debug("Retrieving {} files from session", filesToRetrieve.size());
				List<GeneratedFile> retrievedFiles = readFiles(sessionId, filesToRetrieve);
				files.addAll(retrievedFiles);
			}

			return new CodeExecutionResult(execResult.textOutput(), execResult.isError(), files);
		}
		finally {
			stopSession(sessionId);
		}
	}

	/**
	 * List files in a session directory.
	 * @param sessionId the session ID
	 * @param directoryPath directory path (empty string for root)
	 * @return list of file paths
	 */
	public List<String> listFiles(String sessionId, String directoryPath) {
		logger.debug("Listing files in session {}, path: '{}'", sessionId, directoryPath);
		List<String> filePaths = Collections.synchronizedList(new ArrayList<>());

		try {
			CompletableFuture<Void> future = this.asyncClient.invokeCodeInterpreter(
					InvokeCodeInterpreterRequest.builder()
						.codeInterpreterIdentifier(this.codeInterpreterIdentifier)
						.sessionId(sessionId)
						.name("listFiles")
						.arguments(ToolArguments.builder().directoryPath(directoryPath).build())
						.build(),
					InvokeCodeInterpreterResponseHandler.builder()
						.onEventStream(publisher -> publisher.subscribe(event -> event
							.accept(InvokeCodeInterpreterResponseHandler.Visitor.builder().onResult(result -> {
								if (result.content() != null) {
									result.content().forEach(content -> {
										// Handle resource_link type - extract path from
										// uri
										if (content.uri() != null) {
											String path = extractFileNameFromUri(content.uri());
											if (path != null && !path.isEmpty() && !isExcludedPath(path)) {
												filePaths.add(path);
											}
										}
									});
								}
							}).build())))
						.onError(error -> {
							String errorMessage = "listFiles failed: " + error.getMessage();
							logger.error(errorMessage, error);
						})
						.build());

			future.get(this.asyncTimeoutSeconds, TimeUnit.SECONDS);
		}
		catch (Exception e) {
			logger.error("listFiles failed", e);
		}

		logger.debug("Listed {} files in session", filePaths.size());
		return filePaths;
	}

	/**
	 * Extract filename from a URI (handles file:// scheme and plain paths).
	 */
	private String extractFileNameFromUri(String uri) {
		if (uri == null || uri.isEmpty()) {
			return null;
		}
		// Remove file:// prefix if present
		String path = uri.startsWith("file://") ? uri.substring(7) : uri;
		// Extract just the filename
		int lastSlash = path.lastIndexOf('/');
		return lastSlash >= 0 ? path.substring(lastSlash + 1) : path;
	}

	/**
	 * Read files from a session.
	 * @param sessionId the session ID
	 * @param paths list of file paths to read
	 * @return list of generated files with binary content
	 */
	public List<GeneratedFile> readFiles(String sessionId, List<String> paths) {
		logger.debug("Reading {} files from session {}", paths.size(), sessionId);
		List<GeneratedFile> files = Collections.synchronizedList(new ArrayList<>());
		try {
			CompletableFuture<Void> future = this.asyncClient.invokeCodeInterpreter(
					InvokeCodeInterpreterRequest.builder()
						.codeInterpreterIdentifier(this.codeInterpreterIdentifier)
						.sessionId(sessionId)
						.name("readFiles")
						.arguments(ToolArguments.builder().paths(paths).build())
						.build(),
					InvokeCodeInterpreterResponseHandler.builder()
						.onEventStream(publisher -> publisher
							.subscribe(event -> event.accept(InvokeCodeInterpreterResponseHandler.Visitor.builder()
								.onResult(result -> processResult(paths, result, files))
								.build())))
						.onError(error -> {
							String errorMessage = "readFiles failed: " + error.getMessage();
							logger.error(errorMessage, error);
						})
						.build());

			future.get(this.asyncTimeoutSeconds, TimeUnit.SECONDS);
		}
		catch (Exception e) {
			logger.error("readFiles failed", e);
		}

		logger.debug("Read {} files from session", files.size());
		return files;
	}

	private void processResult(List<String> paths, CodeInterpreterResult result, List<GeneratedFile> files) {
		if (result.content() != null) {
			result.content().forEach(content -> processReadFileContent(content, files, paths));
		}
	}

	private boolean isExcludedPath(String path) {
		for (String excluded : EXCLUDED_PATHS) {
			if (path.contains(excluded)) {
				return true;
			}
		}
		return false;
	}

	private List<String> filterRetrievableFiles(List<String> allFiles) {
		List<String> retrievable = new ArrayList<>();
		for (String file : allFiles) {
			String lower = file.toLowerCase();
			for (String ext : RETRIEVABLE_EXTENSIONS) {
				if (lower.endsWith(ext)) {
					retrievable.add(file);
					break;
				}
			}
		}
		logger.debug("Filtered {} retrievable files from {} total", retrievable.size(), allFiles.size());
		return retrievable;
	}

	private void processReadFileContent(software.amazon.awssdk.services.bedrockagentcore.model.ContentBlock content,
			List<GeneratedFile> files, List<String> requestedPaths) {
		// Handle direct binary data
		if (content.data() != null) {
			String mimeType = content.mimeType();
			byte[] data = content.data().asByteArray();
			String name = determineFileName(mimeType, files.size(), requestedPaths);
			String sourcePath = determineSourcePath(files.size(), requestedPaths);
			files.add(CodeInterpreterArtifacts.fromPath(mimeType, data, name, sourcePath));
			logger.debug("Read file (data): {} ({} bytes, {})", name, data.length, mimeType);
		}
		// Handle resource type - binary data is in resource.blob()
		else if (content.resource() != null) {
			var resource = content.resource();
			String mimeType = resource.mimeType() != null ? resource.mimeType() : content.mimeType();
			String sourcePath = resource.uri() != null ? resource.uri()
					: determineSourcePath(files.size(), requestedPaths);
			String name = resource.uri() != null ? extractFileNameFromUri(resource.uri())
					: determineFileName(mimeType, files.size(), requestedPaths);

			if (resource.blob() != null) {
				byte[] data = resource.blob().asByteArray();
				files.add(CodeInterpreterArtifacts.fromPath(mimeType, data, name, sourcePath));
				logger.debug("Read file (resource blob): {} ({} bytes, {})", name, data.length, mimeType);
			}
			else if (resource.text() != null && !resource.text().isEmpty()) {
				// Handle text content (for CSV, TXT, etc.)
				byte[] data = resource.text().getBytes(java.nio.charset.StandardCharsets.UTF_8);
				files.add(CodeInterpreterArtifacts.fromPath(mimeType, data, name, sourcePath));
				logger.debug("Read file (resource text): {} ({} bytes, {})", name, data.length, mimeType);
			}
			else {
				logger.warn("Resource {} has no blob or text content", name);
			}
		}
	}

	private String determineSourcePath(int index, List<String> requestedPaths) {
		if (requestedPaths != null && index < requestedPaths.size()) {
			return requestedPaths.get(index);
		}
		return null;
	}

	private String determineFileName(String mimeType, int index, List<String> requestedPaths) {
		// If we have requested paths and index is within range, use that name
		if (requestedPaths != null && index < requestedPaths.size()) {
			String path = requestedPaths.get(index);
			String name = extractFileNameFromUri(path);
			if (name != null && !name.isEmpty()) {
				return name;
			}
		}
		return generateFileName(mimeType, index);
	}

}
