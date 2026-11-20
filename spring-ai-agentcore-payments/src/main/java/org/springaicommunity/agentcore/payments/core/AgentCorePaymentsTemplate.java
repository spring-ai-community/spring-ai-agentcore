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

import java.util.List;

import org.jspecify.annotations.Nullable;
import software.amazon.awssdk.services.bedrockagentcore.BedrockAgentCoreClient;

/**
 * Former name of {@link AgentCorePaymentsClient}.
 *
 * @author Andrei Shakirin
 * @deprecated Use {@link AgentCorePaymentsClient}. Removal scheduled for 3.0.0.
 */
@Deprecated(since = "2.4.0", forRemoval = true)
public class AgentCorePaymentsTemplate extends AgentCorePaymentsClient {

	/**
	 * Creates a client with the default network preferences.
	 * @param client the AgentCore data plane client
	 * @param paymentManagerArn the payment manager ARN
	 */
	public AgentCorePaymentsTemplate(BedrockAgentCoreClient client, String paymentManagerArn) {
		super(client, paymentManagerArn);
	}

	/**
	 * Creates a client.
	 * @param client the AgentCore data plane client
	 * @param paymentManagerArn the payment manager ARN
	 * @param agentName agent name sent with data plane calls, may be {@code null}
	 * @param networkPreferences network identifiers used to choose between payment
	 * options, most preferred first
	 * @param permit2AllowanceLimit the Permit2 allowance for the x402 {@code upto}
	 * scheme, in the asset's smallest unit; {@code null} to not grant one
	 */
	public AgentCorePaymentsTemplate(BedrockAgentCoreClient client, String paymentManagerArn,
			@Nullable String agentName, List<String> networkPreferences, @Nullable String permit2AllowanceLimit) {
		super(client, paymentManagerArn, agentName, networkPreferences, permit2AllowanceLimit);
	}

}
