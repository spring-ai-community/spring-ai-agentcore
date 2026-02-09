/*
 * Copyright 2025-2025 the original author or authors.
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

package org.springaicommunity.agentcore.model;

import org.springframework.http.HttpStatus;

/**
 * Response record for AgentCore ping status.
 *
 * <p>
 * Contains the health status information in the format required by the AWS Bedrock
 * AgentCore Runtime contract.
 * </p>
 *
 * @param status the current status enum (HEALTHY, HEALTHY_BUSY, UNHEALTHY)
 * @param httpStatus the HTTP status code to return with the response
 * @param timeOfLastUpdate timestamp in seconds when the status last changed
 * @since 1.0.0
 */
public record AgentCorePingResponse(PingStatus status, HttpStatus httpStatus, long timeOfLastUpdate) {
}
