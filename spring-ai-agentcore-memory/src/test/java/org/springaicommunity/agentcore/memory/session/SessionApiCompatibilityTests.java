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
import java.util.Properties;

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
 * Tests for {@link SessionApiCompatibility}, the startup guard against a stale or mixed
 * spring-ai-session classpath.
 *
 * @author Spring AI Community
 */
@ExtendWith(OutputCaptureExtension.class)
class SessionApiCompatibilityTests {

	@TempDir
	Path tmp;

	@Test
	void theTestClasspathPassesBothChecks() {
		assertThatNoException().isThrownBy(() -> {
			SessionApiCompatibility.checkSignatures(SessionRepository.class, AgentCoreSessionRepository.class,
					SessionEvent.class, EventFilter.class);
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
	void supportedVersionLineMatchesTheSpringAiSessionThisModuleIsBuiltAgainst() throws IOException {
		// Fails a spring-ai-session bump until SUPPORTED_VERSION_LINE is revisited; the
		// next test then keeps the wording outside this class in step.
		ClassLoader loader = this.getClass().getClassLoader();
		assertThat(Collections.list(loader.getResources(SessionApiCompatibility.LEGACY_MARKER))).isEmpty();
		List<URL> markers = Collections.list(loader.getResources(SessionApiCompatibility.CURRENT_MARKER));
		assertThat(markers).hasSize(1);
		Properties properties = new Properties();
		try (InputStream in = markers.get(0).openStream()) {
			properties.load(in);
		}
		assertThat(properties.getProperty("version")).startsWith(SessionApiCompatibility.SUPPORTED_VERSION_LINE);
	}

	@Test
	void versionWordingOutsideThisClassFollowsTheSupportedLine(CapturedOutput output) throws IOException {
		String wording = "spring-ai-session " + SessionApiCompatibility.SUPPORTED_VERSIONS;
		assertThat(SessionApiCompatibility.REQUIRED_ARTIFACT).endsWith(wording);
		new AgentCoreSessionMissingDepDiagnostics().afterPropertiesSet();
		assertThat(output).contains(wording);
		try (InputStream in = this.getClass()
			.getClassLoader()
			.getResourceAsStream("META-INF/additional-spring-configuration-metadata.json")) {
			assertThat(in).isNotNull();
			assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8))
				.contains("'org.springaicommunity:spring-ai-session' (" + SessionApiCompatibility.SUPPORTED_VERSIONS);
		}
	}

	@Test
	void signatureCheckRejectsTheOldSpiWithEveryMissingMember() {
		assertThatThrownBy(() -> SessionApiCompatibility.checkSignatures(LegacyRepository.class,
				AgentCoreSessionRepository.class, LegacyEvent.class, LegacyFilter.class))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("requires org.springaicommunity:spring-ai-session 0.8.x")
			.hasMessageContaining("findById(String) returns java.util.Optional")
			.hasMessageContaining("compactEvents(String, List, List, long) is missing")
			.hasMessageContaining("does not implement LegacyRepository.replaceEvents(String, List)")
			.hasMessageContaining("isArchived() is missing")
			.hasMessageContaining("active() is missing")
			.hasMessageContaining("import org.springaicommunity:spring-ai-session-bom 0.8.0")
			.hasMessageContaining("If org.springaicommunity:spring-ai-session-management is also on the classpath");
	}

	@Test
	void signatureCheckRejectsASpiMethodTheRepositoryDoesNotImplement() {
		// A later release that adds an abstract SessionRepository method: the repository
		// would load and then fail with AbstractMethodError on the first call.
		assertThatThrownBy(() -> SessionApiCompatibility.checkSignatures(NextRepository.class,
				AgentCoreSessionRepository.class, SessionEvent.class, EventFilter.class))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("does not implement NextRepository.forkSession(String, String)")
			.hasMessageNotContaining("findById")
			.hasMessageNotContaining("isArchived");
	}

	@Test
	void signatureCheckCatchesAMixedSplit() {
		// The repository SPI is new but SessionEvent comes from an old copy, as in a
		// shaded jar that merged both versions and stripped the Maven markers.
		assertThatThrownBy(() -> SessionApiCompatibility.checkSignatures(SessionRepository.class,
				AgentCoreSessionRepository.class, LegacyEvent.class, EventFilter.class))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("isArchived() is missing")
			.hasMessageNotContaining("compactEvents")
			.hasMessageNotContaining("does not implement");
	}

	@Test
	void legacyArtifactMarkerFailsFast() throws IOException {
		Path legacy = this.marker("legacy", SessionApiCompatibility.LEGACY_MARKER, "0.5.0");
		this.resource(legacy, SessionApiCompatibility.SPI_CLASS_RESOURCE);
		Path current = this.marker("current", SessionApiCompatibility.CURRENT_MARKER, "0.8.0");
		try (URLClassLoader loader = isolatedLoader(legacy, current)) {
			assertThatThrownBy(() -> SessionApiCompatibility.checkArtifactMarkers(loader))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("Remove org.springaicommunity:spring-ai-session-management")
				.hasMessageContaining("renamed to spring-ai-session in 0.6.0")
				.hasMessageContaining(legacy.getFileName().toString());
		}
	}

	@Test
	void legacyMarkerWithoutTheUnrelocatedSpiOnlyWarns(CapturedOutput output) throws IOException {
		// A jar that shaded and relocated 0.5.0 keeps its META-INF/maven entry by
		// default,
		// but its classes live under another package, so nothing conflicts.
		Path shaded = this.marker("shaded", SessionApiCompatibility.LEGACY_MARKER, "0.5.0");
		this.resource(shaded, "com/example/shaded/org/springframework/ai/session/SessionRepository.class");
		Path current = this.marker("current", SessionApiCompatibility.CURRENT_MARKER, "0.8.0");
		this.resource(current, SessionApiCompatibility.SPI_CLASS_RESOURCE);
		try (URLClassLoader loader = isolatedLoader(shaded, current)) {
			assertThatNoException().isThrownBy(() -> SessionApiCompatibility.checkArtifactMarkers(loader));
		}
		assertThat(output).contains("spring-ai-session-management marker found at")
			.contains("treated as a relocated copy and ignored");
	}

	@Test
	void differentSpringAiSessionVersionsFailFast() throws IOException {
		Path v8 = this.marker("v8", SessionApiCompatibility.CURRENT_MARKER, "0.8.0");
		Path v7 = this.marker("v7", SessionApiCompatibility.CURRENT_MARKER, "0.7.0");
		try (URLClassLoader loader = isolatedLoader(v8, v7)) {
			assertThatThrownBy(() -> SessionApiCompatibility.checkArtifactMarkers(loader))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("Several versions of org.springaicommunity:spring-ai-session")
				.hasMessageContaining("0.7.0")
				.hasMessageContaining("0.8.0");
		}
	}

	@Test
	void sameVersionDuplicateOnlyWarns(CapturedOutput output) throws IOException {
		// For example the same jar seen through a parent and a child class loader.
		Path first = this.marker("first", SessionApiCompatibility.CURRENT_MARKER, "0.8.0");
		Path second = this.marker("second", SessionApiCompatibility.CURRENT_MARKER, "0.8.0");
		try (URLClassLoader loader = isolatedLoader(first, second)) {
			assertThatNoException().isThrownBy(() -> SessionApiCompatibility.checkArtifactMarkers(loader));
		}
		assertThat(output).contains("spring-ai-session 0.8.0 is on the classpath 2 times");
	}

	@Test
	void unsupportedVersionLineOnlyWarns(CapturedOutput output) throws IOException {
		Path next = this.marker("next", SessionApiCompatibility.CURRENT_MARKER, "0.9.0");
		try (URLClassLoader loader = isolatedLoader(next)) {
			assertThatNoException().isThrownBy(() -> SessionApiCompatibility.checkArtifactMarkers(loader));
		}
		assertThat(output).contains("built and tested against org.springaicommunity:spring-ai-session 0.8.x")
			.contains("found spring-ai-session 0.9.0");
	}

	@Test
	void missingOrUnreadableMarkersAreSkipped() throws IOException {
		// Shade filters often strip META-INF/maven; the signature probe still covers
		// that.
		Path blank = this.tmp.resolve("blank");
		Files.createDirectories(blank.resolve(SessionApiCompatibility.CURRENT_MARKER).getParent());
		Files.writeString(blank.resolve(SessionApiCompatibility.CURRENT_MARKER), "groupId=org.springaicommunity\n",
				StandardCharsets.ISO_8859_1);
		try (URLClassLoader empty = isolatedLoader(); URLClassLoader noVersion = isolatedLoader(blank)) {
			assertThatNoException().isThrownBy(() -> {
				SessionApiCompatibility.checkArtifactMarkers(empty);
				SessionApiCompatibility.checkArtifactMarkers(noVersion);
			});
		}
	}

	private Path marker(String dir, String resource, String version) throws IOException {
		Path root = this.tmp.resolve(dir);
		Path file = root.resolve(resource);
		Files.createDirectories(file.getParent());
		Files.writeString(file, "groupId=org.springaicommunity\nversion=" + version + "\n",
				StandardCharsets.ISO_8859_1);
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

	// Shape of the 0.5.0 SPI: findById returns Optional and compactEvents is absent.
	interface LegacyRepository {

		Optional<Session> findById(String sessionId);

		void replaceEvents(String sessionId, List<SessionEvent> events);

	}

	// Shape of a later SPI that adds an abstract method.
	interface NextRepository extends SessionRepository {

		void forkSession(String sourceSessionId, String targetSessionId);

	}

	// Shape of the 0.5.0 SessionEvent and EventFilter: no archive support.
	static final class LegacyEvent {

	}

	static final class LegacyFilter {

	}

}
