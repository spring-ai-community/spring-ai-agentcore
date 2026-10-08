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

package org.springaicommunity.agentcore.payments.tool;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import org.jspecify.annotations.Nullable;
import org.springaicommunity.agentcore.payments.client.AgentCorePaymentsClientHttpRequestInterceptor;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.boot.http.client.FilteredHostException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.util.Assert;
import org.springframework.util.StreamUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * Calls an HTTP endpoint and reports the response as JSON
 * {@code {"statusCode":...,"headers":{...},"body":...}}. The {@link RestClient} is
 * expected to carry {@link AgentCorePaymentsClientHttpRequestInterceptor}, which pays for
 * {@code 402} responses; the payment context of the tool call is passed to it as a
 * request attribute. HTTP error statuses and connection failures are reported, not
 * thrown; payment failures are thrown.
 *
 * @author Andrei Shakirin
 */
public class PaidHttpRequestTool {

	/** Tool name. */
	public static final String NAME = "paidHttpRequest";

	/** Default tool description. */
	public static final String DESCRIPTION = """
			Call an HTTP endpoint, including paid endpoints (HTTP 402 Payment Required), and return its \
			status code, headers and body. Payment is made automatically within the payment session budget.
			""";

	private static final JsonMapper JSON_MAPPER = JsonMapper.builder().build();

	private final RestClient restClient;

	private final PaymentContextResolver contextResolver;

	private final int maxResponseLength;

	/**
	 * Creates the tool.
	 * @param restClient the client used for requests, with the payments interceptor
	 * @param contextResolver resolves user, instrument and session per tool call
	 * @param maxResponseLength maximum body length returned to the model; longer bodies
	 * are truncated
	 */
	public PaidHttpRequestTool(RestClient restClient, PaymentContextResolver contextResolver, int maxResponseLength) {
		Assert.notNull(restClient, "restClient must not be null");
		Assert.notNull(contextResolver, "contextResolver must not be null");
		this.restClient = restClient;
		this.contextResolver = contextResolver;
		this.maxResponseLength = maxResponseLength;
	}

	/**
	 * Executes the request.
	 * @param request the request
	 * @param toolContext the tool context of the call, may be {@code null}
	 * @return the response as JSON
	 */
	public String execute(Request request, @Nullable ToolContext toolContext) {
		Assert.hasText(request.url(), "url must not be empty");
		HttpMethod method = HttpMethod
			.valueOf((request.method() != null) ? request.method().toUpperCase(Locale.ROOT) : "GET");
		RestClient.RequestBodySpec spec = this.restClient.method(method)
			.uri(request.url())
			.attribute(AgentCorePaymentsClientHttpRequestInterceptor.PAYMENT_CONTEXT_ATTRIBUTE,
					this.contextResolver.resolve(toolContext));
		if (request.headers() != null) {
			request.headers().forEach(spec::header);
		}
		if (request.body() != null && method != HttpMethod.GET && method != HttpMethod.HEAD) {
			if (!hasContentType(request.headers()) && parseBody(request.body()).isContainer()) {
				spec.contentType(MediaType.APPLICATION_JSON);
			}
			spec.body(request.body());
		}
		try {
			return this.exchange(spec);
		}
		catch (ResourceAccessException | FilteredHostException ex) {
			ObjectNode error = JSON_MAPPER.createObjectNode();
			error.put("statusCode", 0);
			error.put("error", "Request failed: " + ex.getMessage());
			return JSON_MAPPER.writeValueAsString(error);
		}
	}

	private String exchange(RestClient.RequestBodySpec spec) {
		return spec.exchange((httpRequest, response) -> {
			int status = response.getStatusCode().value();
			Map<String, String> headers = new LinkedHashMap<>();
			response.getHeaders().forEach((name, values) -> headers.put(name, String.join(", ", values)));
			String body = StreamUtils.copyToString(response.getBody(), StandardCharsets.UTF_8);
			if (body.length() > this.maxResponseLength) {
				body = body.substring(0, this.maxResponseLength) + "... [truncated]";
			}
			ObjectNode result = JSON_MAPPER.createObjectNode();
			result.put("statusCode", status);
			result.set("headers", JSON_MAPPER.valueToTree(headers));
			result.set("body", parseBody(body));
			return JSON_MAPPER.writeValueAsString(result);
		});
	}

	private static boolean hasContentType(@Nullable Map<String, String> headers) {
		return headers != null && headers.keySet().stream().anyMatch(HttpHeaders.CONTENT_TYPE::equalsIgnoreCase);
	}

	private static JsonNode parseBody(String body) {
		try {
			JsonNode node = JSON_MAPPER.readTree(body);
			if (node != null && (node.isObject() || node.isArray())) {
				return node;
			}
		}
		catch (JacksonException ex) {
			// not JSON, return as text
		}
		return JSON_MAPPER.getNodeFactory().stringNode(body);
	}

	/**
	 * Input of the {@value #NAME} tool.
	 *
	 * @param url the URL to call
	 * @param method the HTTP method, {@code GET} if not set
	 * @param headers request headers
	 * @param body request body, ignored for {@code GET} and {@code HEAD}
	 */
	public record Request(@JsonProperty(required = true) @JsonPropertyDescription("The full URL to call") String url,
			@JsonPropertyDescription("HTTP method, GET if not set") @Nullable String method,
			@JsonPropertyDescription("Request headers") @Nullable Map<String, String> headers,
			@JsonPropertyDescription("Request body, ignored for GET and HEAD") @Nullable String body) {
	}

}
