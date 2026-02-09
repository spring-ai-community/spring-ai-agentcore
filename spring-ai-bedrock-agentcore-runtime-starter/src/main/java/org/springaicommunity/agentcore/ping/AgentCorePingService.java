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

package org.springaicommunity.agentcore.ping;

import org.springaicommunity.agentcore.model.AgentCorePingResponse;

/**
 * Service interface for AgentCore ping functionality.
 *
 * <p>
 * This service provides health status information in the format required by the AWS
 * Bedrock AgentCore Runtime contract. Implementations can provide static health status or
 * integrate with Spring Boot Actuator for dynamic health checking.
 * </p>
 *
 * @since 1.0.0
 */
public interface AgentCorePingService {

	/**
	 * Gets the current ping status for the AgentCore application.
	 * @return the current ping response containing status, HTTP status code, and
	 * timestamp of last status change
	 */
	AgentCorePingResponse getPingStatus();

}
