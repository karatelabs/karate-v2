package io.karatelabs.http;

import io.karatelabs.common.Resource;
import io.karatelabs.markup.ResourceResolver;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for RequestHandler using InMemoryTestHarness.
 * No real HTTP server needed - tests handler logic directly.
 */
class RequestHandlerTest {

    static InMemoryTestHarness harness;
    static InMemorySessionStore sessionStore;
    static Map<String, String> resources;

    @BeforeAll
    static void beforeAll() {
        sessionStore = new InMemorySessionStore();
        resources = new HashMap<>();

        // Set up test resources
        resources.put("index.html", "<html><body>Welcome</body></html>");
        resources.put("signin.html", "<html><body>Sign In Page</body></html>");
        resources.put("api/hello.js", "response.body = { message: 'hello ' + request.param('name') }");
        resources.put("api/session.js", "context.init(); response.body = { sessionId: context.sessionId }");
        resources.put("api/check-session.js", "response.body = { hasSession: !!session, sessionId: context.sessionId }");
        resources.put("api/redirect.js", "context.redirect('/other')");
        resources.put("pub/app.js", "console.log('app');");
        resources.put("pub/style.css", "body { color: red; }");

        ResourceResolver resolver = (path, caller) -> {
            String content = resources.get(path);
            if (content == null) {
                return null; // Let RequestHandler handle 404
            }
            return Resource.text(content);
        };

        ServerConfig config = new ServerConfig()
                .sessionStore(sessionStore)
                .sessionExpirySeconds(600)
                .apiPrefix("/api/")
                .staticPrefix("/pub/");

        RequestHandler handler = new RequestHandler(config, resolver);
        harness = new InMemoryTestHarness(handler);
    }

    @BeforeEach
    void setUp() {
        sessionStore.clear();
    }

    private HttpResponse get(String path) {
        return harness.get(path);
    }

    private HttpResponse getWithCookie(String path, String cookie) {
        return harness.request()
                .path(path)
                .header("Cookie", cookie)
                .get();
    }

    // Template routing tests

    @Test
    void testRootTemplate() {
        HttpResponse response = get("/");

        assertEquals(200, response.getStatus());
        assertTrue(response.getBodyString().contains("Welcome"));
        assertTrue(response.getHeader("Content-Type").contains("text/html"));
    }

    @Test
    void testNamedTemplate() {
        HttpResponse response = get("/signin");

        assertEquals(200, response.getStatus());
        assertTrue(response.getBodyString().contains("Sign In"));
    }

    @Test
    void testTemplateNotFound() {
        HttpResponse response = get("/nonexistent");

        assertEquals(404, response.getStatus());
        assertTrue(response.getBodyString().contains("Not Found"));
    }

    // API routing tests

    @Test
    void testApiRoute() {
        HttpResponse response = get("/api/hello?name=world");

        assertEquals(200, response.getStatus());
        String body = response.getBodyString();
        assertTrue(body.contains("hello"));
        assertTrue(body.contains("world"));
    }

    @Test
    void testApiSession() {
        HttpResponse response = get("/api/session");

        assertEquals(200, response.getStatus());
        String body = response.getBodyString();
        assertTrue(body.contains("sessionId"));

        // Check session cookie was set
        String setCookie = response.getHeader("Set-Cookie");
        assertNotNull(setCookie);
        assertTrue(setCookie.contains("karate.sid="));

        // Session should be created in store
        assertEquals(1, sessionStore.size());
    }

    @Test
    void testApiRedirect() {
        // In-memory harness doesn't follow redirects, so we see the 302 directly
        HttpResponse response = get("/api/redirect");

        assertEquals(302, response.getStatus());
        assertEquals("/other", response.getHeader("Location"));
    }

    @Test
    void testApiNotFound() {
        HttpResponse response = get("/api/nonexistent");

        assertEquals(404, response.getStatus());
    }

    // Static file routing tests

    @Test
    void testStaticJs() {
        HttpResponse response = get("/pub/app.js");

        assertEquals(200, response.getStatus());
        assertTrue(response.getBodyString().contains("console.log"));
        String contentType = response.getHeader("Content-Type");
        assertTrue(contentType.contains("javascript") || contentType.contains("application/javascript"));
    }

    @Test
    void testStaticCss() {
        HttpResponse response = get("/pub/style.css");

        assertEquals(200, response.getStatus());
        assertTrue(response.getBodyString().contains("color: red"));
        String contentType = response.getHeader("Content-Type");
        assertTrue(contentType.contains("css") || contentType.contains("text/css"));
    }

    @Test
    void testStaticNotFound() {
        HttpResponse response = get("/pub/nonexistent.js");

        assertEquals(404, response.getStatus());
    }

    // Session tests

    @Test
    void testSessionPersistence() {
        // First request creates session
        HttpResponse response1 = get("/api/session");
        String setCookie = response1.getHeader("Set-Cookie");
        assertNotNull(setCookie);

        // Extract session ID from cookie
        String sessionCookie = setCookie.split(";")[0]; // "karate.sid=xyz"

        // Second request with cookie should use same session (doesn't call init)
        HttpResponse response2 = getWithCookie("/api/check-session", sessionCookie);

        // Should not create new session (still 1 in store)
        assertEquals(1, sessionStore.size());
        assertTrue(response2.getBodyString().contains("hasSession"));
    }

