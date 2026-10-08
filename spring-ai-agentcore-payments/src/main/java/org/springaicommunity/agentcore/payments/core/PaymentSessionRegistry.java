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

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import com.github.benmanes.caffeine.cache.Ticker;
import software.amazon.awssdk.services.bedrockagentcore.model.PaymentSession;

import org.springframework.util.Assert;

/**
 * Remembers the payment sessions (budgets) of conversations, so that all invocations of a
 * conversation pay from the same budget. Sessions are only created by explicit calls of
 * {@link #getOrCreate} and {@link #renew}, never while paying.
 * <p>
 * Sessions are keyed by user, AgentCore Runtime session and an alias, so a conversation
 * can hold several budgets (for example {@code "research"} and {@code "booking"}) and
 * parallel conversations never share one. An entry is dropped shortly before its payment
 * session expires; the number of entries is bounded. The registry is in memory per
 * application instance, which fits AgentCore Runtime where a runtime session stays on one
 * instance.
 *
 * @author Andrei Shakirin
 */
public class PaymentSessionRegistry {

	/** Alias of the budget used when none is given. */
	public static final String DEFAULT_ALIAS = "default";

	/** Entries are dropped this long before their payment session expires. */
	static final Duration EXPIRY_MARGIN = Duration.ofMinutes(1);

	private static final Duration MIN_EXPIRY = Duration.ofMinutes(15);

	private static final Duration MAX_EXPIRY = Duration.ofMinutes(480);

	private final AgentCorePaymentsTemplate payments;

	private final String defaultMaxSpendUsd;

	private final Duration defaultExpiry;

	private final Cache<Key, Entry> sessions;

	/**
	 * Creates a registry.
	 * @param payments the payments template used to create sessions
	 * @param defaultMaxSpendUsd the budget of new sessions in USD, for example
	 * {@code "1.00"}
	 * @param defaultExpiry the lifetime of new sessions, 15 to 480 minutes
	 * @param maximumSize the maximum number of remembered sessions
	 */
	public PaymentSessionRegistry(AgentCorePaymentsTemplate payments, String defaultMaxSpendUsd, Duration defaultExpiry,
			long maximumSize) {
		this(payments, defaultMaxSpendUsd, defaultExpiry, maximumSize, Ticker.systemTicker());
	}

	PaymentSessionRegistry(AgentCorePaymentsTemplate payments, String defaultMaxSpendUsd, Duration defaultExpiry,
			long maximumSize, Ticker ticker) {
		Assert.notNull(payments, "payments must not be null");
		Assert.hasText(defaultMaxSpendUsd, "defaultMaxSpendUsd must not be empty");
		validateExpiry(defaultExpiry);
		this.payments = payments;
		this.defaultMaxSpendUsd = defaultMaxSpendUsd;
		this.defaultExpiry = defaultExpiry;
		this.sessions = Caffeine.newBuilder()
			.maximumSize(maximumSize)
			.expireAfter(new EntryExpiry())
			.ticker(ticker)
			.build();
	}

	/**
	 * Returns the default budget of a conversation, creating it with the default limits
	 * if there is none or it expired.
	 * @param userId the user who pays
	 * @param runtimeSessionId the AgentCore Runtime session of the conversation
	 * @return the payment session id
	 */
	public String getOrCreate(String userId, String runtimeSessionId) {
		return this.getOrCreate(userId, runtimeSessionId, DEFAULT_ALIAS);
	}

	/**
	 * Returns a named budget of a conversation, creating it with the default limits if
	 * there is none or it expired.
	 * @param userId the user who pays
	 * @param runtimeSessionId the AgentCore Runtime session of the conversation
	 * @param alias the name of the budget within the conversation
	 * @return the payment session id
	 */
	public String getOrCreate(String userId, String runtimeSessionId, String alias) {
		return this.getOrCreate(userId, runtimeSessionId, alias, this.defaultMaxSpendUsd, this.defaultExpiry);
	}

	/**
	 * Returns a named budget of a conversation, creating it with the given limits if
	 * there is none or it expired.
	 * @param userId the user who pays
	 * @param runtimeSessionId the AgentCore Runtime session of the conversation
	 * @param alias the name of the budget within the conversation
	 * @param maxSpendUsd the budget in USD if a session is created
	 * @param expiry the lifetime if a session is created, 15 to 480 minutes
	 * @return the payment session id
	 */
	public String getOrCreate(String userId, String runtimeSessionId, String alias, String maxSpendUsd,
			Duration expiry) {
		Key key = Key.of(userId, runtimeSessionId, alias);
		validateExpiry(expiry);
		return this.sessions.get(key, (k) -> this.create(k, maxSpendUsd, expiry)).paymentSessionId();
	}

