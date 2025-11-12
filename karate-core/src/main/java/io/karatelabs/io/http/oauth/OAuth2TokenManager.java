package io.karatelabs.io.http.oauth;

import io.karatelabs.common.Json;
import io.karatelabs.io.http.HttpRequestBuilder;
import io.karatelabs.io.http.HttpResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * Manages OAuth 2.0 token storage and refresh.
 */
public class OAuth2TokenManager {

    private static final Logger logger = LoggerFactory.getLogger(OAuth2TokenManager.class);

    private final Map<String, Object> config;
    private OAuth2Token currentToken;

    public OAuth2TokenManager(Map<String, Object> config) {
        this.config = config;
    }

    /**
     * Get current valid token (null if expired or not available)
     */
    public OAuth2Token getValidToken() {
        if (currentToken == null || currentToken.isExpired()) {
            return null;
        }
        return currentToken;
    }

    /**
     * Store new token
     */
    public void storeToken(OAuth2Token token) {
        this.currentToken = token;
        logger.info("Token stored, expires in {} seconds", token.getExpiresInSeconds());
    }

    /**
     * Refresh access token using refresh token
     * @return New token or null if refresh fails
     */
    public OAuth2Token refreshToken(HttpRequestBuilder builder) {
        if (currentToken == null || !currentToken.hasRefreshToken()) {
            logger.warn("Cannot refresh: no refresh token available");
            return null;
        }

        logger.info("Refreshing OAuth token...");

        String tokenUrl = (String) config.get("url");
        builder.url(tokenUrl);
        builder.formField("grant_type", "refresh_token");
        builder.formField("refresh_token", currentToken.getRefreshToken());
        builder.formField("client_id", config.get("client_id"));

        // Optional client_secret (for confidential clients)
        if (config.containsKey("client_secret")) {
            builder.formField("client_secret", config.get("client_secret"));
        }

        builder.header("Accept", "application/json");

        try {
            HttpResponse response = builder.invoke("post");
            Map<String, Object> data = Json.of(response.getBodyString()).asMap();
            OAuth2Token newToken = OAuth2Token.fromMap(data);
            storeToken(newToken);
            logger.info("Token refreshed successfully");
            return newToken;
        } catch (Exception e) {
            logger.error("Token refresh failed: {}", e.getMessage());
            // Clear current token on refresh failure
            currentToken = null;
            throw new OAuth2Exception("Token refresh failed: " + e.getMessage(), e);
        }
    }

    /**
     * Clear stored token
     */
    public void clearToken() {
        currentToken = null;
        logger.info("Token cleared");
    }
}
