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
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.ai.session.EventFilter;
import org.springframework.ai.session.Session;
import org.springframework.ai.session.SessionEvent;
import org.springframework.ai.session.SessionRepository;
import org.springframework.core.NativeDetector;

/**
 * Fail-fast check that the spring-ai-session API on the classpath is the one
 * {@link AgentCoreSessionRepository} is compiled against. spring-ai-session is pre-1.0
 * and every minor release so far has changed the {@link SessionRepository} SPI; with a
 * stale or mixed classpath the repository would otherwise load fine and fail on the first
 * request with an {@link AbstractMethodError} or {@link NoSuchMethodError}.
 *
 * <p>
 * Two checks run, once per class loader, from the repository constructor (so a repository
 * built through the public builder is covered too, not only the auto-configured bean):
 * <ul>
 * <li>A signature probe: every {@link SessionRepository} method must resolve to a
 * concrete {@link AgentCoreSessionRepository} method with a compatible return type, and
 * {@link SessionEvent} and {@link EventFilter} must expose the 0.8.0 archive members. It
 * catches an old jar, a newer SPI and a mixed split, including a shaded jar whose Maven
 * markers were stripped.</li>
 * <li>A best-effort Maven marker scan. The renamed {@code spring-ai-session-management}
 * artifact carries the same packages, so an unrelocated copy of it fails fast, as do two
 * different {@code spring-ai-session} versions. A relocated copy bundled inside another
 * jar, the same version seen twice (for example through a parent and a child class
 * loader), and a version outside the supported line only log a WARN, and missing markers
 * are skipped.</li>
 * </ul>
 * Both are skipped in a GraalVM native image, which is a closed world where a mismatch
 * already fails the image build.
 *
 * @author Spring AI Community
 */
final class SessionApiCompatibility {

	/**
	 * Version prefix of the spring-ai-session line this module is built and tested for.
	 */
	static final String SUPPORTED_VERSION_LINE = "0.8.";

	/**
	 * {@link #SUPPORTED_VERSION_LINE} as written in every message, the configuration
	 * metadata and the READMEs.
	 */
	static final String SUPPORTED_VERSIONS = SUPPORTED_VERSION_LINE + "x";

	static final String REQUIRED_ARTIFACT = "org.springaicommunity:spring-ai-session " + SUPPORTED_VERSIONS;

	static final String LEGACY_MARKER = "META-INF/maven/org.springaicommunity/spring-ai-session-management/pom.properties";

	static final String CURRENT_MARKER = "META-INF/maven/org.springaicommunity/spring-ai-session/pom.properties";

	static final String SPI_CLASS_RESOURCE = "org/springframework/ai/session/SessionRepository.class";

	private static final String REMEDY = "Use " + REQUIRED_ARTIFACT + " (import"
			+ " org.springaicommunity:spring-ai-session-bom " + SUPPORTED_VERSION_LINE + "0 or a later "
			+ SUPPORTED_VERSIONS + "). If org.springaicommunity:spring-ai-session-management is also on the"
			+ " classpath, remove it.";

	private static final String RELOCATED_NOTE = "the same classpath root does not ship"
			+ " org.springframework.ai.session.SessionRepository, so it is treated as a relocated copy and ignored";

	private static final String DUPLICATE_NOTE = "this is harmless while the versions agree, but usually points"
			+ " at a duplicated jar";

	private static final String UNSUPPORTED_LINE_NOTE = "Pre-1.0 minor releases have changed the SessionRepository"
			+ " SPI; check the release notes before relying on this combination";

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
		checkSignatures(SessionRepository.class, AgentCoreSessionRepository.class, SessionEvent.class,
				EventFilter.class);
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

