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

package org.springaicommunity.agentcore.observability.testsupport;

import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizerProvider;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * Routes OTLP span export to an {@link InMemorySpanExporter} for deterministic
 * integration tests.
 */
@TestConfiguration
public class OtelInMemorySpanExporterTestConfig {

	public static final InMemorySpanExporter SPAN_EXPORTER = InMemorySpanExporter.create();

	@Bean
	AutoConfigurationCustomizerProvider otelRouteTracesToInMemory() {
		return customizer -> customizer.addSpanExporterCustomizer((delegate, unused) -> SPAN_EXPORTER);
	}

}
