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

import jakarta.servlet.Filter;

import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.server.WebFilter;

@Configuration
@ConfigurationProperties(prefix = "agentcore.throttle")
public class ThrottleConfiguration {

	/** path of the invocations endpoint that is rate limited. */
	public static final String INVOCATIONS_PATH = "/invocations";

	/** path of the ping endpoint that is rate limited. */
	public static final String PING_PATH = "/ping";

	private int invocationsLimit;

	private int pingLimit;

	public int getInvocationsLimit() {
		return this.invocationsLimit;
	}

	public void setInvocationsLimit(int invocationsLimit) {
		this.invocationsLimit = invocationsLimit;
	}

	public int getPingLimit() {
		return this.pingLimit;
	}

	public void setPingLimit(int pingLimit) {
		this.pingLimit = pingLimit;
	}

	@Configuration
	@ConditionalOnClass(Filter.class)
	@ConditionalOnWebApplication(type = Type.SERVLET)
	static class ServletRateLimitingConfiguration {

		@Bean
		FilterRegistrationBean<RateLimitingFilter> rateLimitingFilter(ThrottleConfiguration properties) {
			FilterRegistrationBean<RateLimitingFilter> registrationBean = new FilterRegistrationBean<>();
			registrationBean
				.setFilter(new RateLimitingFilter(properties.getInvocationsLimit(), properties.getPingLimit()));
			registrationBean.addUrlPatterns(INVOCATIONS_PATH, PING_PATH);
			registrationBean.setOrder(1);
			return registrationBean;
		}

	}

	@Configuration
	@ConditionalOnClass(WebFilter.class)
	@ConditionalOnWebApplication(type = Type.REACTIVE)
	static class ReactiveRateLimitingConfiguration {

		@Bean
		ReactiveRateLimitingWebFilter reactiveRateLimitingWebFilter(ThrottleConfiguration properties) {
			return new ReactiveRateLimitingWebFilter(properties.getInvocationsLimit(), properties.getPingLimit());
		}

	}

}
