package io.karatelabs.io.http;

import io.karatelabs.common.Json;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

public class ClientCredentialsAuthHandler implements AuthHandler {

    private static final Logger logger = LoggerFactory.getLogger(ClientCredentialsAuthHandler.class);

    private final Map<String, Object> config;
    private Map<String, Object> token;

    public ClientCredentialsAuthHandler(Map<String, Object> config) {
        this.config = config;
    }

    static Map<String, Object> requestToken(HttpRequestBuilder builder, Map<String, Object> config) {
        builder.url((String) config.get("url"));
        builder.formField("grant_type", "client_credentials");
        builder.formField("client_id", config.get("client_id"));
        builder.formField("client_secret", config.get("client_secret"));
        if (config.containsKey("scope")) {
            builder.formField("scope", config.get("scope"));
        }
        builder.header("Accept", "application/json");
        try {
            HttpResponse response = builder.invoke("post");
            return Json.of(response.getBodyString()).asMap();
        } catch (Exception e) {
            logger.error("client credentials auth request failed: {}", e.getMessage());
            throw new RuntimeException("client credentials auth request failed: " + e.getMessage(), e);
        }
    }

    @Override
    public void apply(HttpRequestBuilder builder) {
        if (token == null) {
            token = requestToken(builder.forkNewBuilder(), config);
        }
        builder.header("Authorization", "Bearer " + token.get("access_token"));
    }

    @Override
    public String getType() {
        return "oauth2";
    }

    @Override
    public String toCurlPreview(String platform) {
        // Return null to indicate we should use a header placeholder
        // Don't call toCurlArgument() as that would trigger token fetch
        return null;
    }

    public String getAuthorizationHeaderPreview() {
        return "Bearer <your-oauth2-access-token>";
    }

}
