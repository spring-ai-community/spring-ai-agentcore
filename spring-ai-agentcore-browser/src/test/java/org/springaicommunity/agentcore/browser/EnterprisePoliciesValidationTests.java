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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for enterprise policy configuration validation at binding time.
 */
class EnterprisePoliciesValidationTests {

	@Test
	@DisplayName("Should reject blank S3 bucket")
	void shouldRejectBlankBucket() {
		assertThatThrownBy(() -> new AgentCoreBrowserConfiguration.S3Ref(" ", "policies/block.json", null))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("bucket is required");
	}

	@Test
	@DisplayName("Should reject blank S3 prefix")
	void shouldRejectBlankPrefix() {
		assertThatThrownBy(() -> new AgentCoreBrowserConfiguration.S3Ref("my-bucket", "", null))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("prefix is required");
	}

	@Test
	@DisplayName("Should reject unknown enterprise policy type")
	void shouldRejectUnknownPolicyType() {
		AgentCoreBrowserConfiguration.S3Ref s3 = new AgentCoreBrowserConfiguration.S3Ref("my-bucket",
				"policies/block.json", null);
		assertThatThrownBy(() -> new AgentCoreBrowserConfiguration.EnterprisePolicyRef(s3, "NOT_A_POLICY_TYPE"))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("must be one of");
	}

	@Test
	@DisplayName("Should reject typo in enterprise policy type")
	void shouldRejectTypoInPolicyType() {
		AgentCoreBrowserConfiguration.S3Ref s3 = new AgentCoreBrowserConfiguration.S3Ref("my-bucket",
				"policies/block.json", null);
		assertThatThrownBy(() -> new AgentCoreBrowserConfiguration.EnterprisePolicyRef(s3, "RECOMMENED"))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("must be one of");
	}

	@Test
	@DisplayName("Should reject MANAGED policy type for session-scoped configuration")
	void shouldRejectManagedPolicyType() {
		AgentCoreBrowserConfiguration.S3Ref s3 = new AgentCoreBrowserConfiguration.S3Ref("my-bucket",
				"policies/block.json", null);
		assertThatThrownBy(() -> new AgentCoreBrowserConfiguration.EnterprisePolicyRef(s3, "MANAGED"))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("RECOMMENDED");
	}

}
