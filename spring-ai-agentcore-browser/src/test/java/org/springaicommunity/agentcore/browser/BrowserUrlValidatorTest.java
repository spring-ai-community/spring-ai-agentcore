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

package org.springaicommunity.agentcore.browser;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link BrowserUrlValidator}.
 */
class BrowserUrlValidatorTest {

	private final BrowserUrlValidator validator = new BrowserUrlValidator();

	@Nested
	@DisplayName("Valid public URLs")
	class ValidUrls {

		@ParameterizedTest
		@ValueSource(strings = { "https://example.com", "http://example.org/path", "https://www.google.com/search",
				"https://docs.spring.io/spring-ai/reference/" })
		void shouldAllowPublicHttpUrls(String url) {
			assertThatCode(() -> validator.validate(url)).doesNotThrowAnyException();
		}

	}

	@Nested
	@DisplayName("Null and empty URLs")
	class NullAndEmpty {

		@Test
		void shouldRejectNullUrl() {
			assertThatThrownBy(() -> validator.validate(null)).isInstanceOf(BrowserOperationException.class)
				.hasMessageContaining("null or empty");
		}

		@Test
		void shouldRejectEmptyUrl() {
			assertThatThrownBy(() -> validator.validate("")).isInstanceOf(BrowserOperationException.class)
				.hasMessageContaining("null or empty");
		}

		@Test
		void shouldRejectBlankUrl() {
			assertThatThrownBy(() -> validator.validate("   ")).isInstanceOf(BrowserOperationException.class)
				.hasMessageContaining("null or empty");
		}

	}

	@Nested
	@DisplayName("Blocked schemes")
	class BlockedSchemes {

		@ParameterizedTest
		@ValueSource(strings = { "file:///etc/passwd", "ftp://files.example.com", "javascript:alert('xss')",
				"data:text/html,<h1>hi</h1>" })
		void shouldBlockNonHttpSchemes(String url) {
			assertThatThrownBy(() -> validator.validate(url)).isInstanceOf(BrowserOperationException.class)
				.hasMessageContaining("Blocked URL scheme");
		}

	}

	@Nested
	@DisplayName("AWS metadata endpoints")
	class AwsMetadata {

		@ParameterizedTest
		@ValueSource(strings = { "http://169.254.169.254/latest/meta-data/",
				"http://169.254.169.254/latest/meta-data/iam/security-credentials/",
				"http://169.254.169.254/latest/user-data", "http://169.254.170.2/v2/credentials" })
		void shouldBlockAwsMetadataEndpoints(String url) {
			assertThatThrownBy(() -> validator.validate(url)).isInstanceOf(BrowserOperationException.class)
				.hasMessageContaining("internal/metadata");
		}

	}

	@Nested
	@DisplayName("Localhost URLs")
	class Localhost {

		@ParameterizedTest
		@ValueSource(strings = { "http://localhost:8080/actuator/env", "http://localhost/admin", "http://localhost",
				"http://127.0.0.1:8080/", "http://127.0.0.1", "http://[::1]:8080/", "http://0.0.0.0:8080/" })
		void shouldBlockLocalhostUrls(String url) {
			assertThatThrownBy(() -> validator.validate(url)).isInstanceOf(BrowserOperationException.class)
				.hasMessageContaining("internal/metadata");
		}

	}

	@Nested
	@DisplayName("Private network URLs")
	class PrivateNetworks {

		@ParameterizedTest
		@ValueSource(strings = { "http://10.0.1.50:8080/admin", "http://10.255.255.255/", "http://172.16.0.1:3000/",
				"http://172.31.255.255/", "http://192.168.1.1:8080/", "http://192.168.0.100/" })
		void shouldBlockPrivateNetworkUrls(String url) {
			assertThatThrownBy(() -> validator.validate(url)).isInstanceOf(BrowserOperationException.class)
				.hasMessageContaining("internal/metadata");
		}

	}

	@Nested
	@DisplayName("GCP and Azure metadata endpoints")
	class CloudMetadata {

		@ParameterizedTest
		@ValueSource(strings = { "http://metadata.google.internal/computeMetadata/v1/",
				"http://100.100.100.200/latest/meta-data/" })
		void shouldBlockCloudMetadataEndpoints(String url) {
			assertThatThrownBy(() -> validator.validate(url)).isInstanceOf(BrowserOperationException.class)
				.hasMessageContaining("internal/metadata");
		}

	}

	@Nested
	@DisplayName("Custom allowlist")
	class CustomAllowlist {

		private final BrowserUrlValidator allowlistValidator = new BrowserUrlValidator(
				List.of("https://*.example.com*", "https://docs.spring.io/*"), null);

		@Test
		void shouldAllowMatchingUrl() {
			assertThatCode(() -> allowlistValidator.validate("https://www.example.com/page"))
				.doesNotThrowAnyException();
		}

		@Test
		void shouldAllowSecondPattern() {
			assertThatCode(() -> allowlistValidator.validate("https://docs.spring.io/spring-ai/"))
				.doesNotThrowAnyException();
		}

		@Test
		void shouldBlockUrlNotInAllowlist() {
			assertThatThrownBy(() -> allowlistValidator.validate("https://evil.com/steal"))
				.isInstanceOf(BrowserOperationException.class)
				.hasMessageContaining("not in allowlist");
		}

	}

	@Nested
	@DisplayName("Custom blocklist")
	class CustomBlocklist {

		private final BrowserUrlValidator blocklistValidator = new BrowserUrlValidator(null,
				List.of("https://*.internal.corp*"));

		@Test
		void shouldBlockCustomPattern() {
			assertThatThrownBy(() -> blocklistValidator.validate("https://api.internal.corp/secrets"))
				.isInstanceOf(BrowserOperationException.class)
				.hasMessageContaining("custom policy");
		}

		@Test
		void shouldAllowNonMatchingUrl() {
			assertThatCode(() -> blocklistValidator.validate("https://example.com/page")).doesNotThrowAnyException();
		}

	}

}
