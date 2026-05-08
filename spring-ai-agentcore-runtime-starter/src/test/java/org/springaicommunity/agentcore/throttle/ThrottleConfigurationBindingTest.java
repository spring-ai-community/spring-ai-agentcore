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

package org.springaicommunity.agentcore.throttle;

import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.Test;

import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySource;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end wire validation: {@code agentcore.throttle.*} YAML →
 * {@link ThrottleConfiguration} bean → {@link RateLimitingFilter} constructor argument →
 * Caffeine {@code maximumSize}.
 */
class ThrottleConfigurationBindingTest {

	@Test
	void bindsDefaultsWhenNothingConfigured() {
		ThrottleConfiguration cfg = bind(Map.of());
		assertThat(cfg.getMaxBuckets()).isEqualTo(100_000L);
		assertThat(cfg.getBucketExpiry()).isEqualTo(Duration.ofMinutes(5));
	}

	@Test
	void bindsExplicitMaxBuckets() {
		ThrottleConfiguration cfg = bind(Map.of("agentcore.throttle.max-buckets", "5000"));
		assertThat(cfg.getMaxBuckets()).isEqualTo(5_000L);
	}

	@Test
	void bindsExplicitBucketExpiry() {
		ThrottleConfiguration cfg = bind(Map.of("agentcore.throttle.bucket-expiry", "30s"));
		assertThat(cfg.getBucketExpiry()).isEqualTo(Duration.ofSeconds(30));
	}

	@Test
	void bindsAllFourPropertiesTogether() {
		ThrottleConfiguration cfg = bind(
				Map.of("agentcore.throttle.invocations-limit", "42", "agentcore.throttle.ping-limit", "99",
						"agentcore.throttle.max-buckets", "50000", "agentcore.throttle.bucket-expiry", "PT10M"));
		assertThat(cfg.getInvocationsLimit()).isEqualTo(42);
		assertThat(cfg.getPingLimit()).isEqualTo(99);
		assertThat(cfg.getMaxBuckets()).isEqualTo(50_000L);
		assertThat(cfg.getBucketExpiry()).isEqualTo(Duration.ofMinutes(10));
	}

	@Test
	void constructedFilterHonorsBoundConfig() {
		ThrottleConfiguration cfg = bind(
				Map.of("agentcore.throttle.invocations-limit", "10", "agentcore.throttle.ping-limit", "10",
						"agentcore.throttle.max-buckets", "7", "agentcore.throttle.bucket-expiry", "PT1H"));
		RateLimitingFilter filter = new RateLimitingFilter(cfg.getInvocationsLimit(), cfg.getPingLimit(),
				cfg.getMaxBuckets(), cfg.getBucketExpiry());
		assertThat(filter.buckets.policy().eviction().orElseThrow().getMaximum()).isEqualTo(7L);
	}

	private static ThrottleConfiguration bind(Map<String, String> props) {
		ConfigurationPropertySource src = new MapConfigurationPropertySource(props);
		return new Binder(src).bind("agentcore.throttle", ThrottleConfiguration.class)
			.orElseGet(ThrottleConfiguration::new);
	}

}
