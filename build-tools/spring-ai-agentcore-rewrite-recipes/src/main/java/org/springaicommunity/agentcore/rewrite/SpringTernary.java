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
import org.openrewrite.Tree;
import org.openrewrite.TreeVisitor;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JRightPadded;
import org.openrewrite.java.tree.Space;
import org.openrewrite.marker.Markers;

/**
 * Rewrites ternary expressions to match {@code spring-javaformat-checkstyle}'s
 * {@code SpringTernaryCheck}:
 * <ul>
 * <li>Wraps the condition in parentheses if not already parenthesized.</li>
 * <li>Converts {@code (a == b) ? x : y} to {@code (a != b) ? y : x} (uses {@code !=} as
 * the test, swapping branches accordingly).</li>
 * </ul>
 */
public class SpringTernary extends Recipe {

	@Override
	public String getDisplayName() {
		return "Use Spring-style ternary expressions";
	}

	@Override
	public String getDescription() {
		return "Wraps ternary conditions in parentheses and converts == "
				+ "tests to != with swapped branches, matching " + "spring-javaformat-checkstyle's SpringTernaryCheck.";
	}

	@Override
	public TreeVisitor<?, ExecutionContext> getVisitor() {
		return new JavaIsoVisitor<ExecutionContext>() {

			@Override
			public J.Ternary visitTernary(J.Ternary ternary, ExecutionContext ctx) {
				J.Ternary t = super.visitTernary(ternary, ctx);
				Expression condition = t.getCondition();
				Expression truePart = t.getTruePart();
				Expression falsePart = t.getFalsePart();

				// Step 1: flip == to != and swap branches.
				if (condition instanceof J.Binary binary && binary.getOperator() == J.Binary.Type.Equal) {
					condition = binary.withOperator(J.Binary.Type.NotEqual);
					Expression tmp = truePart;
					truePart = falsePart;
					falsePart = tmp;
				}

				// Step 2: wrap condition in parens if not already.
				if (!(condition instanceof J.Parentheses<?>)) {
					condition = this.parenthesize(condition);
				}

				if (condition == t.getCondition() && truePart == t.getTruePart() && falsePart == t.getFalsePart()) {
					return t;
				}
				return t.withCondition(condition).withTruePart(truePart).withFalsePart(falsePart);
			}

			private J.Parentheses<Expression> parenthesize(Expression expr) {
				return new J.Parentheses<>(Tree.randomId(), expr.getPrefix(), Markers.EMPTY,
						new JRightPadded<>(expr.withPrefix(Space.EMPTY), Space.EMPTY, Markers.EMPTY));
			}

		};
	}

}
