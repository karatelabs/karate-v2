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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static io.karatelabs.core.TestUtils.*;

/**
 * V1 Compatibility tests for mock features.
 * Tests V1-style mock features (without @mock tag) work with V2 MockHandler.
 *
 * These are end-to-end tests that:
 * 1. Start a MockServer with a mock feature
 * 2. Run Karate feature tests that make HTTP calls to the mock
 * 3. Verify the responses using Karate match expressions
 *
 * NOTE: Tests from karate-demo (e.g., demo/mock/) are a separate exercise.
 * This file focuses on core mock functionality from karate-core.
 */
class MockV1CompatTest {

    private MockServer server;

    @AfterEach
    void cleanup() {
        if (server != null) {
            server.stop();
        }
    }

    @Test
    void testPaymentsMockCrud() {
        // Start mock server with V1-style feature (no @mock tag)
        server = MockServer.featureString("""
            Feature:

            Background:
            * def counter = 0
            * def payments = {}

            Scenario: pathMatches('/payments') && methodIs('post')
            * def payment = request
            * def counter = counter + 1
            * def id = '' + counter
            * payment.id = id
            * payments[id] = payment
            * def response = payment

            Scenario: pathMatches('/payments')
            * def response = karate.valuesOf(payments)

            Scenario: pathMatches('/payments/{id}') && methodIs('put')
            * payments[pathParams.id] = request
            * def response = request

            Scenario: pathMatches('/payments/{id}') && methodIs('delete')
            * karate.remove('payments', pathParams.id)
            * def responseStatus = 204

            Scenario: pathMatches('/payments/{id}')
            * def response = payments[pathParams.id]
            * def responseStatus = response ? 200 : 404
            """)
            .port(0)
            .start();

        // Run Karate feature to test the mock
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
            """.formatted(server.getPort()));

        assertPassed(sr);
    }

    @Test
    void testCallResponseMock() {
        // V1-style mock with background variables
        server = MockServer.featureString("""
            Feature:

            Background:
            * def foo = { bar: 'baz' }

            Scenario: pathMatches('/hello')
            * def response = foo
            """)
            .port(0)
            .start();

        // Run Karate feature test
        ScenarioRuntime sr = runFeature(new ApacheHttpClient(), """
            Feature: Test Call Response Mock

            Scenario: Get hello endpoint
            * url 'http://localhost:%d'
            * path '/hello'
            * method get
            * status 200
            * match response == { bar: 'baz' }
            """.formatted(server.getPort()));

        assertPassed(sr);
    }

    @Test
    void testPerfMock() {
        // V1-style mock with response delay
        server = MockServer.featureString("""
            Feature:

            Scenario: pathMatches('/fast')
            * def response = { speed: 'fast' }

            Scenario: pathMatches('/slow')
            * def responseDelay = 100
            * def response = { speed: 'slow' }
            """)
            .port(0)
            .start();

        // Run Karate feature test with responseTime assertions
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
            """.formatted(server.getPort()));

        assertPassed(sr);
    }

    @Test
    void testPathParams() {
        // Test path parameter extraction (V1 pattern)
        server = MockServer.featureString("""
            Feature:

            Scenario: pathMatches('/users/{userId}/orders/{orderId}')
            * def response = { userId: pathParams.userId, orderId: pathParams.orderId }
            """)
            .port(0)
            .start();

        ScenarioRuntime sr = runFeature(new ApacheHttpClient(), """
            Feature: Test Path Params

            Scenario: Extract multiple path parameters
            * url 'http://localhost:%d'
            * path '/users/123/orders/456'
            * method get
            * status 200
            * match response == { userId: '123', orderId: '456' }
            """.formatted(server.getPort()));

        assertPassed(sr);
    }

    @Test
    void testRequestHeaders() {
        // Test request header access (V1 pattern)
        server = MockServer.featureString("""
            Feature:

            Scenario: pathMatches('/headers')
            * def response = { auth: requestHeaders['Authorization'][0], custom: requestHeaders['X-Custom'][0] }
            """)
            .port(0)
            .start();

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
            """.formatted(server.getPort()));

        assertPassed(sr);
    }

}
