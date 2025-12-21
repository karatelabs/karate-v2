/*
 * The MIT License
 *
 * Copyright 2025 Karate Labs Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package io.karatelabs.core.mock;

import io.karatelabs.core.MockServer;
import io.karatelabs.io.http.ApacheHttpClient;
import io.karatelabs.io.http.HttpClient;
import io.karatelabs.io.http.HttpRequestBuilder;
import io.karatelabs.io.http.HttpResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for karate.proceed() proxy mode functionality.
 */
class MockProxyTest {

    private MockServer backendServer;
    private MockServer proxyServer;
    private HttpClient client;

    @AfterEach
    void cleanup() {
        if (client != null) {
            try {
                client.close();
            } catch (Exception e) {
                // ignore
            }
        }
        if (proxyServer != null) {
            proxyServer.stop();
        }
        if (backendServer != null) {
            backendServer.stop();
        }
    }

    @Test
    void testProceedWithTargetUrl() {
        // Start a backend server
        backendServer = MockServer.featureString("""
            @mock
            Feature: Backend API

            Scenario: pathMatches('/api/data')
              * def response = { source: 'backend', message: 'hello from backend' }
            """)
            .port(0)
            .start();

        // Start a proxy server that forwards to backend
        String backendUrl = "http://localhost:" + backendServer.getPort();
        proxyServer = MockServer.featureString("""
            @mock
            Feature: Proxy

            Background:
              * def backendUrl = '%s'

            Scenario: pathMatches('/api/data')
              * def response = karate.proceed(backendUrl)
            """.formatted(backendUrl))
            .port(0)
            .start();

        client = new ApacheHttpClient();
        HttpRequestBuilder builder = new HttpRequestBuilder(client);
        builder.url("http://localhost:" + proxyServer.getPort()).path("/api/data").method("GET");
        HttpResponse response = builder.invoke();

        assertEquals(200, response.getStatus());

        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBodyConverted();
        assertEquals("backend", body.get("source"));
        assertEquals("hello from backend", body.get("message"));
    }

    @Test
    void testProceedWithPostRequest() {
        // Backend that echoes the request body
        backendServer = MockServer.featureString("""
            @mock
            Feature: Backend Echo

            Scenario: pathMatches('/echo') && methodIs('post')
              * def response = { received: request }
              * def responseStatus = 201
            """)
            .port(0)
            .start();

        // Proxy that forwards POST requests
        String backendUrl = "http://localhost:" + backendServer.getPort();
        proxyServer = MockServer.featureString("""
            @mock
            Feature: Proxy

            Background:
              * def backendUrl = '%s'

            Scenario: pathMatches('/echo') && methodIs('post')
              * def response = karate.proceed(backendUrl)
            """.formatted(backendUrl))
            .port(0)
            .start();

        client = new ApacheHttpClient();
        HttpRequestBuilder builder = new HttpRequestBuilder(client);
        builder.url("http://localhost:" + proxyServer.getPort())
            .path("/echo")
            .method("POST")
            .body(Map.of("name", "test", "value", 42));
        HttpResponse response = builder.invoke();

        assertEquals(201, response.getStatus());

        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBodyConverted();
        assertNotNull(body.get("received"));

        @SuppressWarnings("unchecked")
        Map<String, Object> received = (Map<String, Object>) body.get("received");
        assertEquals("test", received.get("name"));
        assertEquals(42, ((Number) received.get("value")).intValue());
    }

    @Test
    void testProceedWithResponseModification() {
        // Backend returns basic data
        backendServer = MockServer.featureString("""
            @mock
            Feature: Backend

            Scenario: methodIs('get')
              * def response = { original: true }
            """)
            .port(0)
            .start();

        // Proxy modifies the response before returning
        String backendUrl = "http://localhost:" + backendServer.getPort();
        proxyServer = MockServer.featureString("""
            @mock
            Feature: Modifying Proxy

            Background:
              * def backendUrl = '%s'

            Scenario: methodIs('get')
              * def backendResponse = karate.proceed(backendUrl)
              * def originalBody = backendResponse.body
              * def response = { original: originalBody.original, modified: true, timestamp: 'now' }
            """.formatted(backendUrl))
            .port(0)
            .start();

        client = new ApacheHttpClient();
        HttpRequestBuilder builder = new HttpRequestBuilder(client);
        builder.url("http://localhost:" + proxyServer.getPort()).path("/").method("GET");
        HttpResponse response = builder.invoke();

        assertEquals(200, response.getStatus());

        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBodyConverted();
        assertEquals(true, body.get("original"));
        assertEquals(true, body.get("modified"));
        assertEquals("now", body.get("timestamp"));
    }

}
