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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.bedrockagentcore.BedrockAgentCoreClient;
import software.amazon.awssdk.services.bedrockagentcore.model.CompleteResourceTokenAuthRequest;
import software.amazon.awssdk.services.bedrockagentcore.model.CompleteResourceTokenAuthResponse;
import software.amazon.awssdk.services.bedrockagentcore.model.GetResourceApiKeyRequest;
import software.amazon.awssdk.services.bedrockagentcore.model.GetResourceApiKeyResponse;
import software.amazon.awssdk.services.bedrockagentcore.model.GetResourceOauth2TokenResponse;
import software.amazon.awssdk.services.bedrockagentcore.model.GetWorkloadAccessTokenForJwtRequest;
import software.amazon.awssdk.services.bedrockagentcore.model.GetWorkloadAccessTokenForJwtResponse;
import software.amazon.awssdk.services.bedrockagentcore.model.GetWorkloadAccessTokenForUserIdRequest;
import software.amazon.awssdk.services.bedrockagentcore.model.GetWorkloadAccessTokenForUserIdResponse;
import software.amazon.awssdk.services.bedrockagentcore.model.GetWorkloadAccessTokenRequest;
import software.amazon.awssdk.services.bedrockagentcore.model.GetWorkloadAccessTokenResponse;
import software.amazon.awssdk.services.bedrockagentcore.model.SessionStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

@ExtendWith(MockitoExtension.class)
class AgentCoreIdentityTemplateTests {

	@Mock
	private BedrockAgentCoreClient client;

	private AgentCoreIdentityTemplate template;

	@BeforeEach
	void setUp() {
		this.template = new AgentCoreIdentityTemplate(this.client);
	}

	@Test
	@SuppressWarnings({ "rawtypes", "unchecked" })
	void getWorkloadAccessTokenForJwtReturnsToken() {
		given(this.client.getWorkloadAccessTokenForJWT(any(Consumer.class)))
			.willReturn(GetWorkloadAccessTokenForJwtResponse.builder().workloadAccessToken("wat-123").build());

		String token = this.template.getWorkloadAccessTokenForJwt("my-jwt", "my-workload");

		ArgumentCaptor<Consumer<GetWorkloadAccessTokenForJwtRequest.Builder>> captor = ArgumentCaptor
			.forClass(Consumer.class);
		then(this.client).should().getWorkloadAccessTokenForJWT(captor.capture());
		GetWorkloadAccessTokenForJwtRequest.Builder request = GetWorkloadAccessTokenForJwtRequest.builder();
		captor.getValue().accept(request);
		assertThat(request.build().userToken()).isEqualTo("my-jwt");
		assertThat(request.build().workloadName()).isEqualTo("my-workload");
		assertThat(token).isEqualTo("wat-123");
	}

	@Test
	void getWorkloadAccessTokenForJwtRejectsNullJwt() {
		assertThatIllegalArgumentException()
			.isThrownBy(() -> this.template.getWorkloadAccessTokenForJwt(null, "workload"));
	}

	@Test
	void getWorkloadAccessTokenForJwtRejectsEmptyWorkloadName() {
		assertThatIllegalArgumentException().isThrownBy(() -> this.template.getWorkloadAccessTokenForJwt("jwt", ""));
	}

	@Test
	@SuppressWarnings({ "rawtypes", "unchecked" })
	void getApiKeyReturnsKey() {
		given(this.client.getResourceApiKey(any(Consumer.class)))
			.willReturn(GetResourceApiKeyResponse.builder().apiKey("key-456").build());

		String apiKey = this.template.getApiKey("token", "my-provider");

		ArgumentCaptor<Consumer<GetResourceApiKeyRequest.Builder>> captor = ArgumentCaptor.forClass(Consumer.class);
		then(this.client).should().getResourceApiKey(captor.capture());
		GetResourceApiKeyRequest.Builder request = GetResourceApiKeyRequest.builder();
		captor.getValue().accept(request);
		assertThat(request.build().workloadIdentityToken()).isEqualTo("token");
		assertThat(request.build().resourceCredentialProviderName()).isEqualTo("my-provider");
		assertThat(apiKey).isEqualTo("key-456");
	}

	@Test
	void getApiKeyRejectsNullToken() {
		assertThatIllegalArgumentException().isThrownBy(() -> this.template.getApiKey(null, "provider"));
	}

