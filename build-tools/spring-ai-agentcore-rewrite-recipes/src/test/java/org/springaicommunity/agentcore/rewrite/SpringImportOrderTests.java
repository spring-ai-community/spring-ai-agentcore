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

class SpringImportOrderTests implements RewriteTest {

	@Override
	public void defaults(RecipeSpec spec) {
		spec.recipe(new SpringImportOrder());
	}

	@Test
	void reordersJunitBeforeProjectImportThenSpringThenStatic() {
		// Mirrors the real failing case: project import had been promoted ahead of
		// other "third-party" imports and split with a blank line; Spring expects
		// junit + project together (alphabetical), blank, then spring, blank, static.
		this.rewriteRun(java("""
				package org.springaicommunity.agentcore.ping;

				import org.springaicommunity.agentcore.model.PingStatus;

				import org.junit.jupiter.api.Test;
				import org.springframework.http.HttpStatus;

				import static org.assertj.core.api.Assertions.assertThat;
				import static org.mockito.Mockito.mock;

				class StaticAgentCorePingServiceTests {
				}
				""", """
				package org.springaicommunity.agentcore.ping;

				import org.junit.jupiter.api.Test;
				import org.springaicommunity.agentcore.model.PingStatus;

				import org.springframework.http.HttpStatus;

				import static org.assertj.core.api.Assertions.assertThat;
				import static org.mockito.Mockito.mock;

				class StaticAgentCorePingServiceTests {
				}
				"""));
	}

	@Test
	void leavesAlreadyOrderedImportsUntouched() {
		this.rewriteRun(java("""
				package com.example;

				import java.util.List;
				import java.util.Map;

				import com.fasterxml.jackson.databind.ObjectMapper;

				import org.springframework.http.HttpStatus;

				import static org.junit.jupiter.api.Assertions.assertEquals;

				class Stable {
				}
				"""));
	}

	@Test
	void groupsJavaJavaxOtherSpring() {
		this.rewriteRun(java("""
				package com.example;

				import org.springframework.boot.SpringApplication;
				import javax.servlet.ServletException;
				import com.fasterxml.jackson.databind.ObjectMapper;
				import java.util.List;
				import java.io.IOException;

				class Mixed {
				}
				""", """
				package com.example;

				import java.io.IOException;
				import java.util.List;

				import javax.servlet.ServletException;

				import com.fasterxml.jackson.databind.ObjectMapper;

				import org.springframework.boot.SpringApplication;

				class Mixed {
				}
				"""));
	}

	@Test
	void putsAllStaticImportsAtBottomTogether() {
		this.rewriteRun(java("""
				package com.example;

				import static java.util.Arrays.asList;
				import org.junit.jupiter.api.Test;
				import static org.assertj.core.api.Assertions.assertThat;

				class StaticHandling {
				}
				""", """
				package com.example;

				import org.junit.jupiter.api.Test;

				import static java.util.Arrays.asList;
				import static org.assertj.core.api.Assertions.assertThat;

				class StaticHandling {
				}
				"""));
	}

	@Test
	void doesNothingWhenLessThanTwoImports() {
		this.rewriteRun(java("""
				package com.example;

				import java.util.List;

				class TooFew {
				}
				"""));
	}

}
