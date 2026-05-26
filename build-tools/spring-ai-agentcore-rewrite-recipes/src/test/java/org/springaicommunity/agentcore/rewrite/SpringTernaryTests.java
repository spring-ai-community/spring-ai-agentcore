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

import org.junit.jupiter.api.Test;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import static org.openrewrite.java.Assertions.java;

class SpringTernaryTests implements RewriteTest {

	@Override
	public void defaults(RecipeSpec spec) {
		spec.recipe(new SpringTernary());
	}

	@Test
	void wrapsUnparenthesizedNotEqualCondition() {
		this.rewriteRun(java("""
				class C {
					int sizeOf(Object[] arr) {
						return arr != null ? arr.length : 0;
					}
				}
				""", """
				class C {
					int sizeOf(Object[] arr) {
						return (arr != null) ? arr.length : 0;
					}
				}
				"""));
	}

	@Test
	void flipsEqualToNotEqualAndSwapsBranches() {
		this.rewriteRun(java("""
				class C {
					int sizeOf(Object[] arr) {
						return arr == null ? 0 : arr.length;
					}
				}
				""", """
				class C {
					int sizeOf(Object[] arr) {
						return (arr != null) ? arr.length : 0;
					}
				}
				"""));
	}

	@Test
	void leavesAlreadyParenthesizedNotEqualAlone() {
		this.rewriteRun(java("""
				class C {
					int sizeOf(Object[] arr) {
						return (arr != null) ? arr.length : 0;
					}
				}
				"""));
	}

	@Test
	void wrapsNonComparisonCondition() {
		this.rewriteRun(java("""
				class C {
					String pick(boolean b) {
						return b ? "yes" : "no";
					}
				}
				""", """
				class C {
					String pick(boolean b) {
						return (b) ? "yes" : "no";
					}
				}
				"""));
	}

}
