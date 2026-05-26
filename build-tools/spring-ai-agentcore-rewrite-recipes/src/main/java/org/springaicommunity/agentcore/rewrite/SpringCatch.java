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
import org.openrewrite.java.RenameVariable;
import org.openrewrite.java.tree.J;

/**
 * Renames single-letter catch parameters to {@code ex}, matching
 * {@code spring-javaformat-checkstyle}'s {@code SpringCatchCheck}.
 * <p>
 * Transforms {@code catch (Exception e) { ... e ... }} to {@code catch (Exception ex) {
 * ... ex ... }}, updating both the parameter declaration and all references inside the
 * catch block. Field accesses with the same simple name (e.g. {@code obj.e}) are left
 * alone — the rename uses OpenRewrite's {@link RenameVariable} utility, which honours
 * type-info to rename only the catch-local.
 */
public class SpringCatch extends Recipe {

	private static final String NEW_NAME = "ex";

	@Override
	public String getDisplayName() {
		return "Rename single-letter catch parameters to ex";
	}

	@Override
	public String getDescription() {
		return "Single-letter exception variables are flagged by "
				+ "spring-javaformat-checkstyle's SpringCatchCheck. This recipe "
				+ "renames such variables (and references to them within the " + "catch block) to 'ex'.";
	}

	@Override
	public TreeVisitor<?, ExecutionContext> getVisitor() {
		return new JavaIsoVisitor<ExecutionContext>() {

			@Override
			public J.Try.Catch visitCatch(J.Try.Catch aCatch, ExecutionContext ctx) {
				J.Try.Catch c = super.visitCatch(aCatch, ctx);
				J.VariableDeclarations param = c.getParameter().getTree();
				if (param.getVariables().size() != 1) {
					return c;
				}
				J.VariableDeclarations.NamedVariable namedVar = param.getVariables().get(0);
				if (namedVar.getSimpleName().length() != 1) {
					return c;
				}
				this.doAfterVisit(new RenameVariable<>(namedVar, NEW_NAME));
				return c;
			}

		};
	}

}
