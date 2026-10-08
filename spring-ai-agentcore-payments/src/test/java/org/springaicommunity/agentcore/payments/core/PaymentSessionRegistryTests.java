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

package org.springaicommunity.agentcore.payments.core;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.bedrockagentcore.model.PaymentSession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;

/**
 * Tests for {@link PaymentSessionRegistry}.
 *
 * @author Andrei Shakirin
 */
class PaymentSessionRegistryTests {

	private final AgentCorePaymentsTemplate payments = mock(AgentCorePaymentsTemplate.class);

	private final AtomicLong nanos = new AtomicLong();

	private final AtomicInteger created = new AtomicInteger();

	private final PaymentSessionRegistry registry = new PaymentSessionRegistry(this.payments, "1.00",
			Duration.ofMinutes(60), 100, this.nanos::get);

	@BeforeEach
	void createsNumberedSessions() {
		given(this.payments.createPaymentSession(anyString(), anyString(), anyInt()))
			.willAnswer((invocation) -> PaymentSession.builder()
				.paymentSessionId("ps-" + this.created.incrementAndGet())
				.build());
	}

	@Test
	void reusesSessionOfConversation() {
		String first = this.registry.getOrCreate("alice", "rt-1");
		String second = this.registry.getOrCreate("alice", "rt-1");

		assertThat(first).isEqualTo("ps-1").isEqualTo(second);
		then(this.payments).should().createPaymentSession("alice", "1.00", 60);
	}

	@Test
	void keepsSeparateSessionsPerUserConversationAndAlias() {
		assertThat(this.registry.getOrCreate("alice", "rt-1")).isEqualTo("ps-1");
		assertThat(this.registry.getOrCreate("alice", "rt-2")).isEqualTo("ps-2");
		assertThat(this.registry.getOrCreate("bob", "rt-1")).isEqualTo("ps-3");
		assertThat(this.registry.getOrCreate("alice", "rt-1", "booking", "5.00", Duration.ofMinutes(30)))
			.isEqualTo("ps-4");
		assertThat(this.registry.getOrCreate("alice", "rt-1")).isEqualTo("ps-1");
		then(this.payments).should().createPaymentSession("alice", "5.00", 30);
	}

	@Test
	void createsNewSessionShortlyBeforeExpiry() {
		this.registry.getOrCreate("alice", "rt-1");

		this.nanos.addAndGet(Duration.ofMinutes(58).toNanos());
		assertThat(this.registry.getOrCreate("alice", "rt-1")).isEqualTo("ps-1");

		this.nanos.addAndGet(Duration.ofMinutes(1).toNanos());
		assertThat(this.registry.getOrCreate("alice", "rt-1")).isEqualTo("ps-2");
	}

	@Test
	void renewReplacesBudgetAndRemoveForgetsConversation() {
		this.registry.getOrCreate("alice", "rt-1");
		this.registry.getOrCreate("alice", "rt-1", "booking");

		assertThat(this.registry.renew("alice", "rt-1", PaymentSessionRegistry.DEFAULT_ALIAS)).isEqualTo("ps-3");
		assertThat(this.registry.getOrCreate("alice", "rt-1")).isEqualTo("ps-3");

		this.registry.remove("alice", "rt-1");
		assertThat(this.registry.getOrCreate("alice", "rt-1", "booking")).isEqualTo("ps-4");
	}

	@Test
	void doesNotKeepSessionThatServiceReportsAsAlreadyExpired() {
		willAnswer((invocation) -> PaymentSession.builder()
			.paymentSessionId("ps-" + this.created.incrementAndGet())
			.createdAt(Instant.now().minus(Duration.ofHours(2)))
			.expiryTimeInMinutes(60)
			.build()).given(this.payments).createPaymentSession(anyString(), anyString(), anyInt());

		assertThat(this.registry.getOrCreate("alice", "rt-1")).isEqualTo("ps-1");
		assertThat(this.registry.getOrCreate("alice", "rt-1")).isEqualTo("ps-2");
	}

	@Test
	void rejectsInvalidBudget() {
		assertThatIllegalArgumentException()
			.isThrownBy(() -> new PaymentSessionRegistry(this.payments, "one dollar", Duration.ofMinutes(60), 10));
		assertThatIllegalArgumentException()
			.isThrownBy(() -> new PaymentSessionRegistry(this.payments, "0.00", Duration.ofMinutes(60), 10));
	}

	@Test
	void rejectsExpiryOutsideServiceLimits() {
		assertThatIllegalArgumentException()
			.isThrownBy(() -> this.registry.getOrCreate("alice", "rt-1", "x", "1.00", Duration.ofMinutes(10)));
		then(this.payments).should(never()).createPaymentSession(anyString(), anyString(), anyInt());
	}

}
