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