	static void checkSignatures(Class<?> repositorySpi, Class<?> implementation, Class<?> sessionEvent,
			Class<?> eventFilter) {
		List<String> problems = new ArrayList<>();
		Method findById = findMethod(repositorySpi, "findById", String.class);
		if (findById == null) {
			problems.add(repositorySpi.getSimpleName() + ".findById(String) is missing");
		}
		else if (findById.getReturnType() != Session.class) {
			problems.add(repositorySpi.getSimpleName() + ".findById(String) returns "
					+ findById.getReturnType().getName() + " instead of " + Session.class.getName());
		}
		if (findMethod(repositorySpi, "compactEvents", String.class, List.class, List.class, long.class) == null) {
			problems.add(repositorySpi.getSimpleName() + ".compactEvents(String, List, List, long) is missing");
		}
		// An SPI method the implementation cannot serve (an abstract method added by a
		// newer
		// release, or a changed parameter or return type) would otherwise surface as an
		// AbstractMethodError on its first call.
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
		if (findMethod(sessionEvent, "isArchived") == null) {
			problems.add(sessionEvent.getSimpleName() + ".isArchived() is missing");
		}
		Method active = findMethod(eventFilter, "active");
		if (active == null || !Modifier.isStatic(active.getModifiers())) {
			problems.add(eventFilter.getSimpleName() + ".active() is missing");
		}
		if (!problems.isEmpty()) {
			throw new IllegalStateException("AgentCoreSessionRepository requires " + REQUIRED_ARTIFACT
					+ ", but the Session API on the classpath does not match it: " + String.join("; ", problems) + ". "
					+ REMEDY);
		}
	}

	static void checkArtifactMarkers(ClassLoader loader) {
		List<URL> spiClasses = resources(loader, SPI_CLASS_RESOURCE);
		List<URL> legacy = new ArrayList<>();
		for (URL marker : resources(loader, LEGACY_MARKER)) {
			if (shipsSpiClass(marker, spiClasses)) {
				legacy.add(marker);
			}
			else {
				logger.warn("org.springaicommunity:spring-ai-session-management marker found at {}, but {}", marker,
						RELOCATED_NOTE);
			}
		}
		if (!legacy.isEmpty()) {
			throw new IllegalStateException("org.springaicommunity:spring-ai-session-management is on the classpath "
					+ legacy + ". It was renamed to spring-ai-session in 0.6.0 and ships the same packages with an"
					+ " incompatible API, so the two cannot be mixed. Remove"
					+ " org.springaicommunity:spring-ai-session-management; AgentCoreSessionRepository requires "
					+ REQUIRED_ARTIFACT + ".");
		}
		Map<String, List<URL>> byVersion = new TreeMap<>();
		for (URL url : resources(loader, CURRENT_MARKER)) {
			String version = readVersion(url);
			if (version != null) {
				byVersion.computeIfAbsent(version, (v) -> new ArrayList<>()).add(url);
			}
		}
		if (byVersion.size() > 1) {
			throw new IllegalStateException("Several versions of org.springaicommunity:spring-ai-session are on"
					+ " the classpath %s. Keep exactly one; AgentCoreSessionRepository requires %s."
						.formatted(byVersion, REQUIRED_ARTIFACT));
		}
		byVersion.forEach((version, urls) -> {
			if (urls.size() > 1) {
				logger.warn("org.springaicommunity:spring-ai-session {} is on the classpath {} times {}; {}", version,
						urls.size(), urls, DUPLICATE_NOTE);
			}
			if (!version.startsWith(SUPPORTED_VERSION_LINE)) {
				logger.warn("AgentCoreSessionRepository is built and tested against {}, but found"
						+ " spring-ai-session {}. {}", REQUIRED_ARTIFACT, version, UNSUPPORTED_LINE_NOTE);
			}
		});
	}

	// maven-shade keeps META-INF/maven of relocated dependencies by default, so a legacy
	// marker only conflicts when the same jar or directory also ships the unrelocated
	// SPI class. A marker URL that does not end with the marker path cannot be mapped to
	// a root and is treated as a conflict.
	private static boolean shipsSpiClass(URL marker, List<URL> spiClasses) {
		String markerUrl = marker.toString();
		if (!markerUrl.endsWith(LEGACY_MARKER)) {
			return true;
		}
		String spiUrl = markerUrl.substring(0, markerUrl.length() - LEGACY_MARKER.length()) + SPI_CLASS_RESOURCE;
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

	private static String readVersion(URL url) {
		Properties properties = new Properties();
		try (InputStream in = url.openStream()) {
			properties.load(in);
		}
		catch (IOException ex) {
			logger.debug("Could not read {}; skipping it", url, ex);
			return null;
		}
		String version = properties.getProperty("version");
		return (version != null && !version.isBlank()) ? version.trim() : null;
	}

}
