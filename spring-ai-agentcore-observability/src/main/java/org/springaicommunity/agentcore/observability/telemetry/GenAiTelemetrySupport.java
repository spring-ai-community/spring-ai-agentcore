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

import io.opentelemetry.api.common.AttributeKey;

/**
 * Stable GenAI-related attribute keys aligned with OpenTelemetry semantic conventions for
 * generative AI. See:
 * <a href="https://opentelemetry.io/docs/specs/semconv/gen-ai/gen-ai-spans/">GenAI
 * spans</a>.
 */
public final class GenAiTelemetrySupport {

	public static final String PROVIDER_AWS_BEDROCK = "aws.bedrock";

	public static final AttributeKey<String> GEN_AI_OPERATION_NAME = AttributeKey.stringKey("gen_ai.operation.name");

	public static final AttributeKey<String> GEN_AI_PROVIDER_NAME = AttributeKey.stringKey("gen_ai.provider.name");

	public static final AttributeKey<String> GEN_AI_SYSTEM = AttributeKey.stringKey("gen_ai.system");

	public static final AttributeKey<String> GEN_AI_REQUEST_MODEL = AttributeKey.stringKey("gen_ai.request.model");

	public static final AttributeKey<String> GEN_AI_RESPONSE_MODEL = AttributeKey.stringKey("gen_ai.response.model");

	public static final AttributeKey<Long> GEN_AI_USAGE_INPUT_TOKENS = AttributeKey
		.longKey("gen_ai.usage.input_tokens");

	public static final AttributeKey<Long> GEN_AI_USAGE_OUTPUT_TOKENS = AttributeKey
		.longKey("gen_ai.usage.output_tokens");

	public static final AttributeKey<String> GEN_AI_RESPONSE_FINISH_REASONS = AttributeKey
		.stringKey("gen_ai.response.finish_reasons");

	public static final AttributeKey<String> ERROR_TYPE = AttributeKey.stringKey("error.type");

	/**
	 * Amazon Bedrock / AgentCore request correlation (from inbound HTTP headers when
	 * present).
	 */
	public static final AttributeKey<String> AWS_BEDROCK_AGENTCORE_SESSION_ID = AttributeKey
		.stringKey("aws.bedrock.agentcore.session_id");

	public static final AttributeKey<String> AWS_REQUEST_ID = AttributeKey.stringKey("aws.request_id");

	/** Lowercase HTTP header names used for AgentCore correlation. */
	public static final String HTTP_HEADER_AGENTCORE_SESSION_ID = "x-amzn-bedrock-agentcore-session-id";

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

	public static final AttributeKey<String> GEN_AI_TOKEN_TYPE = AttributeKey.stringKey("gen_ai.token.type");

	/** Span events for optional prompt/completion capture (opt-in). */
	public static final String EVENT_GEN_AI_CONTENT_PROMPT = "gen_ai.content.prompt";

	public static final String EVENT_GEN_AI_CONTENT_COMPLETION = "gen_ai.content.completion";

	public static final AttributeKey<String> GEN_AI_PROMPT = AttributeKey.stringKey("gen_ai.prompt");

	public static final AttributeKey<String> GEN_AI_COMPLETION = AttributeKey.stringKey("gen_ai.completion");

	public static final String OP_CHAT = "chat";

	public static final String OP_EXECUTE_TOOL = "execute_tool";

	private GenAiTelemetrySupport() {
	}

}
