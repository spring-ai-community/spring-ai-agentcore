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

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "agentcore.throttle")
public class ThrottleConfiguration {

	public static final String INVOCATIONS_PATH = "/invocations";

	public static final String PING_PATH = "/ping";

	private int invocationsLimit;

	private int pingLimit;

	private long maxBuckets = 100_000L;

	private Duration bucketExpiry = Duration.ofMinutes(5);

	@Bean
	public FilterRegistrationBean<RateLimitingFilter> rateLimitingFilter() {
		FilterRegistrationBean<RateLimitingFilter> registrationBean = new FilterRegistrationBean<>();
		registrationBean.setFilter(new RateLimitingFilter(invocationsLimit, pingLimit, maxBuckets, bucketExpiry));
		registrationBean.addUrlPatterns(INVOCATIONS_PATH, PING_PATH);
		registrationBean.setOrder(1);
		return registrationBean;
	}

	public int getInvocationsLimit() {
		return invocationsLimit;
	}

	public void setInvocationsLimit(int invocationsLimit) {
		this.invocationsLimit = invocationsLimit;
	}

	public int getPingLimit() {
		return pingLimit;
	}

	public void setPingLimit(int pingLimit) {
		this.pingLimit = pingLimit;
	}

	public long getMaxBuckets() {
		return maxBuckets;
	}

	public void setMaxBuckets(long maxBuckets) {
		this.maxBuckets = maxBuckets;
	}

	public Duration getBucketExpiry() {
		return bucketExpiry;
	}

	public void setBucketExpiry(Duration bucketExpiry) {
		this.bucketExpiry = bucketExpiry;
	}

}
