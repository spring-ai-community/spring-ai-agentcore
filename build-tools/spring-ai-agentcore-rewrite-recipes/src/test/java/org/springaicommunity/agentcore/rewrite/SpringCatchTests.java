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

class SpringCatchTests implements RewriteTest {

	@Override
	public void defaults(RecipeSpec spec) {
		spec.recipe(new SpringCatch());
	}

	@Test
	void renamesSingleLetterCatchVariableAndItsReferences() {
		this.rewriteRun(java("""
				class C {
					void m() {
						try {
							throw new RuntimeException();
						}
						catch (Exception e) {
							System.out.println(e.getMessage());
							throw new RuntimeException(e);
						}
					}
				}
				""", """
				class C {
					void m() {
						try {
							throw new RuntimeException();
						}
						catch (Exception ex) {
							System.out.println(ex.getMessage());
							throw new RuntimeException(ex);
						}
					}
				}
				"""));
	}

	@Test
	void leavesMultiCharCatchNameAlone() {
		this.rewriteRun(java("""
				class C {
					void m() {
						try {
							throw new RuntimeException();
						}
						catch (Exception ex) {
							System.out.println(ex.getMessage());
						}
					}
				}
				"""));
	}

	@Test
	void doesNotRenameUnrelatedFieldNamedSameAsCatchParam() {
		// The 'e' field on Math (Math.E is uppercase but consider general case);
		// verify that an unrelated reference is not clobbered.
		this.rewriteRun(java("""
				class C {
					int e = 5;

					void m() {
						try {
							throw new RuntimeException();
						}
						catch (Exception e) {
							System.out.println(e.getMessage());
							System.out.println(this.e);
						}
					}
				}
				""", """
				class C {
					int e = 5;

					void m() {
						try {
							throw new RuntimeException();
						}
						catch (Exception ex) {
							System.out.println(ex.getMessage());
							System.out.println(this.e);
						}
					}
				}
				"""));
	}

}