	@Test
	void getApiKeyRejectsEmptyResourceName() {
		assertThatIllegalArgumentException().isThrownBy(() -> this.template.getApiKey("token", ""));
	}

	@Test
	void getOauthAccessTokenReturnsAccessToken() {
		given(this.client.getResourceOauth2Token(any(Consumer.class)))
			.willReturn(GetResourceOauth2TokenResponse.builder().accessToken("oauth-789").build());

		String token = this.template.getOauthAccessToken(
				(c) -> c.workloadIdentityToken("wit").resourceCredentialProviderName("provider").scopes("read"));
		assertThat(token).isEqualTo("oauth-789");
	}

	@Test
	void getOauthTokenReturnsAuthorizationSession() {
		GetResourceOauth2TokenResponse response = GetResourceOauth2TokenResponse.builder()
			.authorizationUrl("https://provider.example.com/authorize")
			.sessionUri("urn:ietf:params:oauth:request_uri:session-123")
			.sessionStatus(SessionStatus.IN_PROGRESS)
			.build();
		given(this.client.getResourceOauth2Token(any(Consumer.class))).willReturn(response);

		GetResourceOauth2TokenResponse actual = this.template.getOauthToken(
				(c) -> c.workloadIdentityToken("wit").resourceCredentialProviderName("provider").scopes("read"));

		assertThat(actual.authorizationUrl()).isEqualTo("https://provider.example.com/authorize");
		assertThat(actual.sessionUri()).isEqualTo("urn:ietf:params:oauth:request_uri:session-123");
		assertThat(actual.sessionStatus()).isEqualTo(SessionStatus.IN_PROGRESS);
	}

	@Test
	void getOauthTokenRejectsNullConsumer() {
		assertThatIllegalArgumentException().isThrownBy(() -> this.template.getOauthToken(null));
	}

	@Test
	void constructorRejectsNullClient() {
		assertThatIllegalArgumentException().isThrownBy(() -> new AgentCoreIdentityTemplate(null));
	}

	@Test
	@SuppressWarnings({ "rawtypes", "unchecked" })
	void getWorkloadAccessTokenForUserIdReturnsToken() {
		given(this.client.getWorkloadAccessTokenForUserId(any(Consumer.class)))
			.willReturn(GetWorkloadAccessTokenForUserIdResponse.builder().workloadAccessToken("wat-user-456").build());

		String token = this.template.getWorkloadAccessTokenForUserId("user123", "my-workload");

		ArgumentCaptor<Consumer<GetWorkloadAccessTokenForUserIdRequest.Builder>> captor = ArgumentCaptor
			.forClass(Consumer.class);
		then(this.client).should().getWorkloadAccessTokenForUserId(captor.capture());
		GetWorkloadAccessTokenForUserIdRequest.Builder request = GetWorkloadAccessTokenForUserIdRequest.builder();
		captor.getValue().accept(request);
		assertThat(request.build().userId()).isEqualTo("user123");
		assertThat(request.build().workloadName()).isEqualTo("my-workload");
		assertThat(token).isEqualTo("wat-user-456");
	}

	@Test
	void getWorkloadAccessTokenForUserIdRejectsNullUserId() {
		assertThatIllegalArgumentException()
			.isThrownBy(() -> this.template.getWorkloadAccessTokenForUserId(null, "workload"));
	}

	@Test
	void getWorkloadAccessTokenForUserIdRejectsEmptyWorkloadName() {
		assertThatIllegalArgumentException()
			.isThrownBy(() -> this.template.getWorkloadAccessTokenForUserId("user", ""));
	}

	@Test
	@SuppressWarnings({ "rawtypes", "unchecked" })
	void getWorkloadAccessTokenReturnsToken() {
		given(this.client.getWorkloadAccessToken(any(Consumer.class)))
			.willReturn(GetWorkloadAccessTokenResponse.builder().workloadAccessToken("wat-simple-789").build());

		String token = this.template.getWorkloadAccessToken("my-workload");

		ArgumentCaptor<Consumer<GetWorkloadAccessTokenRequest.Builder>> captor = ArgumentCaptor
			.forClass(Consumer.class);
		then(this.client).should().getWorkloadAccessToken(captor.capture());
		GetWorkloadAccessTokenRequest.Builder request = GetWorkloadAccessTokenRequest.builder();
		captor.getValue().accept(request);
		assertThat(request.build().workloadName()).isEqualTo("my-workload");
		assertThat(token).isEqualTo("wat-simple-789");
	}

