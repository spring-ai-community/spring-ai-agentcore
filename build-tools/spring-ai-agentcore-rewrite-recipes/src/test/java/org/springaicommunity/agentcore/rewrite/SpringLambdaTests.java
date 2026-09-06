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

package org.springaicommunity.agentcore.rewrite;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import static org.openrewrite.java.Assertions.java;

class SpringLambdaTests implements RewriteTest {

	// OpenRewrite ships a javac-internals-bound parser per JDK (rewrite-java-17/21/25).
	// There is no parser for JDK 26 yet, so on JDK 26+ the newest available parser
	// mis-computes lambda source offsets and throws while parsing. Skip (not fail) until
	// an OpenRewrite parser for the running JDK is published. CI runs on JDK 21, where
	// these tests execute normally.
	@BeforeEach
	void requireJdkSupportedByOpenRewrite() {
		int feature = Runtime.version().feature();
		Assumptions.assumeTrue(feature <= 25, () -> "Skipping SpringLambda parse tests: OpenRewrite has no Java parser "
				+ "for JDK " + feature + " (latest is rewrite-java-25). On this JDK the newest available parser "
				+ "mis-computes lambda source offsets and throws StringIndexOutOfBoundsException while parsing. "
				+ "Run on JDK <= 25 (CI uses JDK 21) to exercise these tests.");
	}

	@Override
	public void defaults(RecipeSpec spec) {
		spec.recipe(new SpringLambda());
	}

	@Test
	void wrapsSingleArgWithoutParens() {
		this.rewriteRun(java("""
				import java.util.concurrent.atomic.AtomicReference;

				class C {
					void m(AtomicReference<String> r) {
						r.updateAndGet(current -> current + "x");
					}
				}
				""", """
				import java.util.concurrent.atomic.AtomicReference;

				class C {
					void m(AtomicReference<String> r) {
						r.updateAndGet((current) -> current + "x");
					}
				}
				"""));
	}

	@Test
	void leavesAlreadyParenthesizedSingleArgAlone() {
		this.rewriteRun(java("""
				import java.util.concurrent.atomic.AtomicReference;

				class C {
					void m(AtomicReference<String> r) {
						r.updateAndGet((current) -> current + "x");
					}
				}
				"""));
	}

	@Test
	void leavesZeroArgLambdaAlone() {
		this.rewriteRun(java("""
				import java.util.function.Supplier;

				class C {
					void m() {
						Supplier<String> s = () -> "x";
					}
				}
				"""));
	}

	@Test
	void leavesMultiArgLambdaAlone() {
		this.rewriteRun(java("""
				import java.util.function.BiFunction;

				class C {
					void m() {
						BiFunction<String, String, String> f = (a, b) -> a + b;
					}
				}
				"""));
	}

	@Test
	void wrapsSingleArgWithBlockBody() {
		this.rewriteRun(java("""
				import java.util.concurrent.atomic.AtomicReference;

				class C {
					void m(AtomicReference<Integer> r) {
						r.updateAndGet(current -> {
							return current + 1;
						});
					}
				}
				""", """
				import java.util.concurrent.atomic.AtomicReference;

				class C {
					void m(AtomicReference<Integer> r) {
						r.updateAndGet((current) -> {
							return current + 1;
						});
					}
				}
				"""));
	}

}
