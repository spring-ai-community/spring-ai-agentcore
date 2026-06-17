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

package org.springaicommunity.agentcore.observability;

import java.io.IOException;

import io.opentelemetry.api.baggage.Baggage;
import io.opentelemetry.context.Scope;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springaicommunity.agentcore.context.AgentCoreHeaders;

/**
 * Servlet filter that reads the AgentCore session header and injects it into OTel Baggage
 * as {@code session.id}.
 *
 * <p>
 * This completes the observability circuit: the filter writes {@code session.id} into
 * baggage, and downstream span processors (e.g. {@code SessionBaggageSpanProcessor} from
 * the OTel extension) read it back to enrich span attributes.
 *
 * <p>
 * Downstream HTTP calls made within the filter scope will automatically propagate the
 * baggage via the W3C {@code baggage} header when {@code W3CBaggagePropagator} is
 * configured.
 *
 * @author Vaquar Khan
 * @see AgentCoreHeaders#SESSION_ID
 */
public final class AgentCoreSessionBaggageFilter implements Filter {

	/** OTel Baggage key for the AgentCore session identifier. */
	static final String BAGGAGE_KEY = "session.id";

	@Override
	public void doFilter(final ServletRequest request, final ServletResponse response, final FilterChain chain)
			throws IOException, ServletException {
		if (request instanceof HttpServletRequest httpRequest) {
			String sessionId = httpRequest.getHeader(AgentCoreHeaders.SESSION_ID);
			if (sessionId != null && !sessionId.isBlank()) {
				Baggage baggage = Baggage.current().toBuilder().put(BAGGAGE_KEY, sessionId).build();
				try (Scope ignored = baggage.makeCurrent()) {
					chain.doFilter(request, response);
				}
				return;
			}
		}
		chain.doFilter(request, response);
	}

}
