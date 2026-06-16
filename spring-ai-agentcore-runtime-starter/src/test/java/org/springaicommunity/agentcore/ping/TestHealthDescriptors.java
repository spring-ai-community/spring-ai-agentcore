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

package org.springaicommunity.agentcore.ping;

import java.lang.reflect.Constructor;

import org.springframework.boot.health.actuate.endpoint.HealthDescriptor;
import org.springframework.boot.health.actuate.endpoint.IndicatedHealthDescriptor;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;

/**
 * Test-only helper that constructs real {@link HealthDescriptor} instances for use in
 * tests.
 *
 * <p>
 * Spring Boot 4 introduced a sealed {@link HealthDescriptor} hierarchy whose concrete
 * subclasses (e.g. {@link IndicatedHealthDescriptor}) only have package-private
 * constructors. Sealed types cannot be mocked with Mockito, so this helper uses
 * reflection to call the package-private constructor of {@link IndicatedHealthDescriptor}
 * with a {@link Health} instance carrying the desired {@link Status}.
 */
public final class TestHealthDescriptors {

	private TestHealthDescriptors() {
	}

	public static HealthDescriptor of(Status status) {
		Health health = Health.status(status).build();
		try {
			Constructor<IndicatedHealthDescriptor> ctor = IndicatedHealthDescriptor.class
				.getDeclaredConstructor(Health.class);
			ctor.setAccessible(true);
			return ctor.newInstance(health);
		}
		catch (ReflectiveOperationException ex) {
			throw new IllegalStateException("Failed to instantiate IndicatedHealthDescriptor for tests", ex);
		}
	}

}
