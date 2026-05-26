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
import java.util.Comparator;
import java.util.List;

import org.openrewrite.ExecutionContext;
import org.openrewrite.Recipe;
import org.openrewrite.TreeVisitor;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.Space;

/**
 * Reorders imports to match {@code spring-javaformat-checkstyle}'s
 * {@code SpringImportOrderCheck}.
 * <p>
 * Group order:
 * <ol>
 * <li>{@code java.*}</li>
 * <li>{@code javax.*}</li>
 * <li>everything else (alphabetical)</li>
 * <li>{@code org.springframework.*} (alphabetical)</li>
 * <li>static imports (alphabetical, all together)</li>
 * </ol>
 * Each non-empty group is separated from the next by a blank line.
 * <p>
 * This recipe is needed because OpenRewrite's bundled {@code OrderImports} recipe and
 * {@code SpringFormat} style get overridden by an autodetection layout that places
 * project-prefixed imports in their own group, producing output that does not match
 * Spring's conventions.
 */
public class SpringImportOrder extends Recipe {

	private static final String JAVA_PREFIX = "java.";

	private static final String JAVAX_PREFIX = "javax.";

	private static final String SPRING_PREFIX = "org.springframework.";

	@Override
	public String getDisplayName() {
		return "Order imports per Spring convention";
	}

	@Override
	public String getDescription() {
		return "Reorders imports to match spring-javaformat-checkstyle's "
				+ "SpringImportOrderCheck: java -> javax -> other -> org.springframework -> static, "
				+ "alphabetical within each group, blank line between groups.";
	}

	@Override
	public TreeVisitor<?, ExecutionContext> getVisitor() {
		return new JavaIsoVisitor<ExecutionContext>() {

			@Override
			public J.CompilationUnit visitCompilationUnit(J.CompilationUnit cu, ExecutionContext ctx) {
				List<J.Import> originals = cu.getImports();
				if (originals.size() < 2) {
					return cu;
				}

				List<J.Import> javaGroup = new ArrayList<>();
				List<J.Import> javaxGroup = new ArrayList<>();
				List<J.Import> otherGroup = new ArrayList<>();
				List<J.Import> springGroup = new ArrayList<>();
				List<J.Import> staticGroup = new ArrayList<>();

				for (J.Import imp : originals) {
					String fqn = imp.getQualid().toString();
					if (imp.isStatic()) {
						staticGroup.add(imp);
					}
					else if (fqn.startsWith(JAVA_PREFIX)) {
						javaGroup.add(imp);
					}
					else if (fqn.startsWith(JAVAX_PREFIX)) {
						javaxGroup.add(imp);
					}
					else if (fqn.startsWith(SPRING_PREFIX)) {
						springGroup.add(imp);
					}
					else {
						otherGroup.add(imp);
					}
				}

				Comparator<J.Import> byName = Comparator.comparing((imp) -> imp.getQualid().toString());
				javaGroup.sort(byName);
				javaxGroup.sort(byName);
				otherGroup.sort(byName);
				springGroup.sort(byName);
				staticGroup.sort(byName);

				List<J.Import> reordered = new ArrayList<>(originals.size());
				this.appendGroup(reordered, javaGroup, originals.get(0).getPrefix());
				this.appendGroup(reordered, javaxGroup, null);
				this.appendGroup(reordered, otherGroup, null);
				this.appendGroup(reordered, springGroup, null);
				this.appendGroup(reordered, staticGroup, null);

				// J.Import equality is id-based and ignores prefixes, so reordered
				// could compare equal to originals even when only blank-line
				// spacing differs. Rely on cu.withImports() for content-aware
				// no-op detection instead of an early-return shortcut.
				return cu.withImports(reordered);
			}

			/**
			 * Appends the contents of {@code group} to {@code accumulator}, applying the
			 * appropriate prefix:
			 * <ul>
			 * <li>The very first import in the file keeps its original prefix
			 * ({@code firstFilePrefix})</li>
			 * <li>The first import of any subsequent group gets a blank-line prefix
			 * ({@code "\n\n"})</li>
			 * <li>Other imports get a single newline prefix</li>
			 * </ul>
			 */
			private void appendGroup(List<J.Import> accumulator, List<J.Import> group, Space firstFilePrefix) {
				if (group.isEmpty()) {
					return;
				}
				for (int i = 0; i < group.size(); i++) {
					J.Import imp = group.get(i);
					Space prefix;
					if (accumulator.isEmpty() && firstFilePrefix != null) {
						prefix = firstFilePrefix;
					}
					else if (i == 0) {
						prefix = Space.format("\n\n");
					}
					else {
						prefix = Space.format("\n");
					}
					accumulator.add(imp.withPrefix(prefix));
				}
			}

		};
	}

}
