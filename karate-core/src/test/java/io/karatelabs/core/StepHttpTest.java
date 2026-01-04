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
package io.karatelabs.core;

import io.karatelabs.common.Json;
import io.karatelabs.http.HttpResponse;
import org.junit.jupiter.api.Test;

import static io.karatelabs.core.InMemoryHttpClient.*;
import static io.karatelabs.core.TestUtils.*;
import static org.junit.jupiter.api.Assertions.*;

class StepHttpTest {

    @Test
    void testSimpleGet() {
        InMemoryHttpClient client = new InMemoryHttpClient(req -> json("{ \"id\": 1 }"));

        ScenarioRuntime sr = run(client, """
            * url 'http://test'
            * method get
            * match response == { id: 1 }
            """);
        assertPassed(sr);
    }

    @Test
    void testGetWithPath() {
        InMemoryHttpClient client = new InMemoryHttpClient(req -> {
            // Verify path was set correctly
            String path = req.getPath();
            if (path.endsWith("/users/123")) {
                return json("{ \"id\": 123 }");
            }
            return status(404);
        });

        ScenarioRuntime sr = run(client, """
            * url 'http://test'
            * path 'users', '123'
            * method get
            * match response.id == 123
            """);
        assertPassed(sr);
    }

    @Test
    void testGetWithParams() {
        InMemoryHttpClient client = new InMemoryHttpClient(req -> {
            String page = req.getParam("page");
            String size = req.getParam("size");
            if ("1".equals(page) && "10".equals(size)) {
                return json("{ \"page\": 1, \"size\": 10 }");
            }
            return json("{ \"error\": \"missing params\" }");
        });

        ScenarioRuntime sr = run(client, """
            * url 'http://test/items'
            * param page = 1
            * param size = 10
            * method get
            * match response == { page: 1, size: 10 }
            """);
        assertPassed(sr);
    }

    @Test
    void testStatusAssertion() {
        InMemoryHttpClient client = new InMemoryHttpClient(req -> status(404));

        ScenarioRuntime sr = run(client, """
            * url 'http://test'
            * method get
            * status 200
            """);
        assertFailed(sr);
    }

    @Test
    void testStatusAssertionSuccess() {
        InMemoryHttpClient client = new InMemoryHttpClient(req -> status(201));

        ScenarioRuntime sr = run(client, """
            * url 'http://test'
            * method post
            * status 201
            """);
        assertPassed(sr);
    }

    @Test
    void testPostWithJsonBody() {
        InMemoryHttpClient client = new InMemoryHttpClient(req -> {
            // Echo back request body
            Object body = req.getBodyConverted();
            if (body != null) {
                return json("{ \"received\": true }");
            }
            return status(400);
        });

        ScenarioRuntime sr = run(client, """
            * url 'http://test'
            * request { name: 'test' }
            * method post
            * status 200
            * match response.received == true
            """);
        assertPassed(sr);
    }

    @Test
    void testRequestWithEmbeddedExpressions() {
        InMemoryHttpClient client = new InMemoryHttpClient(req -> {
            // Echo back the request body to verify embedded expressions were resolved
            Object body = req.getBodyConverted();
            return json(Json.stringifyStrict(body));
        });

        ScenarioRuntime sr = run(client, """
            * url 'http://test'
            * def myName = 'John'
            * def myAge = 30
            * request { name: '#(myName)', age: '#(myAge)' }
            * method post
            * status 200
            * match response == { name: 'John', age: 30 }
            """);
        assertPassed(sr);
    }

    @Test
    void testHeaders() {
        InMemoryHttpClient client = new InMemoryHttpClient(req -> {
            String auth = req.getHeader("Authorization");
            if ("Bearer token123".equals(auth)) {
                return json("{ \"authorized\": true }");
            }
            return status(401);
        });

        ScenarioRuntime sr = run(client, """
            * url 'http://test'
            * header Authorization = 'Bearer token123'
            * method get
            * status 200
            * match response.authorized == true
            """);
        assertPassed(sr);
    }

