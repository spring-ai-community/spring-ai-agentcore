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

import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.assertj.core.api.Assertions.assertThat;

class SseNormalizerTest {

	private final SseNormalizer normalizer = new SseNormalizer();

	static Stream<Arguments> splitOnNewlinesTestCases() {
		return Stream.of(Arguments.of("null input", null, List.of()),
				Arguments.of("no newlines", "hello world", List.of("hello world")),
				Arguments.of("single newline", "hello\nworld", List.of("hello", "", "world")),
				Arguments.of("multiple newlines", "a\nb\nc", List.of("a", "", "b", "", "c")),
				Arguments.of("consecutive newlines", "a\n\nb", List.of("a", "", "", "b")),
				Arguments.of("trailing newline", "hello\n", List.of("hello", "")),
				Arguments.of("leading newline", "\nhello", List.of("", "hello")),
				Arguments.of("non-string object", 123, List.of("123")), Arguments.of("empty string", "", List.of("")));
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("splitOnNewlinesTestCases")
	void splitOnNewlines(String description, Object input, List<String> expected) {
		assertThat(normalizer.splitOnNewlines(input)).isEqualTo(expected);
	}

}