	@Test
	void getWorkloadAccessTokenRejectsEmptyWorkloadName() {
		assertThatIllegalArgumentException().isThrownBy(() -> this.template.getWorkloadAccessToken(""));
	}

	@Test
	@SuppressWarnings({ "rawtypes", "unchecked" })
	void completeResourceTokenAuthCallsClient() {
		given(this.client.completeResourceTokenAuth(any(Consumer.class)))
			.willReturn(CompleteResourceTokenAuthResponse.builder().build());

		this.template.completeResourceTokenAuth("https://callback.example.com/session/123", "my-jwt");

		ArgumentCaptor<Consumer<CompleteResourceTokenAuthRequest.Builder>> captor = ArgumentCaptor
			.forClass(Consumer.class);
		then(this.client).should().completeResourceTokenAuth(captor.capture());
		CompleteResourceTokenAuthRequest.Builder request = CompleteResourceTokenAuthRequest.builder();
		captor.getValue().accept(request);
		assertThat(request.build().sessionUri()).isEqualTo("https://callback.example.com/session/123");
		assertThat(request.build().userIdentifier().userToken()).isEqualTo("my-jwt");
	}

	@Test
	void completeResourceTokenAuthRejectsNullSessionUri() {
		assertThatIllegalArgumentException().isThrownBy(() -> this.template.completeResourceTokenAuth(null, "jwt"));
	}

	@Test
	void completeResourceTokenAuthRejectsEmptyUserToken() {
		assertThatIllegalArgumentException().isThrownBy(() -> this.template.completeResourceTokenAuth("uri", ""));
	}

	@Test
	void completeResourceTokenAuthForUserIdCallsClient() {
		given(this.client.completeResourceTokenAuth(any(Consumer.class)))
			.willReturn(CompleteResourceTokenAuthResponse.builder().build());

		this.template.completeResourceTokenAuthForUserId("https://callback.example.com/session/123", "user123");
		then(this.client).should().completeResourceTokenAuth(any(Consumer.class));
	}

	@Test
	void completeResourceTokenAuthForUserIdRejectsNullSessionUri() {
		assertThatIllegalArgumentException()
			.isThrownBy(() -> this.template.completeResourceTokenAuthForUserId(null, "user"));
	}

	@Test
	void completeResourceTokenAuthForUserIdRejectsEmptyUserId() {
		assertThatIllegalArgumentException()
			.isThrownBy(() -> this.template.completeResourceTokenAuthForUserId("uri", ""));
	}

	@Test
	void getApiKeyWithResourceNameUsesHolder() {
		WorkloadAccessTokenHolder holder = new WorkloadAccessTokenHolder();
		holder.set("thread-local-token");
		AgentCoreIdentityTemplate templateWithHolder = new AgentCoreIdentityTemplate(this.client, holder);

		given(this.client.getResourceApiKey(any(Consumer.class)))
			.willReturn(GetResourceApiKeyResponse.builder().apiKey("key-from-holder").build());

		String apiKey = templateWithHolder.getApiKey("my-provider");
		assertThat(apiKey).isEqualTo("key-from-holder");
		holder.clear();
	}

	@Test
	void getApiKeyWithResourceNameThrowsWhenNoHolder() {
		assertThatIllegalStateException().isThrownBy(() -> this.template.getApiKey("provider"))
			.withMessageContaining("WorkloadAccessTokenHolder is not configured");
	}

	@Test
	void getApiKeyWithResourceNameThrowsWhenNoToken() {
		WorkloadAccessTokenHolder holder = new WorkloadAccessTokenHolder();
		AgentCoreIdentityTemplate templateWithHolder = new AgentCoreIdentityTemplate(this.client, holder);

		assertThatIllegalStateException().isThrownBy(() -> templateWithHolder.getApiKey("provider"))
			.withMessageContaining("No workload access token available");
	}

	@Test
	void getApiKeyWithResourceNameRejectsEmptyResourceName() {
		WorkloadAccessTokenHolder holder = new WorkloadAccessTokenHolder();
		holder.set("token");
		AgentCoreIdentityTemplate templateWithHolder = new AgentCoreIdentityTemplate(this.client, holder);

		assertThatIllegalArgumentException().isThrownBy(() -> templateWithHolder.getApiKey(""));
		holder.clear();
	}

}