    @Test
    void testResponseStatus() {
        InMemoryHttpClient client = new InMemoryHttpClient(req -> status(204));

        ScenarioRuntime sr = run(client, """
            * url 'http://test'
            * method delete
            * status 204
            """);
        assertPassed(sr);
        assertEquals(204, get(sr, "responseStatus"));
    }

    @Test
    void testResponseHeaders() {
        InMemoryHttpClient client = new InMemoryHttpClient(req -> {
            var resp = json("{}");
            resp.setHeader("X-Custom-Header", "custom-value");
            return resp;
        });

        ScenarioRuntime sr = run(client, """
            * url 'http://test'
            * method get
            * status 200
            """);
        assertPassed(sr);
        Object headers = get(sr, "responseHeaders");
        assertNotNull(headers);
    }

    @Test
    void testUrlFromVariable() {
        InMemoryHttpClient client = new InMemoryHttpClient(req -> json("{ \"ok\": true }"));

        ScenarioRuntime sr = run(client, """
            * def baseUrl = 'http://test'
            * url baseUrl
            * method get
            * match response.ok == true
            """);
        assertPassed(sr);
    }

    @Test
    void testPutMethod() {
        InMemoryHttpClient client = new InMemoryHttpClient(req -> {
            if ("PUT".equals(req.getMethod())) {
                return json("{ \"updated\": true }");
            }
            return status(405);
        });

        ScenarioRuntime sr = run(client, """
            * url 'http://test/item/1'
            * request { name: 'updated' }
            * method put
            * status 200
            * match response.updated == true
            """);
        assertPassed(sr);
    }

    @Test
    void testPatchMethod() {
        InMemoryHttpClient client = new InMemoryHttpClient(req -> {
            if ("PATCH".equals(req.getMethod())) {
                return json("{ \"patched\": true }");
            }
            return status(405);
        });

        ScenarioRuntime sr = run(client, """
            * url 'http://test/item/1'
            * request { status: 'active' }
            * method patch
            * status 200
            """);
        assertPassed(sr);
    }

    @Test
    void testFormParams() {
        InMemoryHttpClient client = new InMemoryHttpClient(req -> {
            String contentType = req.getHeader("Content-Type");
            if (contentType != null && contentType.contains("form")) {
                return json("{ \"form\": true }");
            }
            return status(400);
        });

        ScenarioRuntime sr = run(client, """
            * url 'http://test/login'
            * form field username = 'admin'
            * form field password = 'secret'
            * method post
            * status 200
            """);
        assertPassed(sr);
    }

    @Test
    void testCookie() {
        InMemoryHttpClient client = new InMemoryHttpClient(req -> {
            String cookie = req.getHeader("Cookie");
            if (cookie != null && cookie.contains("session=abc123")) {
                return json("{ \"session\": 'valid' }");
            }
            return status(401);
        });

        ScenarioRuntime sr = run(client, """
            * url 'http://test'
            * cookie session = 'abc123'
            * method get
            * status 200
            """);
        assertPassed(sr);
    }

    @Test
    void testResponseCookies() {
        InMemoryHttpClient client = new InMemoryHttpClient(req -> {
            HttpResponse resp = json("{ \"ok\": true }");
            resp.setHeader("Set-Cookie", java.util.List.of(
                "session=abc123; Path=/; HttpOnly",
                "token=xyz789; Domain=test.com"
            ));
            resp.setStartTime(1234567890L);
            return resp;
        });

        ScenarioRuntime sr = run(client, """
            * url 'http://test'
            * method get
            * status 200
            * match responseCookies.session.value == 'abc123'
            * match responseCookies.session.path == '/'
            * match responseCookies.token.value == 'xyz789'
            * match responseCookies.token.domain == 'test.com'
            * match requestTimeStamp == 1234567890
            """);
        assertPassed(sr);
    }