    @Test
    void testSessionExpired() {
        // Create a session
        Session session = sessionStore.create(1); // 1 second expiry
        String sessionId = session.getId();

        // Wait for expiry
        try {
            Thread.sleep(1100);
        } catch (InterruptedException ignored) {
        }

        // Request with expired session cookie should not find session
        String cookie = "karate.sid=" + sessionId;
        HttpResponse response = getWithCookie("/", cookie);

        // Should still get 200 (just no session)
        assertEquals(200, response.getStatus());
    }

    // Security headers tests

    @Test
    void testSecurityHeadersOnHtmlResponse() {
        HttpResponse response = get("/");

        assertEquals(200, response.getStatus());
        assertTrue(response.getHeader("Content-Type").contains("text/html"));

        // Security headers should be applied to HTML responses
        assertEquals("nosniff", response.getHeader("X-Content-Type-Options"));
        assertEquals("DENY", response.getHeader("X-Frame-Options"));
        assertEquals("1; mode=block", response.getHeader("X-XSS-Protection"));
        assertEquals("strict-origin-when-cross-origin", response.getHeader("Referrer-Policy"));
    }

    @Test
    void testSecurityHeadersNotOnApiResponse() {
        HttpResponse response = get("/api/hello?name=test");

        assertEquals(200, response.getStatus());

        // Security headers should NOT be applied to non-HTML responses
        assertNull(response.getHeader("X-Content-Type-Options"));
        assertNull(response.getHeader("X-Frame-Options"));
    }

    @Test
    void testSecurityHeadersNotOnStaticJs() {
        HttpResponse response = get("/pub/app.js");

        assertEquals(200, response.getStatus());

        // Security headers should NOT be applied to static JS
        assertNull(response.getHeader("X-Content-Type-Options"));
        assertNull(response.getHeader("X-Frame-Options"));
    }

    // CSRF validation tests

    @Test
    void testCsrfValidationBlocksPostWithoutToken() {
        // Create a session first
        HttpResponse sessionResponse = get("/api/session");
        String setCookie = sessionResponse.getHeader("Set-Cookie");
        String sessionCookie = setCookie.split(";")[0];

        // POST without CSRF token should be blocked
        HttpResponse response = harness.request()
                .path("/api/hello")
                .header("Cookie", sessionCookie)
                .post();

        assertEquals(403, response.getStatus());
        assertTrue(response.getBodyString().contains("CSRF"));
    }

    @Test
    void testCsrfValidationAllowsPostWithValidToken() {
        // Create a session and get the CSRF token
        Session session = sessionStore.create(600);
        String csrfToken = CsrfProtection.getOrCreateToken(session);
        String sessionCookie = "karate.sid=" + session.getId();

        // POST with valid CSRF token should succeed
        HttpResponse response = harness.request()
                .path("/api/hello?name=test")
                .header("Cookie", sessionCookie)
                .header("X-CSRF-Token", csrfToken)
                .post();

        assertEquals(200, response.getStatus());
    }

    @Test
    void testCsrfValidationAllowsPostWithHtmxHeader() {
        // Create a session and get the CSRF token
        Session session = sessionStore.create(600);
        String csrfToken = CsrfProtection.getOrCreateToken(session);
        String sessionCookie = "karate.sid=" + session.getId();

        // POST with HX-CSRF-Token header should succeed
        HttpResponse response = harness.request()
                .path("/api/hello?name=test")
                .header("Cookie", sessionCookie)
                .header("HX-CSRF-Token", csrfToken)
                .post();

        assertEquals(200, response.getStatus());
    }

    @Test
    void testCsrfValidationAllowsGetRequests() {
        // GET requests should not require CSRF token
        HttpResponse response = get("/api/hello?name=world");

        assertEquals(200, response.getStatus());
    }

    @Test
    void testCsrfValidationBlocksInvalidToken() {
        // Create a session
        Session session = sessionStore.create(600);
        CsrfProtection.getOrCreateToken(session); // Generate the real token
        String sessionCookie = "karate.sid=" + session.getId();

        // POST with invalid CSRF token should be blocked
        HttpResponse response = harness.request()
                .path("/api/hello")
                .header("Cookie", sessionCookie)
                .header("X-CSRF-Token", "invalid-token")
                .post();

        assertEquals(403, response.getStatus());
    }

    // Path traversal protection tests

    @Test
    void testPathTraversalBlockedOnStatic() {
        HttpResponse response = get("/pub/../../../etc/passwd");

        assertEquals(403, response.getStatus());
        assertTrue(response.getBodyString().contains("Forbidden"));
    }

    @Test
    void testPathTraversalBlockedOnApi() {
        HttpResponse response = get("/api/../../../etc/passwd");

        assertEquals(403, response.getStatus());
        assertTrue(response.getBodyString().contains("Forbidden"));
    }

    @Test
    void testPathTraversalBlockedOnTemplate() {
        HttpResponse response = get("/../../../etc/passwd");

        assertEquals(403, response.getStatus());
        assertTrue(response.getBodyString().contains("Forbidden"));
    }

    @Test
    void testEncodedPathTraversalBlocked() {
        // %2e%2e = ".."
        HttpResponse response = get("/pub/%2e%2e/%2e%2e/etc/passwd");

        assertEquals(403, response.getStatus());
        assertTrue(response.getBodyString().contains("Forbidden"));
    }

}
