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
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.ai.session.EventFilter;
import org.springframework.ai.session.SessionRepository;
import org.springframework.core.NativeDetector;

/**
 * Fail-fast check that the Session API on the classpath is the Spring AI one
 * {@link AgentCoreSessionRepository} is compiled against. Before Spring AI 2.1 the API
 * was the community artifact {@code org.springaicommunity:spring-ai-session} (earlier
 * {@code spring-ai-session-management}), which ships the same
 * {@code org.springframework.ai.session} packages with an incompatible API. With such a
 * jar still on the classpath the repository would load fine and fail on the first request
 * with an {@link AbstractMethodError} or {@link NoSuchMethodError}.
 *
 * <p>
 * Two checks run, once per class loader, from the repository constructor (so a repository
 * built through the public builder is covered too, not only the auto-configured bean):
 * <ul>
 * <li>A signature probe: every {@link SessionRepository} method must resolve to a
 * concrete {@link AgentCoreSessionRepository} method with a compatible return type, and
 * {@link EventFilter#applyWindow(List)} must exist. It catches an old jar and a mixed
 * split, including a shaded jar whose Maven markers were stripped.</li>
 * <li>A best-effort Maven marker scan: an unrelocated community artifact fails fast. A
 * relocated copy bundled inside another jar only logs a WARN, and missing markers are
 * skipped.</li>
 * </ul>
 * Both are skipped in a GraalVM native image, which is a closed world where a mismatch
 * already fails the image build.
 *
 * @author Spring AI Community
 */
final class SessionApiCompatibility {

	static final String REQUIRED_ARTIFACT = "org.springframework.ai:spring-ai-session (Spring AI 2.1)";

	/** Maven markers of the community artifacts that ship the same packages. */
	static final List<String> COMMUNITY_MARKERS = List.of(
			"META-INF/maven/org.springaicommunity/spring-ai-session/pom.properties",
			"META-INF/maven/org.springaicommunity/spring-ai-session-management/pom.properties");

	static final String SPI_CLASS_RESOURCE = "org/springframework/ai/session/SessionRepository.class";

	private static final String REMEDY = "Remove org.springaicommunity:spring-ai-session and"
			+ " org.springaicommunity:spring-ai-session-management (and the org.springaicommunity:spring-ai-session-bom"
			+ " import): Spring AI 2.1 ships the Session API as " + REQUIRED_ARTIFACT
			+ ", managed by org.springframework.ai:spring-ai-bom.";

	private static final String RELOCATED_NOTE = "the same classpath root does not ship"
			+ " org.springframework.ai.session.SessionRepository, so it is treated as a relocated copy and ignored";

	private static final Logger logger = LoggerFactory.getLogger(SessionApiCompatibility.class);

	// Set only after both checks pass, so a failing classpath keeps failing with the same
	// message on every construction instead of degrading into a NoClassDefFoundError
	// (which a throwing static initializer would cause).
	private static volatile boolean verified;

	private SessionApiCompatibility() {
	}

	static void verifyOnce() {
		if (verified) {
			return;
		}
		if (NativeDetector.inNativeImage()) {
			verified = true;
			return;
		}
		checkSignatures(SessionRepository.class, AgentCoreSessionRepository.class, EventFilter.class);
		ClassLoader loader = AgentCoreSessionRepository.class.getClassLoader();
		checkArtifactMarkers((loader != null) ? loader : ClassLoader.getSystemClassLoader());
		verified = true;
	}

	/**
	 * Package-private for tests.
	 * @return whether both checks have passed in this class loader
	 */
	static boolean isVerified() {
		return verified;
	}

	/**
	 * Package-private for tests.
	 */
	static void resetForTests() {
		verified = false;
	}

	static void checkSignatures(Class<?> repositorySpi, Class<?> implementation, Class<?> eventFilter) {
		List<String> problems = new ArrayList<>();
		// An SPI method the implementation cannot serve (an abstract method of another
		// API version, or a changed parameter or return type) would otherwise surface as
		// an AbstractMethodError on its first call.
		for (Method spi : repositorySpi.getMethods()) {
			if (Modifier.isStatic(spi.getModifiers())) {
				continue;
			}
			Method impl = findMethod(implementation, spi.getName(), spi.getParameterTypes());
			if (impl == null || Modifier.isAbstract(impl.getModifiers())
					|| !spi.getReturnType().isAssignableFrom(impl.getReturnType())) {
				problems.add(implementation.getSimpleName() + " does not implement " + describe(spi));
			}
		}
		if (findMethod(eventFilter, "applyWindow", List.class) == null) {
			problems.add(eventFilter.getSimpleName() + ".applyWindow(List) is missing");
		}
		if (!problems.isEmpty()) {
			throw new IllegalStateException("AgentCoreSessionRepository requires " + REQUIRED_ARTIFACT
					+ ", but the Session API on the classpath does not match it: " + String.join("; ", problems) + ". "
					+ REMEDY);
		}
	}

	static void checkArtifactMarkers(ClassLoader loader) {
		List<URL> spiClasses = resources(loader, SPI_CLASS_RESOURCE);
		List<URL> community = new ArrayList<>();
		for (String markerPath : COMMUNITY_MARKERS) {
			for (URL marker : resources(loader, markerPath)) {
				if (shipsSpiClass(marker, markerPath, spiClasses)) {
					community.add(marker);
				}
				else {
					logger.warn("Community Session API marker found at {}, but {}", marker, RELOCATED_NOTE);
				}
			}
		}
		if (!community.isEmpty()) {
			throw new IllegalStateException("The community Session API is on the classpath " + community
					+ ". It ships the same org.springframework.ai.session packages as Spring AI 2.1 with an"
					+ " incompatible API, so the two cannot be mixed. " + REMEDY);
		}
	}

	// maven-shade keeps META-INF/maven of relocated dependencies by default, so a marker
	// only conflicts when the same jar or directory also ships the unrelocated SPI class.
	// A marker URL that does not end with the marker path cannot be mapped to a root and
	// is treated as a conflict.
	private static boolean shipsSpiClass(URL marker, String markerPath, List<URL> spiClasses) {
		String markerUrl = marker.toString();
		if (!markerUrl.endsWith(markerPath)) {
			return true;
		}
		String spiUrl = markerUrl.substring(0, markerUrl.length() - markerPath.length()) + SPI_CLASS_RESOURCE;
		return spiClasses.stream().anyMatch((spi) -> spi.toString().equals(spiUrl));
	}

	private static String describe(Method method) {
		List<String> parameters = new ArrayList<>();
		for (Class<?> type : method.getParameterTypes()) {
			parameters.add(type.getSimpleName());
		}
		return method.getDeclaringClass().getSimpleName() + "." + method.getName() + "(" + String.join(", ", parameters)
				+ ")";
	}

	private static Method findMethod(Class<?> type, String name, Class<?>... parameterTypes) {
		try {
			return type.getMethod(name, parameterTypes);
		}
		catch (NoSuchMethodException ex) {
			return null;
		}
	}

	private static List<URL> resources(ClassLoader loader, String name) {
		try {
			return Collections.list(loader.getResources(name));
		}
		catch (IOException ex) {
			logger.debug("Could not scan the classpath for {}; skipping the check", name, ex);
			return List.of();
		}
	}

}
