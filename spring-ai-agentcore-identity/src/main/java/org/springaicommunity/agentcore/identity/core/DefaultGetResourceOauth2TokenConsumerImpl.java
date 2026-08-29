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

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import software.amazon.awssdk.services.bedrockagentcore.model.GetResourceOauth2TokenRequest;
import software.amazon.awssdk.services.bedrockagentcore.model.Oauth2FlowType;

import org.springframework.util.Assert;

/**
 * Default implementation of {@link GetResourceOauth2TokenConsumer}.
 *
 * @author Matej Nedic
 */
class DefaultGetResourceOauth2TokenConsumerImpl implements GetResourceOauth2TokenConsumer {

	private final List<Consumer<GetResourceOauth2TokenRequest.Builder>> customizers = new ArrayList<>();

	@Override
	public GetResourceOauth2TokenConsumer workloadIdentityToken(String workloadIdentityToken) {
		Assert.hasText(workloadIdentityToken, "workloadIdentityToken must not be null or empty");
		this.customizers.add((builder) -> builder.workloadIdentityToken(workloadIdentityToken));
		return this;
	}

	@Override
	public GetResourceOauth2TokenConsumer resourceCredentialProviderName(String resourceCredentialProviderName) {
		Assert.hasText(resourceCredentialProviderName, "resourceCredentialProviderName must not be null or empty");
		this.customizers.add((builder) -> builder.resourceCredentialProviderName(resourceCredentialProviderName));
		return this;
	}

	@Override
	public GetResourceOauth2TokenConsumer scopes(Collection<String> scopes) {
		Assert.notNull(scopes, "scopes must not be null");
		List<String> values = List.copyOf(scopes);
		this.customizers.add((builder) -> builder.scopes(values));
		return this;
	}

	@Override
	public GetResourceOauth2TokenConsumer oauth2Flow(Oauth2FlowType oauth2Flow) {
		Assert.notNull(oauth2Flow, "oauth2Flow must not be null");
		this.customizers.add((builder) -> builder.oauth2Flow(oauth2Flow));
		return this;
	}

	@Override
	public GetResourceOauth2TokenConsumer sessionUri(String sessionUri) {
		this.customizers.add((builder) -> builder.sessionUri(sessionUri));
		return this;
	}

	@Override
	public GetResourceOauth2TokenConsumer resourceOauth2ReturnUrl(String resourceOauth2ReturnUrl) {
		this.customizers.add((builder) -> builder.resourceOauth2ReturnUrl(resourceOauth2ReturnUrl));
		return this;
	}

	@Override
	public GetResourceOauth2TokenConsumer forceAuthentication(Boolean forceAuthentication) {
		this.customizers.add((builder) -> builder.forceAuthentication(forceAuthentication));
		return this;
	}

	@Override
	public GetResourceOauth2TokenConsumer customParameters(Map<String, String> customParameters) {
		Map<String, String> values = (customParameters != null) ? Map.copyOf(customParameters) : null;
		this.customizers.add((builder) -> builder.customParameters(values));
		return this;
	}

	@Override
	public GetResourceOauth2TokenConsumer customState(String customState) {
		Assert.hasText(customState, "customState must not be null or empty");
		this.customizers.add((builder) -> builder.customState(customState));
		return this;
	}

	@Override
	public GetResourceOauth2TokenConsumer resources(Collection<String> resources) {
		Assert.notNull(resources, "resources must not be null");
		List<String> values = List.copyOf(resources);
		this.customizers.add((builder) -> builder.resources(values));
		return this;
	}

	@Override
	public GetResourceOauth2TokenConsumer audiences(Collection<String> audiences) {
		Assert.notNull(audiences, "audiences must not be null");
		List<String> values = List.copyOf(audiences);
		this.customizers.add((builder) -> builder.audiences(values));
		return this;
	}

	@Override
	public void accept(GetResourceOauth2TokenRequest.Builder builder) {
		this.customizers.forEach((customizer) -> customizer.accept(builder));
	}

}
