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
 * SSL/TLS integration tests for MockServer.
 */
class MockServerSslTest {

    private MockServer server;
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
        if (server != null) {
            server.stop();
        }
    }

    private HttpResponse request(String method, String path) {
        return request(method, path, null);
    }

    private HttpResponse request(String method, String path, Object body) {
        HttpRequestBuilder builder = new HttpRequestBuilder(client);
        builder.url(server.getUrl()).path(path).method(method);
        if (body != null) {
            builder.body(body);
        }
        return builder.invoke();
    }

    @Test
    void testSslWithSelfSignedCertificate() {
        // Start mock server with SSL (auto-generated self-signed cert)
        server = MockServer.featureString("""
            @mock
            Feature: SSL Test

            Scenario: pathMatches('/secure')
              * def response = { secure: true }
            """)
            .port(0)
            .ssl(true)
            .start();

        // Verify SSL is enabled
        assertTrue(server.isSsl());
        assertTrue(server.getUrl().startsWith("https://"));

        // Create client that trusts all certificates
        client = new ApacheHttpClient();
        client.config("ssl", true);

        // Make HTTPS request
        HttpResponse response = request("GET", "/secure");
        assertEquals(200, response.getStatus());

        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBodyConverted();
        assertEquals(true, body.get("secure"));
    }

    @Test
    void testSslServerProperties() {
        server = MockServer.featureString("""
            @mock
            Feature: Properties Test

            Scenario: methodIs('get')
              * def response = { ok: true }
            """)
            .ssl(true)
            .start();

        // Verify server properties
        assertTrue(server.isSsl());
        assertTrue(server.getPort() > 0);
        assertEquals("https://localhost:" + server.getPort(), server.getUrl());
    }

    @Test
    void testHttpServerNotSsl() {
        server = MockServer.featureString("""
            @mock
            Feature: HTTP Test

            Scenario: methodIs('get')
              * def response = { ok: true }
            """)
            .start();

        // Verify HTTP (not SSL)
        assertFalse(server.isSsl());
        assertTrue(server.getUrl().startsWith("http://"));
        assertFalse(server.getUrl().startsWith("https://"));
    }

    @Test
    void testSslWithStatefulMock() {
        server = MockServer.featureString("""
            @mock
            Feature: Stateful SSL Test

            Background:
              * def counter = { value: 0 }

            Scenario: pathMatches('/increment') && methodIs('post')
              * counter.value = counter.value + 1
              * def response = { count: counter.value }

            Scenario: pathMatches('/count') && methodIs('get')
              * def response = { count: counter.value }
            """)
            .ssl(true)
            .start();

        client = new ApacheHttpClient();
        client.config("ssl", true);

        // Increment counter
        HttpResponse incResponse = request("POST", "/increment");
        assertEquals(200, incResponse.getStatus());

        // Check count
        HttpResponse getResponse = request("GET", "/count");
        assertEquals(200, getResponse.getStatus());

        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) getResponse.getBodyConverted();
        assertEquals(1, ((Number) body.get("count")).intValue());
    }

    @Test
    void testSslWithJsonPayload() {
        server = MockServer.featureString("""
            @mock
            Feature: JSON SSL Test

            Scenario: pathMatches('/echo') && methodIs('post')
              * def response = request
              * def responseStatus = 201
            """)
            .ssl(true)
            .start();

        client = new ApacheHttpClient();
        client.config("ssl", true);

        Map<String, Object> payload = Map.of("name", "test", "value", 42);
        HttpResponse response = request("POST", "/echo", payload);
        assertEquals(201, response.getStatus());

        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBodyConverted();
        assertEquals("test", body.get("name"));
        assertEquals(42, ((Number) body.get("value")).intValue());
    }

}
