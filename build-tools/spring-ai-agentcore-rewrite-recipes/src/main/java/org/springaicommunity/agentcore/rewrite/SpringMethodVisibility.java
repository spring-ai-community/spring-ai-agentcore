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
import org.openrewrite.java.tree.J.Modifier;
import org.openrewrite.java.tree.Space;

/**
 * Removes the {@code public} modifier from methods declared in non-public classes,
 * matching Spring's {@code SpringMethodVisibility} Checkstyle rule. Methods annotated
 * with {@code @Override} are left untouched.
 */
public class SpringMethodVisibility extends Recipe {

	@Override
	public String getDisplayName() {
		return "Remove public from methods in non-public classes";
	}

	@Override
	public String getDescription() {
		return "Removes the public modifier from methods in non-public enclosing classes, "
				+ "matching Spring's SpringMethodVisibility Checkstyle rule. " + "Methods with @Override are skipped.";
	}

	@Override
	public TreeVisitor<?, ExecutionContext> getVisitor() {
		return new JavaIsoVisitor<ExecutionContext>() {

			@Override
			public J.MethodDeclaration visitMethodDeclaration(J.MethodDeclaration method, ExecutionContext ctx) {
				J.MethodDeclaration md = super.visitMethodDeclaration(method, ctx);

				// Only act on public methods
				if (!this.hasPublicModifier(md)) {
					return md;
				}

				// Skip @Override methods
				if (this.hasOverrideAnnotation(md)) {
					return md;
				}

				// Check if enclosing class is non-public
				Object parent = this.getCursor().getParentTreeCursor().getValue();
				if (parent instanceof J.Block) {
					Object grandparent = this.getCursor().getParentTreeCursor().getParentTreeCursor().getValue();
					if (grandparent instanceof J.ClassDeclaration enclosingClass) {
						if (this.isNonPublicClass(enclosingClass)) {
							return this.removePublicModifier(md);
						}
					}
				}

				return md;
			}

			private boolean hasPublicModifier(J.MethodDeclaration md) {
				return md.getModifiers().stream().anyMatch((m) -> m.getType() == Modifier.Type.Public);
			}

			private boolean isNonPublicClass(J.ClassDeclaration cd) {
				// Skip if enclosing class is public or protected
				boolean hasPublic = cd.getModifiers().stream().anyMatch((m) -> m.getType() == Modifier.Type.Public);
				boolean hasProtected = cd.getModifiers()
					.stream()
					.anyMatch((m) -> m.getType() == Modifier.Type.Protected);
				if (hasPublic || hasProtected) {
					return false;
				}
				// Skip if any ancestor is an interface (all interface members
				// are implicitly public)
				var cursor = this.getCursor().getParentTreeCursor();
				while (cursor != null) {
					Object val = cursor.getValue();
					if (val instanceof J.ClassDeclaration ancestor) {
						if (ancestor.getKind() == J.ClassDeclaration.Kind.Type.Interface) {
							return false;
						}
					}
					var parentCursor = cursor.getParent();
					if (parentCursor == null) {
						break;
					}
					cursor = parentCursor;
				}
				return true;
			}

			private boolean hasOverrideAnnotation(J.MethodDeclaration md) {
				return md.getLeadingAnnotations().stream().anyMatch((a) -> "Override".equals(a.getSimpleName()));
			}

			private J.MethodDeclaration removePublicModifier(J.MethodDeclaration md) {
				List<Modifier> modifiers = new ArrayList<>(md.getModifiers());
				Space publicPrefix = modifiers.stream()
					.filter((m) -> m.getType() == Modifier.Type.Public)
					.findFirst()
					.map(Modifier::getPrefix)
					.orElse(Space.EMPTY);
				modifiers.removeIf((m) -> m.getType() == Modifier.Type.Public);
				if (!modifiers.isEmpty()) {
					// Transfer public's prefix to the next modifier
					Modifier first = modifiers.get(0);
					modifiers.set(0, first.withPrefix(publicPrefix));
					return md.withModifiers(modifiers);
				}
				// No modifiers left: transfer prefix to the return type (or name for
				// constructors)
				if (md.getReturnTypeExpression() != null) {
					return md.withModifiers(modifiers)
						.withReturnTypeExpression(md.getReturnTypeExpression().withPrefix(publicPrefix));
				}
				return md.withModifiers(modifiers).withName(md.getName().withPrefix(publicPrefix));
			}

		};
	}

}
