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

package org.springaicommunity.agentcore.payments.tool;

import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

import org.springframework.util.Assert;

/**
 * Hosts the {@code paidHttpRequest} tool may call and pay. An entry matches a hostname
 * exactly, ignoring case; {@code *.example.com} matches subdomains of {@code example.com}
 * (not {@code example.com} itself). Ports are ignored.
 *
 * @author Andrei Shakirin
 */
public final class AllowedHosts {

	private static final Pattern HOSTNAME = Pattern
		.compile("(\\*\\.)?[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)*");

	private final List<String> entries;

	private AllowedHosts(List<String> entries) {
		this.entries = entries;
	}

	/**
	 * Creates the allowlist.
	 * @param entries hostnames or {@code *.domain} patterns, not empty
	 * @return the allowlist
	 * @throws IllegalArgumentException if the list is empty or an entry is not a hostname
	 * (for example contains a scheme, path or port)
	 */
	public static AllowedHosts of(List<String> entries) {
		Assert.notEmpty(entries, "allowed hosts must not be empty");
		List<String> normalized = entries.stream().map((entry) -> entry.trim().toLowerCase(Locale.ROOT)).toList();
		for (String entry : normalized) {
			Assert.isTrue(HOSTNAME.matcher(entry).matches(),
					() -> "'" + entry + "' is not a hostname or *.domain pattern (no scheme, path or port)");
		}
		return new AllowedHosts(normalized);
	}

	/**
	 * Tells whether the host of a URL is allowed.
	 * @param uri the URL
	 * @return {@code true} if the URL is http(s) and its host matches an entry
	 */
	public boolean allows(URI uri) {
		String scheme = uri.getScheme();
		if (scheme == null || !(scheme.equalsIgnoreCase("https") || scheme.equalsIgnoreCase("http"))) {
			return false;
		}
		return this.allowsHost(uri.getHost());
	}

	private boolean allowsHost(@Nullable String host) {
		if (host == null) {
			return false;
		}
		String candidate = host.toLowerCase(Locale.ROOT);
		for (String entry : this.entries) {
			if ((entry.startsWith("*.")) ? candidate.endsWith(entry.substring(1)) : candidate.equals(entry)) {
				return true;
			}
		}
		return false;
	}

	@Override
	public String toString() {
		return String.join(", ", this.entries);
	}

}
