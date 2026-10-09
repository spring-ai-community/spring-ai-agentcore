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

package org.springaicommunity.agentcore.payments.tool;

import java.util.Map;

import org.jspecify.annotations.Nullable;
import org.springaicommunity.agentcore.payments.core.PaymentContext;

import org.springframework.ai.chat.model.ToolContext;

/**
 * Uses fixed defaults, typically from {@code agentcore.payments.*} properties, and lets
 * tool context entries ({@link PaymentContext#USER_ID_KEY},
 * {@link PaymentContext#PAYMENT_INSTRUMENT_ID_KEY},
 * {@link PaymentContext#PAYMENT_SESSION_ID_KEY}) override them per request.
 *
 * @author Andrei Shakirin
 */
public class DefaultPaymentContextResolver implements PaymentContextResolver {

	private final PaymentContext defaults;

	public DefaultPaymentContextResolver(PaymentContext defaults) {
		this.defaults = defaults;
	}

	@Override
	public PaymentContext resolve(@Nullable ToolContext toolContext) {
		if (toolContext == null) {
			return this.defaults;
		}
		Map<String, Object> context = toolContext.getContext();
		return new PaymentContext(value(context, PaymentContext.USER_ID_KEY, this.defaults.userId()),
				value(context, PaymentContext.PAYMENT_INSTRUMENT_ID_KEY, this.defaults.paymentInstrumentId()),
				value(context, PaymentContext.PAYMENT_SESSION_ID_KEY, this.defaults.paymentSessionId()));
	}

	private static @Nullable String value(Map<String, Object> context, String key, @Nullable String defaultValue) {
		Object value = context.get(key);
		return (value != null) ? value.toString() : defaultValue;
	}

}
