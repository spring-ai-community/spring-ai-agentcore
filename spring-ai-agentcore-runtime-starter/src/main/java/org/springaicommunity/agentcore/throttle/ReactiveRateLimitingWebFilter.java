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

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import reactor.core.publisher.Mono;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;

/**
 * Reactive web filter that applies per-client rate limits.
 *
 * @author Matej Nedic
 */
public class ReactiveRateLimitingWebFilter implements WebFilter {

	private static final String DEFAULT_CLIENT_ID = "default";

	private static final String X_FORWARDED_FOR_HEADER = "X-Forwarded-For";

	private static final byte[] ERROR_RESPONSE = """
			{"error":"Rate limit exceeded"}""".getBytes(StandardCharsets.UTF_8);

	private static final long DEFAULT_MAX_BUCKETS = 10_000;

	private static final Duration DEFAULT_BUCKET_EXPIRY = Duration.ofMinutes(5);

	final Cache<String, Bucket> buckets;

	private final Map<String, Integer> pathLimits;

	public ReactiveRateLimitingWebFilter(int invocationsLimit, int pingLimit) {
		this(invocationsLimit, pingLimit, DEFAULT_MAX_BUCKETS, DEFAULT_BUCKET_EXPIRY);
	}

	ReactiveRateLimitingWebFilter(int invocationsLimit, int pingLimit, long maxBuckets, Duration bucketExpiry) {
		this.pathLimits = Map.of(ThrottleConfiguration.INVOCATIONS_PATH, invocationsLimit,
				ThrottleConfiguration.PING_PATH, pingLimit);
		this.buckets = Caffeine.newBuilder().maximumSize(maxBuckets).expireAfterAccess(bucketExpiry).build();
	}

	@Override
	public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
		ServerHttpRequest request = exchange.getRequest();
		String path = request.getPath().value();
		if (!this.shouldApplyRateLimit(path)) {
			return chain.filter(exchange);
		}

		Bucket bucket = this.getBucket(this.getClientId(request), path);
		if (bucket.tryConsume(1)) {
			return chain.filter(exchange);
		}

		ServerHttpResponse response = exchange.getResponse();
		response.setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
		response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
		return response.writeWith(Mono.just(response.bufferFactory().wrap(ERROR_RESPONSE)));
	}

	private boolean shouldApplyRateLimit(String path) {
		Integer limit = this.pathLimits.get(path);
		return limit != null && limit > 0;
	}

	private String getClientId(ServerHttpRequest request) {
		String forwardedFor = request.getHeaders().getFirst(X_FORWARDED_FOR_HEADER);
		if (forwardedFor != null && !forwardedFor.isEmpty()) {
			return forwardedFor.split(",")[0].trim();
		}
		InetSocketAddress remoteAddress = request.getRemoteAddress();
		return (remoteAddress != null) ? remoteAddress.getHostString() : DEFAULT_CLIENT_ID;
	}

	private Bucket getBucket(String clientId, String path) {
		String key = clientId + ':' + path;
		return this.buckets.get(key, (ignored) -> this.createBucket(path));
	}

	private Bucket createBucket(String path) {
		int limit = this.pathLimits.get(path);
		Bandwidth bandwidth = Bandwidth.builder()
			.capacity(limit)
			.refillIntervally(limit, Duration.ofMinutes(1))
			.build();
		return Bucket.builder().addLimit(bandwidth).build();
	}

}
