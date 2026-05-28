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

class SpringMethodVisibilityTests implements RewriteTest {

	@Override
	public void defaults(RecipeSpec spec) {
		spec.recipe(new SpringMethodVisibility());
	}

	@Test
	void removesPublicFromMethodInPackagePrivateClass() {
		this.rewriteRun(java("""
				class Outer {

					public String getData() {
						return "data";
					}

				}
				""", """
				class Outer {

					String getData() {
						return "data";
					}

				}
				"""));
	}

	@Test
	void removesPublicFromMethodInPrivateInnerClass() {
		this.rewriteRun(java("""
				public class Outer {

					private static class Inner {

						public String getValue() {
							return "value";
						}

					}

				}
				""", """
				public class Outer {

					private static class Inner {

						String getValue() {
							return "value";
						}

					}

				}
				"""));
	}

	@Test
	void leavesPublicMethodInPublicClass() {
		this.rewriteRun(java("""
				public class Outer {

					public String getData() {
						return "data";
					}

				}
				"""));
	}

	@Test
	void leavesOverrideMethodUntouched() {
		this.rewriteRun(java("""
				class Outer {

					@Override
					public String toString() {
						return "outer";
					}

				}
				"""));
	}

	@Test
	void preservesOtherModifiers() {
		this.rewriteRun(java("""
				class Outer {

					public static String helper() {
						return "help";
					}

				}
				""", """
				class Outer {

					static String helper() {
						return "help";
					}

				}
				"""));
	}

	@Test
	void leavesMethodsInInterfaceNestedRecordUntouched() {
		this.rewriteRun(java("""
				public interface MyInterface {

					record Result(String value) {

						public boolean isEmpty() {
							return this.value == null;
						}

					}

				}
				"""));
	}

}
