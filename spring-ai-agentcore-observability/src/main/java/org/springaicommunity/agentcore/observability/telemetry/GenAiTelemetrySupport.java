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

package org.springaicommunity.agentcore.observability.telemetry;

import java.util.List;

import io.opentelemetry.api.common.AttributeKey;

/**
 * Stable GenAI-related attribute keys aligned with OpenTelemetry semantic conventions for
 * generative AI. See:
 * <a href="https://opentelemetry.io/docs/specs/semconv/gen-ai/gen-ai-spans/">GenAI
 * spans</a>.
 *
 * @author Vaquar Khan
 */
public final class GenAiTelemetrySupport {

	/** Provider name for AWS Bedrock. */
	public static final String PROVIDER_AWS_BEDROCK = "aws.bedrock";

	/** Attribute key for the GenAI operation name. */
	public static final AttributeKey<String> GEN_AI_OPERATION_NAME = AttributeKey.stringKey("gen_ai.operation.name");

	/** Attribute key for the GenAI provider name. */
	public static final AttributeKey<String> GEN_AI_PROVIDER_NAME = AttributeKey.stringKey("gen_ai.provider.name");

	/** Attribute key for the GenAI system identifier. */
	public static final AttributeKey<String> GEN_AI_SYSTEM = AttributeKey.stringKey("gen_ai.system");

	/** Attribute key for the GenAI request model. */
	public static final AttributeKey<String> GEN_AI_REQUEST_MODEL = AttributeKey.stringKey("gen_ai.request.model");

	/** Attribute key for the GenAI response model. */
	public static final AttributeKey<String> GEN_AI_RESPONSE_MODEL = AttributeKey.stringKey("gen_ai.response.model");

	/** Attribute key for the number of input tokens used. */
	public static final AttributeKey<Long> GEN_AI_USAGE_INPUT_TOKENS = AttributeKey
		.longKey("gen_ai.usage.input_tokens");

	/** Attribute key for the number of output tokens used. */
	public static final AttributeKey<Long> GEN_AI_USAGE_OUTPUT_TOKENS = AttributeKey
		.longKey("gen_ai.usage.output_tokens");

	/** Attribute key for the list of finish reasons from the response. */
	public static final AttributeKey<List<String>> GEN_AI_RESPONSE_FINISH_REASONS = AttributeKey
		.stringArrayKey("gen_ai.response.finish_reasons");

	/** Attribute key for the error type classification. */
	public static final AttributeKey<String> ERROR_TYPE = AttributeKey.stringKey("error.type");

	/**
	 * Amazon Bedrock / AgentCore request correlation (from inbound HTTP headers when
	 * present).
	 */
	public static final AttributeKey<String> AWS_BEDROCK_AGENTCORE_SESSION_ID = AttributeKey
		.stringKey("aws.bedrock.agentcore.session_id");

	/** Attribute key for the AWS request identifier. */
	public static final AttributeKey<String> AWS_REQUEST_ID = AttributeKey.stringKey("aws.request_id");

	/**
	 * AgentCore session header. Matches
	 * {@code org.springaicommunity.agentcore.context.AgentCoreHeaders.SESSION_ID}. HTTP
	 * header lookups are case-insensitive so the lowercase form works for both servlet
	 * and WebFlux.
	 */
	public static final String HTTP_HEADER_AGENTCORE_SESSION_ID = "x-amzn-bedrock-agentcore-runtime-session-id";

	/**
	 * Standard AWS response header for the request id (see AWS docs:
	 * {@code x-amzn-request-id}). Servlet and Spring {@code getHeader} lookups are
	 * case-insensitive; this is the canonical spelling.
	 */
	public static final String HTTP_HEADER_AMZN_REQUEST_ID = "x-amzn-request-id";

	/**
	 * Alternate spellings for the same logical header (legacy undashed form, doc-style
	 * casing). Lookup should try these in order after the primary constant when
	 * correlating with tools that key on a specific string form.
	 */
	public static final String[] HTTP_HEADER_AMZN_REQUEST_ID_ALIASES = { HTTP_HEADER_AMZN_REQUEST_ID,
			"x-amzn-requestid", "X-Amzn-RequestId" };

	/** Histogram metric name from GenAI client metrics semantic conventions. */
	public static final String METRIC_GEN_AI_CLIENT_TOKEN_USAGE = "gen_ai.client.token.usage";

	/** Attribute key for the token type (input or output). */
	public static final AttributeKey<String> GEN_AI_TOKEN_TYPE = AttributeKey.stringKey("gen_ai.token.type");

	/** Operation name for chat completions. */
	public static final String OP_CHAT = "chat";

	/** Operation name for tool execution. */
	public static final String OP_EXECUTE_TOOL = "execute_tool";

	private GenAiTelemetrySupport() {
	}

}
