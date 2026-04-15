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

package org.springaicommunity.agentcore.browser;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Validates URLs before browser navigation to prevent SSRF attacks.
 *
 * <p>
 * Blocks access to cloud metadata endpoints, localhost, private networks, and non-HTTP
 * schemes by default. Supports configurable allowlist and blocklist patterns using glob
 * syntax ({@code *} matches any characters).
 *
 * <p>
 * Following OWASP SSRF Prevention guidelines, all known cloud metadata endpoints are
 * blocked regardless of expected deployment target.
 *
 * @author Spring AI Community
 * @see <a href=
 * "https://cheatsheetseries.owasp.org/cheatsheets/Server_Side_Request_Forgery_Prevention_Cheat_Sheet.html">OWASP
 * SSRF Prevention</a>
 */
public class BrowserUrlValidator {

	private static final Logger logger = LoggerFactory.getLogger(BrowserUrlValidator.class);

	private static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https");

	/**
	 * Default blocked patterns covering cloud metadata, localhost, and private networks.
	 */
	private static final List<String> DEFAULT_BLOCKED_GLOBS = List.of(
			// AWS EC2/ECS instance metadata
			"*://169.254.*",
			// GCP compute metadata
			"*://metadata.google.*",
			// Azure / Alibaba Cloud metadata
			"*://100.100.100.200*",
			// Loopback — localhost
			"*://localhost*", "*://localhost:*",
			// Loopback — IPv4
			"*://127.*",
			// Loopback — IPv6
			"*://[::1]*",
			// Loopback — wildcard bind
			"*://0.0.0.0*",
			// Private network — Class A (10.0.0.0/8)
			"*://10.*",
			// Private network — Class B (172.16.0.0/12)
			"*://172.16.*", "*://172.17.*", "*://172.18.*", "*://172.19.*", "*://172.20.*", "*://172.21.*",
			"*://172.22.*", "*://172.23.*", "*://172.24.*", "*://172.25.*", "*://172.26.*", "*://172.27.*",
			"*://172.28.*", "*://172.29.*", "*://172.30.*", "*://172.31.*",
			// Private network — Class C (192.168.0.0/16)
			"*://192.168.*");

	private final List<Pattern> defaultBlockedPatterns;

	private final List<Pattern> customBlockedPatterns;

	private final List<Pattern> allowedPatterns;

	/**
	 * Create a validator with default SSRF protections and no custom patterns.
	 */
	public BrowserUrlValidator() {
		this(null, null);
	}

	/**
	 * Create a validator with default SSRF protections plus optional custom patterns.
	 * @param allowedUrlPatterns glob patterns for allowed URLs (if set, only matching
	 * URLs are allowed after passing other checks)
	 * @param blockedUrlPatterns glob patterns for additional blocked URLs
	 */
	public BrowserUrlValidator(List<String> allowedUrlPatterns, List<String> blockedUrlPatterns) {
		this.defaultBlockedPatterns = compileGlobs(DEFAULT_BLOCKED_GLOBS);
		this.customBlockedPatterns = compileGlobs(blockedUrlPatterns);
		this.allowedPatterns = compileGlobs(allowedUrlPatterns);
	}

	/**
	 * Validate a URL before browser navigation.
	 * @param url the URL to validate
	 * @throws BrowserOperationException if the URL is blocked
	 */
	public void validate(String url) {
		if (url == null || url.isBlank()) {
			throw new BrowserOperationException("Blocked URL: URL cannot be null or empty");
		}

		// Check scheme from raw string first (before URI parsing which may fail on
		// exotic schemes)
		int colonIndex = url.indexOf(':');
		if (colonIndex > 0) {
			String scheme = url.substring(0, colonIndex).toLowerCase();
			if (!ALLOWED_SCHEMES.contains(scheme)) {
				logger.warn("Blocked URL with disallowed scheme: {}", scheme);
				throw new BrowserOperationException("Blocked URL scheme: only HTTP and HTTPS are allowed");
			}
		}
		else {
			throw new BrowserOperationException("Blocked URL scheme: only HTTP and HTTPS are allowed");
		}

		URI uri;
		try {
			uri = new URI(url);
		}
		catch (URISyntaxException e) {
			throw new BrowserOperationException("Blocked URL: malformed URL — " + e.getMessage());
		}

		// Check default blocked patterns (cloud metadata, localhost, private networks)
		for (Pattern pattern : defaultBlockedPatterns) {
			if (pattern.matcher(url).matches()) {
				logger.warn("Blocked URL matching internal/metadata pattern: {}", url);
				throw new BrowserOperationException("Blocked URL (internal/metadata endpoint): " + url);
			}
		}

		// Check custom blocked patterns
		for (Pattern pattern : customBlockedPatterns) {
			if (pattern.matcher(url).matches()) {
				logger.warn("Blocked URL matching custom policy pattern: {}", url);
				throw new BrowserOperationException("Blocked URL (custom policy): " + url);
			}
		}

		// Check allowlist (if configured, URL must match at least one pattern)
		if (!allowedPatterns.isEmpty()) {
			boolean matched = false;
			for (Pattern pattern : allowedPatterns) {
				if (pattern.matcher(url).matches()) {
					matched = true;
					break;
				}
			}
			if (!matched) {
				logger.warn("Blocked URL not in allowlist: {}", url);
				throw new BrowserOperationException("Blocked URL (not in allowlist): " + url);
			}
		}

		logger.debug("URL validation passed: {}", url);
	}

	private static List<Pattern> compileGlobs(List<String> globs) {
		if (globs == null || globs.isEmpty()) {
			return List.of();
		}
		List<Pattern> patterns = new ArrayList<>(globs.size());
		for (String glob : globs) {
			if (glob != null && !glob.isBlank()) {
				patterns.add(globToPattern(glob));
			}
		}
		return List.copyOf(patterns);
	}

	/**
	 * Convert a glob pattern to a regex Pattern. {@code *} matches any sequence of
	 * characters.
	 */
	static Pattern globToPattern(String glob) {
		StringBuilder regex = new StringBuilder();
		for (int i = 0; i < glob.length(); i++) {
			char c = glob.charAt(i);
			if (c == '*') {
				regex.append(".*");
			}
			else {
				regex.append(Pattern.quote(String.valueOf(c)));
			}
		}
		return Pattern.compile(regex.toString(), Pattern.CASE_INSENSITIVE);
	}

}
