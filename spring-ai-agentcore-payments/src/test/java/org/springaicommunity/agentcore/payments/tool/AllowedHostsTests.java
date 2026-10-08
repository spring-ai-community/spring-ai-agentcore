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

package org.springaicommunity.agentcore.payments.tool;

import java.net.URI;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * Tests for {@link AllowedHosts}.
 *
 * @author Andrei Shakirin
 */
class AllowedHostsTests {

	private final AllowedHosts hosts = AllowedHosts.of(List.of("API.example.com", "*.merchant.io"));

	@Test
	void matchesHostnamesExactlyIgnoringCaseAndPort() {
		assertThat(this.hosts.allows(URI.create("https://api.example.com/data"))).isTrue();
		assertThat(this.hosts.allows(URI.create("https://Api.Example.COM:8443/data"))).isTrue();
		assertThat(this.hosts.allows(URI.create("https://other.example.com/data"))).isFalse();
		assertThat(this.hosts.allows(URI.create("https://api.example.com.evil.org/data"))).isFalse();
	}

	@Test
	void matchesSubdomainsOnlyWithWildcardEntry() {
		assertThat(this.hosts.allows(URI.create("https://pay.merchant.io/x"))).isTrue();
		assertThat(this.hosts.allows(URI.create("https://a.b.merchant.io/x"))).isTrue();
		assertThat(this.hosts.allows(URI.create("https://merchant.io/x"))).isFalse();
		assertThat(this.hosts.allows(URI.create("https://evilmerchant.io/x"))).isFalse();
	}

	@Test
	void allowsOnlyHttpAndHttps() {
		assertThat(this.hosts.allows(URI.create("ftp://api.example.com/data"))).isFalse();
		assertThat(this.hosts.allows(URI.create("file:///etc/passwd"))).isFalse();
	}

	@Test
	void rejectsEmptyListAndEntriesThatAreNotHostnames() {
		assertThatIllegalArgumentException().isThrownBy(() -> AllowedHosts.of(List.of()));
		assertThatIllegalArgumentException().isThrownBy(() -> AllowedHosts.of(List.of("https://api.example.com")));
		assertThatIllegalArgumentException().isThrownBy(() -> AllowedHosts.of(List.of("api.example.com:443")));
		assertThatIllegalArgumentException().isThrownBy(() -> AllowedHosts.of(List.of("*")));
	}

}
