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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class JwtUtilTests {

    private JwtUtil jwtUtil;

    @BeforeEach
    void setUp() {
        jwtUtil = new JwtUtil();
    }

    @Test
    void extractUserId_WithValidTokenShouldReturnUsername() {
        // Given
        String token = createUnsignedToken("alice", "sub-123");

        // When
        String userId = jwtUtil.extractUserId(token);

        // Then
        assertEquals("alice", userId);
    }

    @Test
    void extractUserId_WithoutUsernameButWithSubShouldReturnSub() {
        // Given
        String token = createUnsignedToken(null, "sub-123");

        // When
        String userId = jwtUtil.extractUserId(token);

        // Then
        assertEquals("sub-123", userId);
    }

    @Test
    void extractUserId_WithInvalidTokenShouldReturnNull() {
        // Given
        String invalidToken = "invalid.token.here";

        // When
        String userId = jwtUtil.extractUserId(invalidToken);

        // Then
        assertNull(userId);
    }

    @Test
    void extractUserId_PreferUsernameOverSub() {
        // Given
        String token = createUnsignedToken("preferred-username", "fallback-sub");

        // When
        String userId = jwtUtil.extractUserId(token);

        // Then
        assertEquals("preferred-username", userId, "Should prefer username over sub claim");
    }

    /**
     * Helper method to create unsigned JWT tokens for testing
     * Creates a simple JWT with header.payload format (no signature)
     */
    private String createUnsignedToken(String username, String sub) {
        // Create header
        String header = "{\"alg\":\"none\",\"typ\":\"JWT\"}";
        
        // Create payload
        StringBuilder payload = new StringBuilder("{");
        payload.append("\"iat\":").append(System.currentTimeMillis() / 1000).append(",");
        payload.append("\"exp\":").append((System.currentTimeMillis() / 1000) + 3600);
        
        if (sub != null) {
            payload.append(",\"sub\":\"").append(sub).append("\"");
        }
        if (username != null) {
            payload.append(",\"username\":\"").append(username).append("\"");
        }
        
        payload.append("}");
        
        // Base64 encode header and payload
        String encodedHeader = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(header.getBytes());
        String encodedPayload = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payload.toString().getBytes());
        
        // Return unsigned JWT (header.payload.)
        return encodedHeader + "." + encodedPayload + ".";
    }
}
