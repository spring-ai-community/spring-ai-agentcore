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
package org.springaicommunity.example.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import com.nimbusds.jwt.JWT;
import com.nimbusds.jwt.JWTParser;
import com.nimbusds.jwt.JWTClaimsSet;

/**
 * Utility class for JWT token parsing using Nimbus JOSE + JWT.
 * Note: Signature validation is skipped as AgentCore Runtime has already validated the token.
 */
@Component
public class JwtUtil {

    private static final Logger logger = LoggerFactory.getLogger(JwtUtil.class);

    /**
     * Extracts user ID from JWT token using Nimbus JWT parser.
     * 
     * @param token JWT token (without "Bearer " prefix)
     * @return user ID from token claims, or null if not found
     */
    public String extractUserId(String token) {
        try {
            JWT jwt = JWTParser.parse(token);
            JWTClaimsSet claims = jwt.getJWTClaimsSet();
            
            // Try username first, then sub claim
            String username = claims.getStringClaim("username");
            if (username != null) {
                return username;
            }
            
            return claims.getSubject();

        } catch (Exception e) {
            logger.warn("Failed to parse JWT token: {}", e.getMessage());
            return null;
        }
    }
}
