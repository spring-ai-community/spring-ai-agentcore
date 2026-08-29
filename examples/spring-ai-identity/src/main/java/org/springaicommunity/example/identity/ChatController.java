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
package org.springaicommunity.example.identity;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springaicommunity.agentcore.identity.core.AgentCoreIdentityTemplate;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import software.amazon.awssdk.services.bedrockagentcore.model.GetResourceOauth2TokenResponse;
import software.amazon.awssdk.services.bedrockagentcore.model.Oauth2FlowType;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class ChatController {

	private static final Duration AUTHORIZATION_SESSION_TTL = Duration.ofMinutes(10);

	private static final int MAX_PENDING_AUTHORIZATIONS = 10_000;

	private final AgentCoreIdentityTemplate identityTemplate;

	private final String workloadName;

	private final String resourceCredentialProviderName;

	private final String[] oauthScopes;

	private final String oauthReturnUrl;

	private final Map<String, PendingAuthorization> pendingAuthorizations = new ConcurrentHashMap<>();

	public ChatController(AgentCoreIdentityTemplate identityTemplate, @Value("${app.workload-name}") String workloadName,
			@Value("${app.resource-credential-provider-name}") String resourceCredentialProviderName,
			@Value("${app.oauth2-scopes}") String[] oauthScopes,
			@Value("${app.oauth2-return-url}") String oauthReturnUrl) {
		this.identityTemplate = identityTemplate;
		this.workloadName = workloadName;
		this.resourceCredentialProviderName = resourceCredentialProviderName;
		this.oauthScopes = oauthScopes;
		this.oauthReturnUrl = oauthReturnUrl;
	}

	@PostMapping("/chat")
	public String chat(@RequestBody Map<String, String> request, @AuthenticationPrincipal Jwt jwt) {
		this.identityTemplate.getWorkloadAccessTokenForJwt(jwt.getTokenValue(), this.workloadName);
		return "successfully retrieved workload access token";
	}

	@PostMapping("/api-key")
	public String apiKey(@AuthenticationPrincipal Jwt jwt) {
		String workloadAccessToken = this.identityTemplate.getWorkloadAccessTokenForJwt(jwt.getTokenValue(),
				this.workloadName);
		this.identityTemplate.getApiKey(workloadAccessToken, this.resourceCredentialProviderName);
		return "successfully retrieved api key";
	}

	@PostMapping("/api-key/reactive")
	public Mono<String> reactiveApiKey(@AuthenticationPrincipal Jwt jwt) {
		return Mono.fromCallable(() -> this.apiKey(jwt)).subscribeOn(Schedulers.boundedElastic());
	}

	@PostMapping("/oauth-token")
	public String oauthToken(@AuthenticationPrincipal Jwt jwt) {
		String workloadAccessToken = this.identityTemplate.getWorkloadAccessTokenForJwt(jwt.getTokenValue(),
				this.workloadName);
		this.identityTemplate.getOauthAccessToken((oauth) -> oauth.workloadIdentityToken(workloadAccessToken)
			.resourceCredentialProviderName(this.resourceCredentialProviderName)
			.oauth2Flow(Oauth2FlowType.M2_M)
			.scopes(this.oauthScopes));
		return "successfully retrieved oauth token";
	}

	@PostMapping("/oauth-token/reactive")
	public Mono<String> reactiveOauthToken(@AuthenticationPrincipal Jwt jwt) {
		return Mono.fromCallable(() -> this.oauthToken(jwt)).subscribeOn(Schedulers.boundedElastic());
	}

	@PostMapping("/oauth-user-federation/start")
	public AuthorizationSession startUserFederation(@AuthenticationPrincipal Jwt jwt) {
		this.purgeExpiredAuthorizations();
		if (this.pendingAuthorizations.size() >= MAX_PENDING_AUTHORIZATIONS) {
			throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
					"Too many pending OAuth authorization sessions");
		}

		String workloadAccessToken = this.identityTemplate.getWorkloadAccessTokenForJwt(jwt.getTokenValue(),
				this.workloadName);
		String state = UUID.randomUUID().toString();
		GetResourceOauth2TokenResponse response = this.identityTemplate.getOauthToken((oauth) -> oauth
			.workloadIdentityToken(workloadAccessToken)
			.resourceCredentialProviderName(this.resourceCredentialProviderName)
			.oauth2Flow(Oauth2FlowType.USER_FEDERATION)
			.scopes(this.oauthScopes)
			.resourceOauth2ReturnUrl(this.oauthReturnUrl)
			.customState(state));

		if (response.accessToken() != null) {
			return new AuthorizationSession(null, null, null, true);
		}
		if (response.authorizationUrl() == null || response.sessionUri() == null) {
			throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
					"AgentCore Identity returned an incomplete authorization session");
		}

		this.pendingAuthorizations.put(response.sessionUri(),
				new PendingAuthorization(jwt.getSubject(), state, Instant.now().plus(AUTHORIZATION_SESSION_TTL)));
		return new AuthorizationSession(response.authorizationUrl(), response.sessionUri(), state, false);
	}

	@PostMapping("/oauth-user-federation/start/reactive")
	public Mono<AuthorizationSession> startUserFederationReactive(@AuthenticationPrincipal Jwt jwt) {
		return Mono.fromCallable(() -> this.startUserFederation(jwt)).subscribeOn(Schedulers.boundedElastic());
	}

	@PostMapping("/oauth-user-federation/complete")
	public String completeUserFederation(@RequestBody CompleteAuthorization request,
			@AuthenticationPrincipal Jwt jwt) {
		PendingAuthorization pending = this.pendingAuthorizations.remove(request.sessionUri());
		if (!this.isValidAuthorization(pending, request.state(), jwt.getSubject())) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid or expired OAuth authorization session");
		}

		this.identityTemplate.completeResourceTokenAuth(request.sessionUri(), jwt.getTokenValue());
		String workloadAccessToken = this.identityTemplate.getWorkloadAccessTokenForJwt(jwt.getTokenValue(),
				this.workloadName);
		GetResourceOauth2TokenResponse response = this.identityTemplate.getOauthToken((oauth) -> oauth
			.workloadIdentityToken(workloadAccessToken)
			.resourceCredentialProviderName(this.resourceCredentialProviderName)
			.oauth2Flow(Oauth2FlowType.USER_FEDERATION)
			.scopes(this.oauthScopes)
			.sessionUri(request.sessionUri()));
		if (response.accessToken() == null) {
			throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
					"AgentCore Identity did not return an access token for the completed session");
		}
		return "successfully completed OAuth user federation";
	}

	@PostMapping("/oauth-user-federation/complete/reactive")
	public Mono<String> completeUserFederationReactive(@RequestBody CompleteAuthorization request,
			@AuthenticationPrincipal Jwt jwt) {
		return Mono.fromCallable(() -> this.completeUserFederation(request, jwt))
			.subscribeOn(Schedulers.boundedElastic());
	}

	private boolean isValidAuthorization(PendingAuthorization pending, String state, String userId) {
		return pending != null && pending.expiresAt().isAfter(Instant.now()) && pending.userId().equals(userId)
				&& state != null && MessageDigest.isEqual(pending.state().getBytes(StandardCharsets.UTF_8),
						state.getBytes(StandardCharsets.UTF_8));
	}

	private void purgeExpiredAuthorizations() {
		Instant now = Instant.now();
		this.pendingAuthorizations.entrySet().removeIf((entry) -> !entry.getValue().expiresAt().isAfter(now));
	}

	record AuthorizationSession(String authorizationUrl, String sessionUri, String state, boolean alreadyAuthorized) {
	}

	record CompleteAuthorization(String sessionUri, String state) {
	}

	private record PendingAuthorization(String userId, String state, Instant expiresAt) {
	}

}