    @Test
    void testPrevRequest() {
        InMemoryHttpClient client = new InMemoryHttpClient(req -> json("{ \"ok\": true }"));

        ScenarioRuntime sr = run(client, """
            * url 'http://test/users'
            * request { name: 'John' }
            * method post
            * status 200
            * def prev = karate.prevRequest
            * match prev.method == 'POST'
            * match prev.url contains '/users'
            * match prev.body == { name: 'John' }
            """);
        assertPassed(sr);
    }

    @Test
    void testPrevRequestUpdatesAfterEachCall() {
        InMemoryHttpClient client = new InMemoryHttpClient(req -> json("{ \"ok\": true }"));

        ScenarioRuntime sr = run(client, """
            * url 'http://test/first'
            * method get
            * status 200
            * match karate.prevRequest.method == 'GET'
            * match karate.prevRequest.url contains '/first'

            * url 'http://test/second'
            * request { data: 123 }
            * method post
            * status 200
            * match karate.prevRequest.method == 'POST'
            * match karate.prevRequest.url contains '/second'
            """);
        assertPassed(sr);
    }

    // ========== Embedded Expression Tests ==========
    // Tests for V1 compatibility: embedded expressions like #(varName) in JSON literals

    @Test
    void testParamsWithEmbeddedExpressions() {
        InMemoryHttpClient client = new InMemoryHttpClient(req -> {
            String page = req.getParam("page");
            String size = req.getParam("size");
            if ("5".equals(page) && "20".equals(size)) {
                return json("{ \"ok\": true }");
            }
            return json("{ \"page\": \"" + page + "\", \"size\": \"" + size + "\" }");
        });

        ScenarioRuntime sr = run(client, """
            * url 'http://test'
            * def myPage = 5
            * def mySize = 20
            * params { page: '#(myPage)', size: '#(mySize)' }
            * method get
            * status 200
            * match response.ok == true
            """);
        assertPassed(sr);
    }

    @Test
    void testHeadersWithEmbeddedExpressions() {
        InMemoryHttpClient client = new InMemoryHttpClient(req -> {
            String auth = req.getHeader("Authorization");
            String custom = req.getHeader("X-Custom");
            if ("Bearer secret123".equals(auth) && "myvalue".equals(custom)) {
                return json("{ \"ok\": true }");
            }
            return json("{ \"auth\": \"" + auth + "\", \"custom\": \"" + custom + "\" }");
        });

        ScenarioRuntime sr = run(client, """
            * url 'http://test'
            * def token = 'secret123'
            * def customVal = 'myvalue'
            * headers { Authorization: 'Bearer #(token)', 'X-Custom': '#(customVal)' }
            * method get
            * status 200
            * match response.ok == true
            """);
        assertPassed(sr);
    }

    @Test
    void testCookiesWithEmbeddedExpressions() {
        InMemoryHttpClient client = new InMemoryHttpClient(req -> {
            String cookie = req.getHeader("Cookie");
            if (cookie != null && cookie.contains("session=abc123") && cookie.contains("user=john")) {
                return json("{ \"ok\": true }");
            }
            return json("{ \"cookie\": \"" + cookie + "\" }");
        });

        ScenarioRuntime sr = run(client, """
            * url 'http://test'
            * def sessionId = 'abc123'
            * def username = 'john'
            * cookies { session: '#(sessionId)', user: '#(username)' }
            * method get
            * status 200
            * match response.ok == true
            """);
        assertPassed(sr);
    }

