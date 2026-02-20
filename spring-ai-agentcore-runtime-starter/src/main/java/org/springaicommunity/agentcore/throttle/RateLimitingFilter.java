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

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

public class RateLimitingFilter implements Filter {

	private static final String DEFAULT_CLIENT_ID = "default";

	private static final String X_FORWARDED_FOR_HEADER = "X-Forwarded-For";

	private static final String ERROR_RESPONSE = """
			{"error":"Rate limit exceeded"}""";

	private static final String UTF_8 = "UTF-8";

	private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();

	private final Map<String, Integer> pathLimits;

	public RateLimitingFilter(int invocationsLimit, int pingLimit) {
		this.pathLimits = Map.of(ThrottleConfiguration.INVOCATIONS_PATH, invocationsLimit,
				ThrottleConfiguration.PING_PATH, pingLimit);
	}

	@Override
	public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
			throws IOException, ServletException {
		var httpRequest = (HttpServletRequest) request;
		var httpResponse = (HttpServletResponse) response;

		var path = httpRequest.getRequestURI();
		if (path == null || !shouldApplyRateLimit(path)) {
			chain.doFilter(request, response);
			return;
		}

		var bucket = getBucket(getClientId(httpRequest), path);
		if (bucket.tryConsume(1)) {
			chain.doFilter(request, response);
		}

		else {
			httpResponse.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
			httpResponse.setContentType(MediaType.APPLICATION_JSON_VALUE);
			httpResponse.setCharacterEncoding(UTF_8);
			httpResponse.getWriter().write(ERROR_RESPONSE);
		}
	}

	private boolean shouldApplyRateLimit(String path) {
		var limit = pathLimits.get(path);
		return limit != null && limit > 0;
	}

	private String getClientId(HttpServletRequest request) {
		var forwardedFor = request.getHeader(X_FORWARDED_FOR_HEADER);
		if (forwardedFor != null && !forwardedFor.isEmpty()) {
			return forwardedFor.split(",")[0].trim();
		}
		var remoteAddr = request.getRemoteAddr();
		return remoteAddr != null ? remoteAddr : DEFAULT_CLIENT_ID;
	}

	private Bucket getBucket(String clientId, String path) {
		var key = clientId + ':' + path;
		return buckets.computeIfAbsent(key, k -> createBucket(path));
	}

	private Bucket createBucket(String path) {
		var limit = pathLimits.get(path);
		var bandwidth = Bandwidth.builder().capacity(limit).refillIntervally(limit, Duration.ofMinutes(1)).build();
		return Bucket.builder().addLimit(bandwidth).build();
	}

}
