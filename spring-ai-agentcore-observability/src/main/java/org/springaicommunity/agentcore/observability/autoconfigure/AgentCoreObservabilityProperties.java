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

package org.springaicommunity.agentcore.observability.autoconfigure;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for AgentCore OpenTelemetry enrichment.
 *
 * <p>
 * The tighter-scope observability module only adds AgentCore-aware behavior on top of
 * what Spring AI Observability already emits. Content capture and PII masking are handled
 * upstream or by the OTel pipeline (e.g. CloudWatch managed data identifiers). This type
 * remains intentionally small so future AgentCore-scoped toggles can be added without
 * changing the external configuration contract.
 *
 * @author Vaquar Khan
 */
@ConfigurationProperties(prefix = "spring.ai.agentcore.observability")
public class AgentCoreObservabilityProperties {

	/**
	 * Whether to emit the AgentCore-aware span enrichment aspect. Default {@code true}.
	 */
	private boolean enabled = true;

	public boolean isEnabled() {
		return this.enabled;
	}

	public void setEnabled(boolean enabled) {
		this.enabled = enabled;
	}

}
