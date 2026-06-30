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

package org.springaicommunity.agentcore.codeinterpreter;

/**
 * Controls which files an ephemeral code-interpreter execution returns.
 * <p>
 * The levels widen from "only what the interpreter surfaced" to "every file the run
 * created":
 * <ul>
 * <li>{@link #RESULT_ONLY} — only files emitted inline in the execution result stream
 * (for example, a chart rendered directly in the response). No filesystem inspection is
 * performed.</li>
 * <li>{@link #GENERATED} — inline result files plus files newly created on disk during
 * the run, limited to common output types (images, PDF, CSV/Excel, JSON, text, HTML) and
 * excluding system directories. This is the default.</li>
 * <li>{@link #ALL} — inline result files plus <em>all</em> files newly created on disk
 * during the run (any extension), still excluding system directories.</li>
 * </ul>
 * All disk-based levels are computed as a delta against a baseline taken at session
 * start, so pre-existing sandbox files are never returned.
 *
 * @author Yuriy Bezsonov
 */
public enum FileRetrievalPolicy {

	/** Return only files emitted inline in the execution result. */
	RESULT_ONLY,

	/**
	 * Return inline result files plus newly created files of common output types
	 * (default).
	 */
	GENERATED,

	/** Return inline result files plus all newly created files, regardless of type. */
	ALL

}
