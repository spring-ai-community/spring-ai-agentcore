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

package org.springaicommunity.example.observability;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Bridges Spring Boot's tracing autoconfiguration to the ADOT Java agent's OpenTelemetry
 * SDK. Without this, Spring Boot's {@code OpenTelemetryAutoConfiguration} creates its own
 * separate {@code SdkOpenTelemetry} bean that the Micrometer Tracing bridge uses; spans
 * created from Spring AI observations (ChatClient, advisor, ChatModel, tool) end up in
 * that separate SDK and never reach X-Ray. By exposing {@link GlobalOpenTelemetry#get()}
 * as the primary {@code OpenTelemetry} bean, the bridge writes to the same SDK the agent
 * uses for AWS SDK auto-instrumentation, so the full span tree appears in the GenAI
 * Observability dashboard.
 *
 * <p>
 * The agent must be attached for this to work: it installs its SDK as
 * {@code GlobalOpenTelemetry} during JVM startup, before this bean is resolved.
 */
@Configuration
class OpenTelemetryConfig {

	@Bean
	OpenTelemetry openTelemetry() {
		return GlobalOpenTelemetry.get();
	}

}
