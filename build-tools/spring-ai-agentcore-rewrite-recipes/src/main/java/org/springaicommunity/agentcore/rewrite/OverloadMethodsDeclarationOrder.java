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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.openrewrite.ExecutionContext;
import org.openrewrite.Recipe;
import org.openrewrite.TreeVisitor;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.Statement;

/**
 * Reorders class body members so that overloaded methods (same name) are grouped
 * together, matching Checkstyle's {@code OverloadMethodsDeclarationOrder} rule.
 * <p>
 * Non-method members (fields, constructors, inner classes) are left in place. When
 * methods with the same name are separated by other methods, the later occurrences are
 * moved to immediately follow the first occurrence of that name.
 */
public class OverloadMethodsDeclarationOrder extends Recipe {

	@Override
	public String getDisplayName() {
		return "Group overloaded methods together";
	}

	@Override
	public String getDescription() {
		return "Reorders class members so that overloaded methods (same name) "
				+ "are adjacent, matching Checkstyle's OverloadMethodsDeclarationOrder.";
	}

	@Override
	public TreeVisitor<?, ExecutionContext> getVisitor() {
		return new JavaIsoVisitor<ExecutionContext>() {

			@Override
			public J.ClassDeclaration visitClassDeclaration(J.ClassDeclaration classDecl, ExecutionContext ctx) {
				J.ClassDeclaration cd = super.visitClassDeclaration(classDecl, ctx);
				// Skip interfaces — method ordering there is a design choice
				if (cd.getKind() == J.ClassDeclaration.Kind.Type.Interface) {
					return cd;
				}
				List<Statement> statements = cd.getBody().getStatements();
				if (statements.size() < 2) {
					return cd;
				}

				List<Statement> reordered = this.reorder(statements);
				if (reordered.equals(statements)) {
					return cd;
				}
				return cd.withBody(cd.getBody().withStatements(reordered));
			}

			private List<Statement> reorder(List<Statement> statements) {
				// Track first-seen position of each method name
				Map<String, Integer> firstSeen = new LinkedHashMap<>();
				List<Statement> result = new ArrayList<>(statements.size());

				for (Statement stmt : statements) {
					if (stmt instanceof J.MethodDeclaration md) {
						String name = md.getSimpleName();
						if (firstSeen.containsKey(name)) {
							// Insert after the last method with this name
							int insertAfter = this.findLastIndexOfName(result, name);
							result.add(insertAfter + 1, stmt);
						}
						else {
							firstSeen.put(name, result.size());
							result.add(stmt);
						}
					}
					else {
						result.add(stmt);
					}
				}
				return result;
			}

			private int findLastIndexOfName(List<Statement> list, String name) {
				for (int i = list.size() - 1; i >= 0; i--) {
					if (list.get(i) instanceof J.MethodDeclaration md && md.getSimpleName().equals(name)) {
						return i;
					}
				}
				return -1;
			}

		};
	}

}
