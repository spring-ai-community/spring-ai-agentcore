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

package org.springaicommunity.agentcore.observability.realbedrock;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * JUnit 5 {@code @EnabledIf} condition so live Bedrock tests are skipped in CI or laptops
 * without AWS configuration.
 */
public final class RealBedrockConditions {

	private RealBedrockConditions() {
	}

	/**
	 * @return {@code true} only when {@code RUN_REAL_BEDROCK_TESTS=true} and this machine
	 * has typical AWS credential indicators (so default {@code mvn verify} never calls
	 * Bedrock even if {@code
	 *     ~/.aws/credentials} exists).
	 */
	public static boolean liveAwsConfigured() {
		if (!Boolean.parseBoolean(System.getenv("RUN_REAL_BEDROCK_TESTS"))) {
			return false;
		}
		if (Boolean.parseBoolean(System.getenv("SKIP_REAL_BEDROCK_TESTS"))) {
			return false;
		}
		String key = System.getenv("AWS_ACCESS_KEY_ID");
		if (key != null && !key.isBlank()) {
			return true;
		}
		String token = System.getenv("AWS_SESSION_TOKEN");
		if (token != null && !token.isBlank()) {
			return true;
		}
		String profile = System.getenv("AWS_PROFILE");
		if (profile != null && !profile.isBlank()) {
			return true;
		}
		Path creds = Path.of(System.getProperty("user.home"), ".aws", "credentials");
		return Files.isRegularFile(creds);
	}

}
