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

package org.springaicommunity.agentcore.service;

import java.util.ArrayList;
import java.util.List;

import reactor.core.publisher.Flux;

import org.springframework.stereotype.Service;

/**
 * Normalizes streaming responses for Server-Sent Events (SSE) by handling newline
 * characters appropriately.
 */
@Service
public class SseNormalizer {

	/**
	 * Normalize Flux for SSE by splitting tokens on newlines. Each newline becomes an
	 * empty string, which renders as an empty SSE data event. This preserves newline
	 * semantics for markdown tables and code blocks.
	 */
	public Object normalize(Object result) {
		if (result instanceof Flux<?> flux) {
			return flux.flatMapIterable(this::splitOnNewlines);
		}
		return result;
	}

	/**
	 * Split a token on newlines, interleaving empty strings as newline markers.
	 */
	public List<String> splitOnNewlines(Object item) {
		if (item == null) {
			return List.of();
		}
		String str = item instanceof String s ? s : item.toString();
		if (!str.contains("\n")) {
			return List.of(str);
		}
		String[] parts = str.split("\n", -1);
		List<String> result = new ArrayList<>(parts.length * 2 - 1);
		for (int i = 0; i < parts.length; i++) {
			if (i > 0) {
				result.add("");
			}
			if (!parts[i].isEmpty()) {
				result.add(parts[i]);
			}
		}
		return result;
	}

}
