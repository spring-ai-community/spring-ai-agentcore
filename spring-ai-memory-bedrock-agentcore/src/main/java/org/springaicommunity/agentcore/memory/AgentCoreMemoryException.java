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

package org.springaicommunity.agentcore.memory;

/**
 * Base exception for AgentCore Memory operations.
 */
public class AgentCoreMemoryException extends RuntimeException {

	public AgentCoreMemoryException(String message) {
		super(message);
	}

	public AgentCoreMemoryException(String message, Throwable cause) {
		super(message, cause);
	}

	/**
	 * Thrown when a memory retrieval operation fails.
	 */
	public static class RetrievalException extends AgentCoreMemoryException {

		public RetrievalException(String message, Throwable cause) {
			super(message, cause);
		}

	}

	/**
	 * Thrown when a memory storage operation fails.
	 */
	public static class StorageException extends AgentCoreMemoryException {

		public StorageException(String message, Throwable cause) {
			super(message, cause);
		}

	}

	/**
	 * Thrown when memory configuration is invalid.
	 */
	public static class ConfigurationException extends AgentCoreMemoryException {

		public ConfigurationException(String message) {
			super(message);
		}

	}

}
