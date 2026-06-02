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

package org.springaicommunity.agentcore.otel;

import io.opentelemetry.api.baggage.Baggage;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.SpanProcessor;

/**
 * Propagates {@code session.id} from OTel baggage to span attributes. AgentCore sets the
 * session ID in baggage when invoking the runtime; this processor ensures it appears on
 * every span so the GenAI Observability dashboard can group traces by session.
 *
 * @author Maximilian Schellhorn
 */
class SessionBaggageSpanProcessor implements SpanProcessor {

	private static final String SESSION_ID_KEY = "session.id";

	private static final AttributeKey<String> SESSION_ID_ATTR = AttributeKey.stringKey(SESSION_ID_KEY);

	@Override
	public void onStart(Context parentContext, ReadWriteSpan span) {
		String sessionId = Baggage.fromContext(parentContext).getEntryValue(SESSION_ID_KEY);
		if (sessionId != null && !sessionId.isEmpty()) {
			span.setAttribute(SESSION_ID_ATTR, sessionId);
		}
	}

	@Override
	public boolean isStartRequired() {
		return true;
	}

	@Override
	public void onEnd(ReadableSpan span) {
	}

	@Override
	public boolean isEndRequired() {
		return false;
	}

}
