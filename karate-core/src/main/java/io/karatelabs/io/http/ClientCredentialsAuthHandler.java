package io.karatelabs.io.http;

import io.karatelabs.common.Json;

import java.util.Map;

public class ClientCredentialsAuthHandler implements AuthHandler {

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
        HttpResponse response = builder.invoke("post");
        return Json.of(response.getBodyString()).asMap();
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

}
