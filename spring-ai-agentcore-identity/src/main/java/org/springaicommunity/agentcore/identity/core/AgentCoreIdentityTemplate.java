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

import java.util.function.Consumer;

import org.jspecify.annotations.Nullable;
import software.amazon.awssdk.services.bedrockagentcore.BedrockAgentCoreClient;
import software.amazon.awssdk.services.bedrockagentcore.model.GetResourceOauth2TokenResponse;

import org.springframework.util.Assert;

/**
 * Template providing access to Amazon Bedrock AgentCore Identity operations.
 *
 * <p>
 * Supports three credential retrieval patterns:
 * <ul>
 * <li>Workload access token retrieval (from JWT, user ID, or workload name alone)</li>
 * <li>API key retrieval from a credential provider vault</li>
 * <li>OAuth 2.0 token retrieval ({@code USER_FEDERATION}, {@code M2M}, and
 * {@code ON_BEHALF_OF_TOKEN_EXCHANGE} flows)</li>
 * </ul>
 *
 * <p>
 * AWS service and client failures are intentionally propagated unchanged so callers can
 * inspect AWS error details, retryability, request IDs, and HTTP status codes. In a
 * reactive pipeline, invoke this blocking template on a bounded-elastic scheduler.
 *
 * @author Matej Nedic
 * @see <a href=
 * "https://docs.aws.amazon.com/bedrock-agentcore/latest/devguide/identity.html">AgentCore
 * Identity documentation</a>
 */
public class AgentCoreIdentityTemplate {

	private final BedrockAgentCoreClient client;

	private final @Nullable WorkloadAccessTokenHolder workloadAccessTokenHolder;

	public AgentCoreIdentityTemplate(BedrockAgentCoreClient client) {
		this(client, null);
	}

	public AgentCoreIdentityTemplate(BedrockAgentCoreClient client,
			@Nullable WorkloadAccessTokenHolder workloadAccessTokenHolder) {
		Assert.notNull(client, "BedrockAgentCoreClient must not be null");
		this.client = client;
		this.workloadAccessTokenHolder = workloadAccessTokenHolder;
	}

	/**
	 * Exchanges a user JWT for a workload access token. Use this when the caller provides
	 * a JWT identifying the end user (e.g., from Cognito or an external IdP). AgentCore
	 * validates the JWT signature and expiration, then binds the user identity to the
	 * resulting token.
	 * @param jwt the end-user JWT to exchange
	 * @param workloadName the registered workload identity name
	 * @return an opaque workload access token for accessing credential providers
	 */
	public String getWorkloadAccessTokenForJwt(String jwt, String workloadName) {
		Assert.hasText(jwt, "jwt must not be null or empty");
		Assert.hasText(workloadName, "workloadName must not be null or empty");
		return this.client.getWorkloadAccessTokenForJWT((r) -> r.userToken(jwt).workloadName(workloadName))
			.workloadAccessToken();
	}

	/**
	 * Retrieves a workload access token using a user ID string. Use this when no JWT is
	 * available but the caller can provide a unique user identifier. Partition user IDs
	 * by provider (e.g., {@code "cognito+user123"}) when using multiple identity
	 * providers.
	 * @param userId the unique identifier of the end user
	 * @param workloadName the registered workload identity name
	 * @return an opaque workload access token for accessing credential providers
	 */
	public String getWorkloadAccessTokenForUserId(String userId, String workloadName) {
		Assert.hasText(userId, "userId must not be null or empty");
		Assert.hasText(workloadName, "workloadName must not be null or empty");
		return this.client.getWorkloadAccessTokenForUserId((r) -> r.userId(userId).workloadName(workloadName).build())
			.workloadAccessToken();
	}

	/**
	 * Retrieves a workload access token using only the workload name. The caller's IAM
	 * identity is used to authenticate the agent.
	 * @param workloadName the registered workload identity name
	 * @return an opaque workload access token for accessing credential providers
	 */
	public String getWorkloadAccessToken(String workloadName) {
		Assert.hasText(workloadName, "workloadName must not be null or empty");
		return this.client.getWorkloadAccessToken((r) -> r.workloadName(workloadName).build()).workloadAccessToken();
	}

