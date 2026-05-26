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

class OverloadMethodsDeclarationOrderTests implements RewriteTest {

	@Override
	public void defaults(RecipeSpec spec) {
		spec.recipe(new OverloadMethodsDeclarationOrder());
	}

	@Test
	void groupsOverloadedMethodsTogether() {
		this.rewriteRun(java("""
				class Example {

					void foo() {
					}

					void bar() {
					}

					void foo(int x) {
					}

				}
				""", """
				class Example {

					void foo() {
					}

					void foo(int x) {
					}

					void bar() {
					}

				}
				"""));
	}

	@Test
	void leavesAlreadyGroupedMethodsUntouched() {
		this.rewriteRun(java("""
				class Example {

					void foo() {
					}

					void foo(int x) {
					}

					void bar() {
					}

				}
				"""));
	}

	@Test
	void handlesMultipleOverloadGroups() {
		this.rewriteRun(java("""
				class Example {

					void foo() {
					}

					void bar() {
					}

					void foo(int x) {
					}

					void bar(String s) {
					}

				}
				""", """
				class Example {

					void foo() {
					}

					void foo(int x) {
					}

					void bar() {
					}

					void bar(String s) {
					}

				}
				"""));
	}

	@Test
	void preservesNonMethodMembers() {
		this.rewriteRun(java("""
				class Example {

					private int field;

					void foo() {
					}

					private String name;

					void foo(int x) {
					}

				}
				""", """
				class Example {

					private int field;

					void foo() {
					}

					void foo(int x) {
					}

					private String name;

				}
				"""));
	}

	@Test
	void leavesClassWithNoOverloadsAlone() {
		this.rewriteRun(java("""
				class Example {

					void foo() {
					}

					void bar() {
					}

					void baz() {
					}

				}
				"""));
	}

}
