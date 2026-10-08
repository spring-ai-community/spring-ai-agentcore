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
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Blockchain network identifiers used to pick one of the payment options a merchant
 * offers. Matches the defaults of the AgentCore Python SDK.
 *
 * @author Andrei Shakirin
 */
public final class NetworkPreferences {

	/**
	 * Default preference order, most preferred first: low-fee mainnets, then other
	 * mainnets, then test networks.
	 */
	public static final List<String> DEFAULT = List.of("solana-mainnet", "solana:5eykt4UsFv8P8NJdTREpY1vzqKqZKvdp",
			"solana:5eykt4UsFv8P8NJdTREpY1vzqKqZKvdpKuc147dw2N9d", "eip155:8453", "eip155:1", "base", "eip155:42161",
			"eip155:10", "ethereum", "solana-devnet", "solana:EtWTRABZaYq6iMfeYKouRu166VU2xqa1",
			"solana:EtWTRABZaYq6iMfeYKouRu166VU2xqa1wcaWoxPkrZBG", "solana-testnet",
			"solana:4uhcVJyU9pJkvQyS88uRDiswHXSCkY3z", "solana:4uhcVJyU9pJkvQyS88uRDiswHXSCkY3zQawwpjk2NsNY", "sepolia",
			"base-sepolia", "eip155:84532", "eip155:11155111");

	private static final Set<String> ETHEREUM_NETWORKS = lowerCase(Set.of("eip155:8453", "eip155:1", "base",
			"eip155:42161", "eip155:10", "ethereum", "sepolia", "base-sepolia", "eip155:84532", "eip155:11155111"));

	private static final Set<String> SOLANA_NETWORKS = lowerCase(Set.of("solana", "solana-mainnet",
			"solana:5eykt4UsFv8P8NJdTREpY1vzqKqZKvdp", "solana:5eykt4UsFv8P8NJdTREpY1vzqKqZKvdpKuc147dw2N9d",
			"solana-devnet", "solana:EtWTRABZaYq6iMfeYKouRu166VU2xqa1",
			"solana:EtWTRABZaYq6iMfeYKouRu166VU2xqa1wcaWoxPkrZBG", "solana-testnet",
			"solana:4uhcVJyU9pJkvQyS88uRDiswHXSCkY3z", "solana:4uhcVJyU9pJkvQyS88uRDiswHXSCkY3zQawwpjk2NsNY"));

	private NetworkPreferences() {
	}

	/**
	 * Returns the x402 network identifiers a payment instrument can pay on.
	 * @param instrumentNetwork instrument network, {@code ETHEREUM} or {@code SOLANA}
	 * @return lower-case network identifiers
	 */
	static Set<String> networksFor(String instrumentNetwork) {
		return switch (instrumentNetwork.toUpperCase(Locale.ROOT)) {
			case "ETHEREUM" -> ETHEREUM_NETWORKS;
			case "SOLANA" -> SOLANA_NETWORKS;
			default -> throw new PaymentException("Unsupported payment instrument network '" + instrumentNetwork
					+ "'. Supported networks are ETHEREUM and SOLANA.");
		};
	}

	private static Set<String> lowerCase(Set<String> networks) {
		return networks.stream().map((n) -> n.toLowerCase(Locale.ROOT)).collect(Collectors.toUnmodifiableSet());
	}

}
