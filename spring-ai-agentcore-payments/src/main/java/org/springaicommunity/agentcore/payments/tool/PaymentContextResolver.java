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

import org.jspecify.annotations.Nullable;
import org.springaicommunity.agentcore.payments.core.PaymentContext;

import org.springframework.ai.chat.model.ToolContext;

/**
 * Resolves the user, payment instrument and payment session for a tool call. Define a
 * bean to look them up per user or conversation.
 *
 * @author Andrei Shakirin
 */
@FunctionalInterface
public interface PaymentContextResolver {

	/**
	 * Resolves the payment context of a tool call.
	 * @param toolContext the tool context of the call, may be {@code null}
	 * @return the payment context; missing values fail when a payment is attempted
	 */
	PaymentContext resolve(@Nullable ToolContext toolContext);

}
