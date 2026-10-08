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

package org.springaicommunity.agentcore.payments.autoconfiguration;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springaicommunity.agentcore.payments.core.PaymentContext;
import org.springaicommunity.agentcore.payments.tool.PaidHttpRequestTool;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests that the {@code paidHttpRequest} tool cannot reach local addresses.
 *
 * @author Andrei Shakirin
 */
class PaidHttpToolAddressFilterTests {

	private final AtomicInteger requests = new AtomicInteger();

	private HttpServer server;

	@BeforeEach
	void startServer() throws Exception {
		this.server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
		this.server.createContext("/", (exchange) -> {
			this.requests.incrementAndGet();
			exchange.sendResponseHeaders(200, -1);
			exchange.close();
		});
		this.server.start();
	}

	@AfterEach
	void stopServer() {
		this.server.stop(0);
	}

	@Test
	void httpToolCannotReachLocalAddresses() {
		PaidHttpRequestTool tool = new PaidHttpRequestTool(
				AgentCorePaymentsAutoConfiguration
					.paidHttpToolRestClient((request, body, execution) -> execution.execute(request, body)),
				(toolContext) -> new PaymentContext(null, null, null), 1000);
		int port = this.server.getAddress().getPort();

		String byName = tool
			.execute(new PaidHttpRequestTool.Request("http://localhost:" + port + "/", null, null, null), null);
		String byAddress = tool
			.execute(new PaidHttpRequestTool.Request("http://127.0.0.1:" + port + "/", null, null, null), null);

		assertThat(byName).startsWith("{\"statusCode\":0");
		assertThat(byAddress).startsWith("{\"statusCode\":0");
		assertThat(this.requests).hasValue(0);
	}

}
