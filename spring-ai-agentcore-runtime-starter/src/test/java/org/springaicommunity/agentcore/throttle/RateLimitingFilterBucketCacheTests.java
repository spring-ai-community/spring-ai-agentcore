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

package org.springaicommunity.agentcore.throttle;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Duration;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;

class RateLimitingFilterBucketCacheTests {

	/**
	 * Runs {@link com.github.benmanes.caffeine.cache.Cache#cleanUp()} before size; only
	 * for test assertions.
	 */
	private static long bucketCountAfterMaintenance(RateLimitingFilter filter) {
		filter.buckets.cleanUp();
		return filter.buckets.estimatedSize();
	}

	@Test
	void shouldNotGrowBeyondMaxBuckets() throws Exception {
		RateLimitingFilter filter = new RateLimitingFilter(1_000, 1_000, 3L, Duration.ofHours(24));
		FilterChain chain = mock(FilterChain.class);
		HttpServletResponse response = mock(HttpServletResponse.class);
		StringWriter body = new StringWriter();
		given(response.getWriter()).willReturn(new PrintWriter(body));

		for (int i = 0; i < 4; i++) {
			HttpServletRequest request = mock(HttpServletRequest.class);
			given(request.getRequestURI()).willReturn(ThrottleConfiguration.INVOCATIONS_PATH);
			given(request.getHeader("X-Forwarded-For")).willReturn("10.0.1." + i);
			filter.doFilter(request, response, chain);
		}

		assertThat(bucketCountAfterMaintenance(filter)).isEqualTo(3);
	}

	@Test
	void shouldEvictIdleBucketsAfterExpiry() throws Exception {
		RateLimitingFilter filter = new RateLimitingFilter(100, 100, 1_000L, Duration.ofMillis(100));
		FilterChain chain = mock(FilterChain.class);
		HttpServletResponse response = mock(HttpServletResponse.class);
		StringWriter body = new StringWriter();
		given(response.getWriter()).willReturn(new PrintWriter(body));

		HttpServletRequest request = mock(HttpServletRequest.class);
		given(request.getRequestURI()).willReturn(ThrottleConfiguration.INVOCATIONS_PATH);
		given(request.getHeader("X-Forwarded-For")).willReturn("192.168.0.1");

		filter.doFilter(request, response, chain);
		assertThat(bucketCountAfterMaintenance(filter)).isEqualTo(1);

		Thread.sleep(250L);
		assertThat(bucketCountAfterMaintenance(filter)).isEqualTo(0);
	}

	@Test
	void shouldReuseBucketForSameClient() throws Exception {
		RateLimitingFilter filter = new RateLimitingFilter(1_000, 1_000, 100L, Duration.ofHours(24));
		FilterChain chain = mock(FilterChain.class);
		HttpServletResponse response = mock(HttpServletResponse.class);
		StringWriter body = new StringWriter();
		given(response.getWriter()).willReturn(new PrintWriter(body));

		HttpServletRequest request = mock(HttpServletRequest.class);
		given(request.getRequestURI()).willReturn(ThrottleConfiguration.INVOCATIONS_PATH);
		given(request.getHeader("X-Forwarded-For")).willReturn("10.0.1.1");

		filter.doFilter(request, response, chain);
		filter.doFilter(request, response, chain);
		filter.doFilter(request, response, chain);

		assertThat(bucketCountAfterMaintenance(filter)).isEqualTo(1);
	}

	@Test
	void shouldUseDefaultCacheWithTwoArgConstructor() throws Exception {
		RateLimitingFilter filter = new RateLimitingFilter(1_000, 1_000);
		FilterChain chain = mock(FilterChain.class);
		HttpServletResponse response = mock(HttpServletResponse.class);
		StringWriter body = new StringWriter();
		given(response.getWriter()).willReturn(new PrintWriter(body));

		HttpServletRequest request = mock(HttpServletRequest.class);
		given(request.getRequestURI()).willReturn(ThrottleConfiguration.INVOCATIONS_PATH);
		given(request.getHeader("X-Forwarded-For")).willReturn("10.0.2.2");

		filter.doFilter(request, response, chain);
		filter.doFilter(request, response, chain);

		assertThat(bucketCountAfterMaintenance(filter)).isEqualTo(1);
	}

	@Test
	void shouldNotEvictBucketWhileClientKeepsAccessing() throws Exception {
		RateLimitingFilter filter = new RateLimitingFilter(100, 100, 1_000L, Duration.ofMillis(100));
		FilterChain chain = mock(FilterChain.class);
		HttpServletResponse response = mock(HttpServletResponse.class);
		StringWriter body = new StringWriter();
		given(response.getWriter()).willReturn(new PrintWriter(body));

		HttpServletRequest request = mock(HttpServletRequest.class);
		given(request.getRequestURI()).willReturn(ThrottleConfiguration.INVOCATIONS_PATH);
		given(request.getHeader("X-Forwarded-For")).willReturn("192.168.0.3");

		filter.doFilter(request, response, chain);
		Thread.sleep(80L);
		filter.doFilter(request, response, chain);
		Thread.sleep(80L);
		filter.doFilter(request, response, chain);

		assertThat(bucketCountAfterMaintenance(filter)).isEqualTo(1);
	}

	@Test
	void shouldRestoreFullCapacityAfterIdleEviction() throws Exception {
		RateLimitingFilter filter = new RateLimitingFilter(2, 2, 1_000L, Duration.ofMillis(100));
		FilterChain chain = mock(FilterChain.class);
		HttpServletResponse response = mock(HttpServletResponse.class);
		StringWriter body = new StringWriter();
		given(response.getWriter()).willReturn(new PrintWriter(body));

		HttpServletRequest request = mock(HttpServletRequest.class);
		given(request.getRequestURI()).willReturn(ThrottleConfiguration.INVOCATIONS_PATH);
		given(request.getHeader("X-Forwarded-For")).willReturn("192.168.0.4");

		filter.doFilter(request, response, chain);
		filter.doFilter(request, response, chain);
		filter.doFilter(request, response, chain);

		then(chain).should(times(2)).doFilter(any(ServletRequest.class), any(ServletResponse.class));

		Thread.sleep(250L);

		filter.doFilter(request, response, chain);

		then(chain).should(times(3)).doFilter(any(ServletRequest.class), any(ServletResponse.class));
	}

}
