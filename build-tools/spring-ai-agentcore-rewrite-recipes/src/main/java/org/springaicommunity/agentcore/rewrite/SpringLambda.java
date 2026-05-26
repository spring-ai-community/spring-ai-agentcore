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

import org.openrewrite.ExecutionContext;
import org.openrewrite.Recipe;
import org.openrewrite.TreeVisitor;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.tree.J;

/**
 * Adds parentheses around single-argument lambda parameter lists, matching
 * {@code spring-javaformat-checkstyle}'s {@code SpringLambdaCheck}.
 * <p>
 * Transforms {@code arg -> body} to {@code (arg) -> body}. Lambdas that already have
 * parentheses, or that have zero or multiple parameters (which always require
 * parentheses), are left untouched.
 */
public class SpringLambda extends Recipe {

	@Override
	public String getDisplayName() {
		return "Add parentheses around single-argument lambdas";
	}

	@Override
	public String getDescription() {
		return "Wraps the parameter of a single-argument lambda in parentheses, "
				+ "matching spring-javaformat-checkstyle's SpringLambdaCheck.";
	}

	@Override
	public TreeVisitor<?, ExecutionContext> getVisitor() {
		return new JavaIsoVisitor<ExecutionContext>() {

			@Override
			public J.Lambda visitLambda(J.Lambda lambda, ExecutionContext ctx) {
				J.Lambda l = super.visitLambda(lambda, ctx);
				J.Lambda.Parameters params = l.getParameters();
				if (params.isParenthesized() || params.getParameters().size() != 1) {
					return l;
				}
				return l.withParameters(params.withParenthesized(true));
			}

		};
	}

}