	/**
	 * Replaces a named budget of a conversation by a new session with the default limits,
	 * for example after the user approved more spending.
	 * @param userId the user who pays
	 * @param runtimeSessionId the AgentCore Runtime session of the conversation
	 * @param alias the name of the budget within the conversation
	 * @return the new payment session id
	 */
	public String renew(String userId, String runtimeSessionId, String alias) {
		return this.renew(userId, runtimeSessionId, alias, this.defaultMaxSpendUsd, this.defaultExpiry);
	}

	/**
	 * Replaces a named budget of a conversation by a new session with the given limits.
	 * @param userId the user who pays
	 * @param runtimeSessionId the AgentCore Runtime session of the conversation
	 * @param alias the name of the budget within the conversation
	 * @param maxSpendUsd the budget of the new session in USD
	 * @param expiry the lifetime of the new session, 15 to 480 minutes
	 * @return the new payment session id
	 */
	public String renew(String userId, String runtimeSessionId, String alias, String maxSpendUsd, Duration expiry) {
		Key key = Key.of(userId, runtimeSessionId, alias);
		validateExpiry(expiry);
		Entry entry = this.sessions.asMap().compute(key, (k, previous) -> this.create(k, maxSpendUsd, expiry));
		return entry.paymentSessionId();
	}

	/**
	 * Forgets all budgets of a conversation, for example when it ends. The payment
	 * sessions themselves expire in AgentCore Payments.
	 * @param userId the user
	 * @param runtimeSessionId the AgentCore Runtime session of the conversation
	 */
	public void remove(String userId, String runtimeSessionId) {
		this.sessions.asMap()
			.keySet()
			.removeIf((key) -> key.userId().equals(userId) && key.runtimeSessionId().equals(runtimeSessionId));
	}

	private Entry create(Key key, String maxSpendUsd, Duration expiry) {
		PaymentSession session = this.payments.createPaymentSession(key.userId(), maxSpendUsd,
				(int) expiry.toMinutes());
		return new Entry(session.paymentSessionId(), lifetime(session, expiry).minus(EXPIRY_MARGIN));
	}

	// Remaining lifetime of a new session: from the service's creation time and expiry
	// when available, otherwise the requested expiry.
	private static Duration lifetime(PaymentSession session, Duration requestedExpiry) {
		if (session.createdAt() == null || session.expiryTimeInMinutes() == null) {
			return requestedExpiry;
		}
		Instant expiresAt = session.createdAt().plus(Duration.ofMinutes(session.expiryTimeInMinutes()));
		Duration remaining = Duration.between(Instant.now(), expiresAt);
		return (remaining.compareTo(requestedExpiry) < 0) ? remaining : requestedExpiry;
	}

	private static void validateExpiry(Duration expiry) {
		Assert.notNull(expiry, "expiry must not be null");
		Assert.isTrue(expiry.compareTo(MIN_EXPIRY) >= 0 && expiry.compareTo(MAX_EXPIRY) <= 0,
				"expiry must be between 15 and 480 minutes");
	}

	private record Key(String userId, String runtimeSessionId, String alias) {

		static Key of(String userId, String runtimeSessionId, String alias) {
			Assert.hasText(userId, "userId must not be empty");
			Assert.hasText(runtimeSessionId, "runtimeSessionId must not be empty");
			Assert.hasText(alias, "alias must not be empty");
			return new Key(userId, runtimeSessionId, alias);
		}

	}

	private record Entry(String paymentSessionId, Duration lifetime) {
	}

	private static final class EntryExpiry implements Expiry<Key, Entry> {

		@Override
		public long expireAfterCreate(Key key, Entry entry, long currentTime) {
			return entry.lifetime().toNanos();
		}

		@Override
		public long expireAfterUpdate(Key key, Entry entry, long currentTime, long currentDuration) {
			return entry.lifetime().toNanos();
		}

		@Override
		public long expireAfterRead(Key key, Entry entry, long currentTime, long currentDuration) {
			return currentDuration;
		}

	}

}
