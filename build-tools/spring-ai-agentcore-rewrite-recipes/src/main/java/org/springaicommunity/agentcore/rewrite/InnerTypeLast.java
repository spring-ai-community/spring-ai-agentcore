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
import java.util.List;

import org.openrewrite.ExecutionContext;
import org.openrewrite.Recipe;
import org.openrewrite.TreeVisitor;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.Statement;

/**
 * Moves nested type declarations (inner classes/interfaces/enums) to the end of the
 * enclosing class body, matching Checkstyle's {@code InnerTypeLast} rule as included in
 * {@code spring-javaformat-checkstyle}.
 * <p>
 * Within each class body, the relative order of non-type members (fields, initializers,
 * constructors, methods) is preserved, and the relative order of nested types among
 * themselves is preserved. Only the partition is moved.
 */
public class InnerTypeLast extends Recipe {

	@Override
	public String getDisplayName() {
		return "Move inner types to end of enclosing class";
	}

	@Override
	public String getDescription() {
		return "Reorders class members so that nested type declarations "
				+ "(class/interface/enum/record) appear after all fields, "
				+ "initializers, constructors, and methods, matching " + "Checkstyle's InnerTypeLast.";
	}

	@Override
	public TreeVisitor<?, ExecutionContext> getVisitor() {
		return new JavaIsoVisitor<ExecutionContext>() {

			@Override
			public J.ClassDeclaration visitClassDeclaration(J.ClassDeclaration classDecl, ExecutionContext ctx) {
				J.ClassDeclaration cd = super.visitClassDeclaration(classDecl, ctx);
				List<Statement> statements = cd.getBody().getStatements();
				if (statements.size() < 2) {
					return cd;
				}

				// Partition while preserving relative order within each partition.
				List<Statement> nonTypes = new ArrayList<>(statements.size());
				List<Statement> types = new ArrayList<>();
				int firstTypeIdx = -1;
				int lastNonTypeIdx = -1;
				for (int i = 0; i < statements.size(); i++) {
					Statement s = statements.get(i);
					if (s instanceof J.ClassDeclaration) {
						types.add(s);
						if (firstTypeIdx == -1) {
							firstTypeIdx = i;
						}
					}
					else {
						nonTypes.add(s);
						lastNonTypeIdx = i;
					}
				}

				// Already in the right shape: every non-type comes before every type.
				if (firstTypeIdx == -1 || lastNonTypeIdx == -1 || lastNonTypeIdx < firstTypeIdx) {
					return cd;
				}

				List<Statement> reordered = new ArrayList<>(statements.size());
				reordered.addAll(nonTypes);
				reordered.addAll(types);
				return cd.withBody(cd.getBody().withStatements(reordered));
			}

		};
	}

}
