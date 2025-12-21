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
import io.karatelabs.core.ScenarioRuntime;
import io.karatelabs.io.http.ApacheHttpClient;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.karatelabs.core.TestUtils.*;

/**
 * End-to-end tests for mock features.
 * Uses a shared mock server for all tests, running Karate features against it.
 *
 * NOTE: Tests from karate-demo (e.g., demo/mock/) are a separate exercise.
 * This file focuses on core mock functionality from karate-core.
 */
class MockE2eTest {

    private static MockServer server;
    private static int port;

    @BeforeAll
    static void startServer() {
        // Comprehensive mock server handling all test scenarios
        server = MockServer.featureString("""
            Feature: E2E Test Mock

            Background:
              * def counter = 0
              * def payments = {}
              * def foo = { bar: 'baz' }

            # Payment CRUD scenarios
            Scenario: pathMatches('/payments') && methodIs('post')
              * def payment = request
              * def counter = counter + 1
              * def id = '' + counter
              * payment.id = id
              * payments[id] = payment
              * def response = payment

            Scenario: pathMatches('/payments/{id}') && methodIs('put')
              * payments[pathParams.id] = request
              * def response = request

            Scenario: pathMatches('/payments/{id}') && methodIs('delete')
              * karate.remove('payments', pathParams.id)
              * def responseStatus = 204

            Scenario: pathMatches('/payments/{id}')
              * def response = payments[pathParams.id]
              * def responseStatus = response ? 200 : 404

            Scenario: pathMatches('/payments')
              * def response = karate.valuesOf(payments)

            # Simple response scenarios
            Scenario: pathMatches('/hello')
              * def response = foo

            # Performance test scenarios
            Scenario: pathMatches('/fast')
              * def response = { speed: 'fast' }

            Scenario: pathMatches('/slow')
              * def responseDelay = 100
              * def response = { speed: 'slow' }

            # Path params test
            Scenario: pathMatches('/users/{userId}/orders/{orderId}')
              * def response = { userId: pathParams.userId, orderId: pathParams.orderId }

            # Headers test
            Scenario: pathMatches('/headers')
              * def response = { auth: requestHeaders['Authorization'][0], custom: requestHeaders['X-Custom'][0] }
            """)
            .port(0)
            .start();

        port = server.getPort();
    }

    @AfterAll
    static void stopServer() {
        if (server != null) {
            server.stopAsync();
        }
    }

    @Test
    void testPaymentsMockCrud() {
        ScenarioRuntime sr = runFeature(new ApacheHttpClient(), """
            Feature: Test Payments Mock

            Scenario: CRUD operations on payments
            * url 'http://localhost:%d'

            # Create payment
            * path '/payments'
            * request { amount: 100, description: 'Test' }
            * method post
            * status 200
            * match response.id == '#notnull'
            * match response.amount == 100
            * def id = response.id

            # Get payment
            * path '/payments/' + id
            * method get
            * status 200
            * match response.id == id
            * match response.amount == 100

            # List payments
            * path '/payments'
            * method get
            * status 200
            * match response == '#array'

            # Update payment
            * path '/payments/' + id
            * request { id: '#(id)', amount: 200 }
            * method put
            * status 200

            # Verify update
            * path '/payments/' + id
            * method get
            * status 200
            * match response.amount == 200

            # Delete payment
            * path '/payments/' + id
            * method delete
            * status 204

            # Verify deleted
            * path '/payments/' + id
            * method get
            * status 404
            """.formatted(port));

        assertPassed(sr);
    }

    @Test
    void testCallResponseMock() {
        ScenarioRuntime sr = runFeature(new ApacheHttpClient(), """
            Feature: Test Call Response Mock

            Scenario: Get hello endpoint
            * url 'http://localhost:%d'
            * path '/hello'
            * method get
            * status 200
            * match response == { bar: 'baz' }
            """.formatted(port));

        assertPassed(sr);
    }

    @Test
    void testPerfMock() {
        ScenarioRuntime sr = runFeature(new ApacheHttpClient(), """
            Feature: Test Perf Mock

            Scenario: Fast and slow endpoints
            * url 'http://localhost:%d'

            # Fast endpoint should respond quickly
            * path '/fast'
            * method get
            * status 200
            * match response.speed == 'fast'
            * assert responseTime < 100

            # Slow endpoint has configured delay
            * path '/slow'
            * method get
            * status 200
            * match response.speed == 'slow'
            * assert responseTime >= 100
            """.formatted(port));

        assertPassed(sr);
    }

    @Test
    void testPathParams() {
        ScenarioRuntime sr = runFeature(new ApacheHttpClient(), """
            Feature: Test Path Params

            Scenario: Extract multiple path parameters
            * url 'http://localhost:%d'
            * path '/users/123/orders/456'
            * method get
            * status 200
            * match response == { userId: '123', orderId: '456' }
            """.formatted(port));

        assertPassed(sr);
    }

    @Test
    void testRequestHeaders() {
        ScenarioRuntime sr = runFeature(new ApacheHttpClient(), """
            Feature: Test Request Headers

            Scenario: Access request headers in mock
            * url 'http://localhost:%d'
            * path '/headers'
            * header Authorization = 'Bearer token123'
            * header X-Custom = 'custom-value'
            * method get
            * status 200
            * match response.auth == 'Bearer token123'
            * match response.custom == 'custom-value'
            """.formatted(port));

        assertPassed(sr);
    }

}
