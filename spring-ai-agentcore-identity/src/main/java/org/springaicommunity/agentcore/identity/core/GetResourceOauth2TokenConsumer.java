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

package org.springaicommunity.agentcore.identity.core;

import java.util.Arrays;
import java.util.Collection;
import java.util.Map;
import java.util.function.Consumer;

import software.amazon.awssdk.services.bedrockagentcore.model.GetResourceOauth2TokenRequest;
import software.amazon.awssdk.services.bedrockagentcore.model.Oauth2FlowType;

import org.springframework.util.Assert;

/**
 * Fluent consumer for configuring an AgentCore Identity OAuth 2.0 token request.
 *
 * @author Matej Nedic
 */
public interface GetResourceOauth2TokenConsumer extends Consumer<GetResourceOauth2TokenRequest.Builder> {

	/**
	 * Sets the workload identity token that authorizes credential retrieval.
	 * @param workloadIdentityToken workload access token
	 * @return this consumer
	 */
	GetResourceOauth2TokenConsumer workloadIdentityToken(String workloadIdentityToken);

	/**
	 * Sets the resource credential provider configured in AgentCore Identity.
	 * @param resourceCredentialProviderName credential-provider name
	 * @return this consumer
	 */
	GetResourceOauth2TokenConsumer resourceCredentialProviderName(String resourceCredentialProviderName);

	/**
	 * Sets the OAuth scopes requested from the resource provider.
	 * @param scopes requested scopes
	 * @return this consumer
	 */
	GetResourceOauth2TokenConsumer scopes(Collection<String> scopes);

	/**
	 * Sets the OAuth scopes requested from the resource provider.
	 * @param scopes requested scopes
	 * @return this consumer
	 */
	default GetResourceOauth2TokenConsumer scopes(String... scopes) {
		return this.scopes(Arrays.asList(scopes));
	}

	/**
	 * Sets the OAuth flow, such as M2M, user federation, or on-behalf-of exchange.
	 * @param oauth2Flow oauth flow
	 * @return this consumer
	 */
	GetResourceOauth2TokenConsumer oauth2Flow(Oauth2FlowType oauth2Flow);

	/**
	 * Sets the authorization session URI when continuing a USER_FEDERATION flow.
	 * @param sessionUri authorization session URI
	 * @return this consumer
	 */
	GetResourceOauth2TokenConsumer sessionUri(String sessionUri);

	/**
	 * Sets the registered browser return URL for USER_FEDERATION authorization.
	 * @param resourceOauth2ReturnUrl oauth return URL
	 * @return this consumer
	 */
	GetResourceOauth2TokenConsumer resourceOauth2ReturnUrl(String resourceOauth2ReturnUrl);

	/**
	 * Controls whether the resource provider must authenticate the user again.
	 * @param forceAuthentication whether authentication should be forced
	 * @return this consumer
	 */
	GetResourceOauth2TokenConsumer forceAuthentication(Boolean forceAuthentication);

	/**
	 * Sets provider-specific authorization parameters that do not override standard OAuth
	 * parameters.
	 * @param customParameters custom authorization parameters
	 * @return this consumer
	 */
	GetResourceOauth2TokenConsumer customParameters(Map<String, String> customParameters);

	/**
	 * Sets opaque caller state for correlating and protecting USER_FEDERATION callbacks.
	 * Callers must validate the returned state before completing authorization.
	 * @param customState csrf/correlation state
	 * @return this consumer
	 */
	GetResourceOauth2TokenConsumer customState(String customState);

	/**
	 * Sets resource indicators for on-behalf-of token exchange.
	 * @param resources target resources
	 * @return this consumer
	 */
	GetResourceOauth2TokenConsumer resources(Collection<String> resources);

	/**
	 * Sets resource indicators for on-behalf-of token exchange.
	 * @param resources target resources
	 * @return this consumer
	 */
	default GetResourceOauth2TokenConsumer resources(String... resources) {
		return this.resources(Arrays.asList(resources));
	}

	/**
	 * Sets target audiences for on-behalf-of token exchange.
	 * @param audiences target audiences
	 * @return this consumer
	 */
	GetResourceOauth2TokenConsumer audiences(Collection<String> audiences);

	/**
	 * Sets target audiences for on-behalf-of token exchange.
	 * @param audiences target audiences
	 * @return this consumer
	 */
	default GetResourceOauth2TokenConsumer audiences(String... audiences) {
		return this.audiences(Arrays.asList(audiences));
	}

	/**
	 * Creates a request consumer from the fluent configuration callback.
	 * @param consumer request configuration callback
	 * @return configured AWS request consumer
	 */
	static GetResourceOauth2TokenConsumer of(Consumer<GetResourceOauth2TokenConsumer> consumer) {
		Assert.notNull(consumer, "consumer must not be null");
		DefaultGetResourceOauth2TokenConsumerImpl spec = new DefaultGetResourceOauth2TokenConsumerImpl();
		consumer.accept(spec);
		return spec;
	}

}
