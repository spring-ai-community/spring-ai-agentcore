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

package org.springaicommunity.agentcore.memory.session;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import software.amazon.awssdk.services.bedrockagentcore.BedrockAgentCoreClient;

import org.springframework.ai.session.EventFilter;
import org.springframework.ai.session.Session;
import org.springframework.ai.session.SessionEvent;
import org.springframework.ai.session.SessionRepository;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link SessionApiCompatibility}, the startup guard against a community
 * Session API jar on a Spring AI 2.1 classpath.
 *
 * @author Spring AI Community
 */
@ExtendWith(OutputCaptureExtension.class)
class SessionApiCompatibilityTests {

	private static final String COMMUNITY_SESSION = SessionApiCompatibility.COMMUNITY_MARKERS.get(0);

	private static final String COMMUNITY_SESSION_MANAGEMENT = SessionApiCompatibility.COMMUNITY_MARKERS.get(1);

	@TempDir
	Path tmp;

	@Test
	void theTestClasspathPassesBothChecks() {
		assertThatNoException().isThrownBy(() -> {
			SessionApiCompatibility.checkSignatures(SessionRepository.class, AgentCoreSessionRepository.class,
					EventFilter.class);
			SessionApiCompatibility.checkArtifactMarkers(this.getClass().getClassLoader());
			SessionApiCompatibility.verifyOnce();
			SessionApiCompatibility.verifyOnce();
		});
	}

	@Test
	void theRepositoryConstructorRunsTheCheck() {
		// Covers repositories built through the public builder, not only the
		// auto-configured bean.
		SessionApiCompatibility.resetForTests();
		assertThat(SessionApiCompatibility.isVerified()).isFalse();
		AgentCoreSessionRepository.builder()
			.memoryId("mem-1")
			.client(Mockito.mock(BedrockAgentCoreClient.class))
			.build();
		assertThat(SessionApiCompatibility.isVerified()).isTrue();
	}

	@Test
	void theTestClasspathHasNoCommunitySessionApi() throws IOException {
		ClassLoader loader = this.getClass().getClassLoader();
		for (String marker : SessionApiCompatibility.COMMUNITY_MARKERS) {
			assertThat(Collections.list(loader.getResources(marker))).as(marker).isEmpty();
		}
	}

	@Test
	void artifactWordingOutsideThisClassNamesSpringAi(CapturedOutput output) throws IOException {
		try (InputStream in = this.getClass()
			.getClassLoader()
			.getResourceAsStream("META-INF/additional-spring-configuration-metadata.json")) {
			assertThat(in).isNotNull();
			assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8)).contains(
					"'org.springframework.ai:spring-ai-session' and 'org.springframework.ai:spring-ai-session-advisor'")
				.doesNotContain("org.springaicommunity:spring-ai-session");
		}
	}

	@Test
	void signatureCheckRejectsAnOldSpi() {
		assertThatThrownBy(() -> SessionApiCompatibility.checkSignatures(LegacyRepository.class,
				AgentCoreSessionRepository.class, LegacyFilter.class))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("requires " + SessionApiCompatibility.REQUIRED_ARTIFACT)
			.hasMessageContaining("does not implement LegacyRepository.findByUserId(String)")
			.hasMessageContaining("applyWindow(List) is missing")
			.hasMessageContaining("Remove org.springaicommunity:spring-ai-session");
	}

	@Test
	void signatureCheckRejectsASpiMethodTheRepositoryDoesNotImplement() {
		// A later release that adds an abstract SessionRepository method: the repository
		// would load and then fail with AbstractMethodError on the first call.
		assertThatThrownBy(() -> SessionApiCompatibility.checkSignatures(NextRepository.class,
				AgentCoreSessionRepository.class, EventFilter.class))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("does not implement NextRepository.forkSession(String, String)")
			.hasMessageNotContaining("findById")
			.hasMessageNotContaining("applyWindow");
	}

	@Test
	void unrelocatedCommunityArtifactsFailFast() throws IOException {
		for (String markerPath : SessionApiCompatibility.COMMUNITY_MARKERS) {
			Path community = this.marker("community-" + markerPath.hashCode(), markerPath);
			this.resource(community, SessionApiCompatibility.SPI_CLASS_RESOURCE);
			try (URLClassLoader loader = isolatedLoader(community)) {
				assertThatThrownBy(() -> SessionApiCompatibility.checkArtifactMarkers(loader))
					.isInstanceOf(IllegalStateException.class)
					.hasMessageContaining("community Session API is on the classpath")
					.hasMessageContaining(community.getFileName().toString())
					.hasMessageContaining("Remove org.springaicommunity:spring-ai-session");
			}
		}
	}

	@Test
	void relocatedCommunityArtifactOnlyWarns(CapturedOutput output) throws IOException {
		// A jar that shaded and relocated the community API keeps its META-INF/maven
		// entry by default, but its classes live under another package.
		Path shaded = this.marker("shaded", COMMUNITY_SESSION_MANAGEMENT);
		this.resource(shaded, "com/example/shaded/org/springframework/ai/session/SessionRepository.class");
		Path spring = this.tmp.resolve("spring");
		this.resource(spring, SessionApiCompatibility.SPI_CLASS_RESOURCE);
		try (URLClassLoader loader = isolatedLoader(shaded, spring)) {
			assertThatNoException().isThrownBy(() -> SessionApiCompatibility.checkArtifactMarkers(loader));
		}
		assertThat(output).contains("Community Session API marker found at")
			.contains("treated as a relocated copy and ignored");
	}

	@Test
	void markerWithoutAnySpiClassIsReportedAsConflictOnlyWhenItShipsTheSpi() throws IOException {
		Path markerOnly = this.marker("marker-only", COMMUNITY_SESSION);
		try (URLClassLoader empty = isolatedLoader(); URLClassLoader withMarker = isolatedLoader(markerOnly)) {
			assertThatNoException().isThrownBy(() -> {
				SessionApiCompatibility.checkArtifactMarkers(empty);
				SessionApiCompatibility.checkArtifactMarkers(withMarker);
			});
		}
	}

	private Path marker(String dir, String resource) throws IOException {
		Path root = this.tmp.resolve(dir);
		Path file = root.resolve(resource);
		Files.createDirectories(file.getParent());
		Files.writeString(file, "groupId=org.springaicommunity\nversion=0.10.0\n", StandardCharsets.ISO_8859_1);
		return root;
	}

	private void resource(Path root, String resource) throws IOException {
		Path file = root.resolve(resource);
		Files.createDirectories(file.getParent());
		Files.write(file, new byte[0]);
	}

	// The parent is null (bootstrap only) so the real test classpath markers stay out.
	private static URLClassLoader isolatedLoader(Path... roots) throws IOException {
		URL[] urls = new URL[roots.length];
		for (int i = 0; i < roots.length; i++) {
			urls[i] = roots[i].toUri().toURL();
		}
		return new URLClassLoader(urls, null);
	}

	// Shape of the community 0.10 SPI: user lookups instead of findByMetadata.
	interface LegacyRepository {

		Optional<Session> findById(String sessionId);

		List<Session> findByUserId(String userId);

		void appendEvent(SessionEvent event);

	}

	// Shape of a later SPI that adds an abstract method.
	interface NextRepository extends SessionRepository {

		void forkSession(String sourceSessionId, String targetSessionId);

	}

	// Shape of the community EventFilter: no applyWindow.
	static final class LegacyFilter {

	}

}