	/**
	 * Retrieves an API key from the AgentCore Identity credential vault. Requires a valid
	 * workload access token obtained via one of the {@code getWorkloadAccessToken}
	 * methods or automatically provided by AgentCore Runtime.
	 * @param workloadIdentityToken the workload access token authorizing this request
	 * @param resourceName the name of the API key credential provider
	 * @return the API key stored in the vault
	 */
	public String getApiKey(String workloadIdentityToken, String resourceName) {
		Assert.hasText(workloadIdentityToken, "workloadIdentityToken must not be null or empty");
		Assert.hasText(resourceName, "resourceName must not be null or empty");
		return this.client
			.getResourceApiKey((r) -> r.workloadIdentityToken(workloadIdentityToken)
				.resourceCredentialProviderName(resourceName)
				.build())
			.apiKey();
	}

	/**
	 * Retrieves an API key using the workload access token automatically captured from
	 * the AgentCore Runtime invocation headers. Requires the runtime starter on the
	 * classpath and a {@link WorkloadAccessTokenHolder} to be configured.
	 * @param resourceName the name of the API key credential provider
	 * @return the API key stored in the vault
	 * @throws IllegalStateException if no {@link WorkloadAccessTokenHolder} is configured
	 * or no token is available on the current thread
	 */
	public String getApiKey(String resourceName) {
		Assert.hasText(resourceName, "resourceName must not be null or empty");
		Assert.state(this.workloadAccessTokenHolder != null,
				"WorkloadAccessTokenHolder is not configured. Add the runtime starter dependency.");
		String token = this.workloadAccessTokenHolder.get();
		Assert.state(token != null, "No workload access token available on the current thread. "
				+ "Ensure this method is called within an @AgentCoreInvocation context.");
		return this.getApiKey(token, resourceName);
	}

	/**
	 * Retrieves the complete OAuth 2.0 response from AgentCore Identity. The response can
	 * contain an access token for completed M2M, on-behalf-of, or USER_FEDERATION flows.
	 * When user consent is required, it instead contains the authorization URL, session
	 * URI, and session status needed to complete the authorization flow.
	 * @param consumer configures all OAuth 2.0 request fields, including provider,
	 * scopes, flow, audience/resource targeting, callback URL, and CSRF state
	 * @return the complete AgentCore Identity OAuth 2.0 response
	 */
	public GetResourceOauth2TokenResponse getOauthToken(Consumer<GetResourceOauth2TokenConsumer> consumer) {
		Assert.notNull(consumer, "consumer must not be null");
		return this.client.getResourceOauth2Token(GetResourceOauth2TokenConsumer.of(consumer));
	}

	/**
	 * Retrieves only the OAuth 2.0 access token. This convenience method is intended for
	 * requests that resolve immediately, such as M2M and on-behalf-of token exchange. Use
	 * {@link #getOauthToken(Consumer)} when USER_FEDERATION can require authorization.
	 * @param consumer configures the OAuth 2.0 token request
	 * @return the OAuth 2.0 access token, or {@code null} when user authorization is
	 * required
	 */
	public @Nullable String getOauthAccessToken(Consumer<GetResourceOauth2TokenConsumer> consumer) {
		return this.getOauthToken(consumer).accessToken();
	}

	/**
	 * Completes the OAuth 2.0 authorization code flow after user consent. Call this after
	 * the user visits the authorization URL returned by the identity provider and is
	 * redirected back with the session URI.
	 * @param sessionUri the callback session URI received after user authorization
	 * @param userToken the end-user JWT (use when identifying user by JWT)
	 */
	public void completeResourceTokenAuth(String sessionUri, String userToken) {
		Assert.hasText(sessionUri, "sessionUri must not be null or empty");
		Assert.hasText(userToken, "userToken must not be null or empty");
		this.client
			.completeResourceTokenAuth((r) -> r.sessionUri(sessionUri).userIdentifier((u) -> u.userToken(userToken)));
	}

	/**
	 * Completes the OAuth 2.0 authorization code flow using a user ID instead of JWT.
	 * @param sessionUri the callback session URI received after user authorization
	 * @param userId the unique user identifier
	 */
	public void completeResourceTokenAuthForUserId(String sessionUri, String userId) {
		Assert.hasText(sessionUri, "sessionUri must not be null or empty");
		Assert.hasText(userId, "userId must not be null or empty");
		this.client.completeResourceTokenAuth((r) -> r.sessionUri(sessionUri).userIdentifier((u) -> u.userId(userId)));
	}

}
