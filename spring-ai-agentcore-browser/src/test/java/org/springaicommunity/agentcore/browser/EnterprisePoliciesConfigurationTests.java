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

package org.springaicommunity.agentcore.browser;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for enterprise policies configuration binding.
 */
@SpringBootTest(properties = { "agentcore.browser.enterprise-policies[0].s3.bucket=corp-browser-policies",
		"agentcore.browser.enterprise-policies[0].s3.prefix=policies/production/recommended.json",
		"agentcore.browser.enterprise-policies[0].type=RECOMMENDED",
		"agentcore.browser.enterprise-policies[1].s3.bucket=corp-browser-policies",
		"agentcore.browser.enterprise-policies[1].s3.prefix=policies/production/allowlist.json",
		"agentcore.browser.enterprise-policies[1].s3.version-id=v1.2.3",
		"agentcore.browser.enterprise-policies[1].type=RECOMMENDED" })
class EnterprisePoliciesConfigurationTests {

	@Autowired
	private AgentCoreBrowserConfiguration config;

	@Test
	@DisplayName("Should bind enterprise policies from application properties")
	void shouldBindEnterprisePolicies() {
		List<AgentCoreBrowserConfiguration.EnterprisePolicyRef> policies = this.config.enterprisePolicies();
		assertThat(policies).hasSize(2);

		AgentCoreBrowserConfiguration.EnterprisePolicyRef first = policies.get(0);
		assertThat(first.s3().bucket()).isEqualTo("corp-browser-policies");
		assertThat(first.s3().prefix()).isEqualTo("policies/production/recommended.json");
		assertThat(first.s3().versionId()).isNull();
		assertThat(first.type()).isEqualTo("RECOMMENDED");

		AgentCoreBrowserConfiguration.EnterprisePolicyRef second = policies.get(1);
		assertThat(second.s3().bucket()).isEqualTo("corp-browser-policies");
		assertThat(second.s3().prefix()).isEqualTo("policies/production/allowlist.json");
		assertThat(second.s3().versionId()).isEqualTo("v1.2.3");
		assertThat(second.type()).isEqualTo("RECOMMENDED");
	}

	@Test
	@DisplayName("Should have default values for other config fields")
	void shouldHaveDefaults() {
		assertThat(this.config.mode()).isEqualTo(AgentCoreBrowserConfiguration.DEFAULT_MODE);
		assertThat(this.config.sessionTimeoutSeconds())
			.isEqualTo(AgentCoreBrowserConfiguration.DEFAULT_SESSION_TIMEOUT_SECONDS);
		assertThat(this.config.browserIdentifier()).isEqualTo(AgentCoreBrowserConfiguration.DEFAULT_BROWSER_IDENTIFIER);
	}

	@Configuration
	@EnableConfigurationProperties(AgentCoreBrowserConfiguration.class)
	static class TestConfig {

	}

}
