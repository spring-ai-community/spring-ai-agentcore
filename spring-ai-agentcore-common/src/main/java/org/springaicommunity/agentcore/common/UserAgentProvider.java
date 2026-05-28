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

package org.springaicommunity.agentcore.common;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Configures the AWS SDK {@code sdk.ua.appId} system property so that all SDK clients
 * automatically include the spring-ai-agentcore identifier in the User-Agent header.
 * Preserves any user-provided value by appending rather than replacing.
 *
 * @author Matt Meckes
 */
public final class UserAgentProvider {

	private static final Logger logger = LoggerFactory.getLogger(UserAgentProvider.class);

	private static final String SDK_USER_AGENT_APP_ID = "sdk.ua.appId";

	private static final String APP_ID;

	static {
		String version = "unknown";
		try (InputStream is = UserAgentProvider.class.getResourceAsStream("/version.properties")) {
			if (is != null) {
				Properties props = new Properties();
				props.load(is);
				version = props.getProperty("version", "unknown");
			}
		}
		catch (IOException ex) {
			logger.warn("Failed to load version.properties", ex);
		}
		APP_ID = "spring-ai-agentcore/" + version;
	}

	private UserAgentProvider() {
	}

	/**
	 * Sets the {@code sdk.ua.appId} system property, appending to any existing
	 * user-provided value. Safe to call multiple times; subsequent calls are no-ops if
	 * the value already contains the spring-ai-agentcore identifier.
	 */
	public static void configure() {
		try {
			String existing = System.getProperty(SDK_USER_AGENT_APP_ID);
			if (existing != null && existing.contains(APP_ID)) {
				return;
			}
			String newValue = (existing == null || existing.isEmpty()) ? APP_ID : existing + "/" + APP_ID;
			System.setProperty(SDK_USER_AGENT_APP_ID, newValue);
			logger.debug("Set {} = {}", SDK_USER_AGENT_APP_ID, newValue);
		}
		catch (Exception ex) {
			logger.warn("Unable to configure user agent system property", ex);
		}
	}

	public static String appId() {
		return APP_ID;
	}

}