    @Test
    void testFormFieldsWithEmbeddedExpressions() {
        InMemoryHttpClient client = new InMemoryHttpClient(req -> {
            String contentType = req.getHeader("Content-Type");
            Object body = req.getBodyConverted();
            // For form data, body is typically a string like "username=admin&password=secret"
            String bodyStr = body != null ? body.toString() : "";
            if (contentType != null && contentType.contains("form")
                && bodyStr.contains("admin") && bodyStr.contains("secret123")) {
                return json("{ \"ok\": true }");
            }
            return json("{ \"body\": \"" + bodyStr + "\" }");
        });

        ScenarioRuntime sr = run(client, """
            * url 'http://test'
            * def user = 'admin'
            * def pass = 'secret123'
            * form fields { username: '#(user)', password: '#(pass)' }
            * method post
            * status 200
            * match response.ok == true
            """);
        assertPassed(sr);
    }

    @Test
    void testRequestWithNestedEmbeddedExpressions() {
        InMemoryHttpClient client = new InMemoryHttpClient(req -> {
            Object body = req.getBodyConverted();
            return json(Json.stringifyStrict(body));
        });

        ScenarioRuntime sr = run(client, """
            * url 'http://test'
            * def user = { name: 'John', age: 30 }
            * def items = ['a', 'b', 'c']
            * request { user: '#(user)', items: '#(items)', count: '#(items.length)' }
            * method post
            * status 200
            * match response.user == { name: 'John', age: 30 }
            * match response.items == ['a', 'b', 'c']
            * match response.count == 3
            """);
        assertPassed(sr);
    }

    @Test
    void testRequestWithOptionalEmbeddedExpressions() {
        InMemoryHttpClient client = new InMemoryHttpClient(req -> {
            Object body = req.getBodyConverted();
            return json(Json.stringifyStrict(body));
        });

        ScenarioRuntime sr = run(client, """
            * url 'http://test'
            * def name = 'John'
            * def missing = null
            * request { name: '#(name)', optional: '##(missing)' }
            * method post
            * status 200
            * match response == { name: 'John' }
            """);
        assertPassed(sr);
    }

    // ========== Header Name Tests ==========

    @Test
    void testHeaderWithHyphenatedName() {
        InMemoryHttpClient client = new InMemoryHttpClient(req -> {
            String contentType = req.getHeader("Content-Type");
            if ("application/json".equals(contentType)) {
                return json("{ \"ok\": true }");
            }
            return json("{ \"contentType\": \"" + contentType + "\" }");
        });

        ScenarioRuntime sr = run(client, """
            * url 'http://test'
            * header Content-Type = 'application/json'
            * method get
            * status 200
            * match response.ok == true
            """);
        assertPassed(sr);
    }

    @Test
    void testHeaderWithLowercaseHyphenatedName() {
        InMemoryHttpClient client = new InMemoryHttpClient(req -> {
            String contentType = req.getHeader("content-type");
            if ("application/json".equals(contentType)) {
                return json("{ \"ok\": true }");
            }
            return json("{ \"contentType\": \"" + contentType + "\" }");
        });

        ScenarioRuntime sr = run(client, """
            * url 'http://test'
            * header content-type = 'application/json'
            * method get
            * status 200
            * match response.ok == true
            """);
        assertPassed(sr);
    }

    @Test
    void testMatchResponseHeaderExpression() {
        // V1 supports "match header <name>" to access response headers
        InMemoryHttpClient client = new InMemoryHttpClient(req -> {
            HttpResponse resp = json("{ \"ok\": true }");
            resp.setHeader("Content-Type", "application/json; charset=utf-8");
            return resp;
        });

        ScenarioRuntime sr = run(client, """
            * url 'http://test'
            * method get
            * status 200
            * match header Content-Type contains 'application/json'
            """);
        assertPassed(sr);
    }

    @Test
    void testMatchResponseHeaderCaseInsensitive() {
        // V1 behavior: header name lookup is case-insensitive
        InMemoryHttpClient client = new InMemoryHttpClient(req -> {
            HttpResponse resp = json("{ \"ok\": true }");
            resp.setHeader("Content-Type", "application/json");
            return resp;
        });

        ScenarioRuntime sr = run(client, """
            * url 'http://test'
            * method get
            * status 200
            * match header content-type contains 'application/json'
            """);
        assertPassed(sr);
    }

}
