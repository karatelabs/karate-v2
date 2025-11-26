package io.karatelabs.io.http;

import io.karatelabs.common.Resource;
import io.karatelabs.markup.ResourceResolver;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RequestHandlerTest {

    static HttpServer server;
    static HttpClient client;
    static int port;
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
        server = HttpServer.start(0, handler);
        port = server.getPort();
        client = new ApacheHttpClient();
    }

    @AfterAll
    static void afterAll() {
        if (client != null) {
            try {
                client.close();
            } catch (Exception ignored) {
            }
        }
        if (server != null) {
            server.stop();
        }
    }

    @BeforeEach
    void setUp() {
        sessionStore.clear();
    }

    private HttpResponse get(String pathWithParams) {
        HttpRequestBuilder builder = new HttpRequestBuilder(client)
                .url("http://localhost:" + port)
                .method("GET");

        // Handle query params in path: /api/hello?name=world
        int queryIdx = pathWithParams.indexOf('?');
        if (queryIdx >= 0) {
            builder.path(pathWithParams.substring(0, queryIdx));
            String queryString = pathWithParams.substring(queryIdx + 1);
            for (String param : queryString.split("&")) {
                String[] parts = param.split("=", 2);
                if (parts.length == 2) {
                    builder.param(parts[0], parts[1]);
                }
            }
        } else {
            builder.path(pathWithParams);
        }

        return builder.invoke();
    }

    private HttpResponse getWithCookie(String path, String cookie) {
        return new HttpRequestBuilder(client)
                .url("http://localhost:" + port)
                .path(path)
                .method("GET")
                .header("Cookie", cookie)
                .invoke();
    }

    private HttpResponse getNoRedirect(String path) {
        // Create client that doesn't follow redirects
        ApacheHttpClient noRedirectClient = new ApacheHttpClient();
        noRedirectClient.config("followRedirects", false);
        try {
            return new HttpRequestBuilder(noRedirectClient)
                    .url("http://localhost:" + port)
                    .path(path)
                    .method("GET")
                    .invoke();
        } finally {
            try {
                noRedirectClient.close();
            } catch (Exception ignored) {
            }
        }
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
        HttpResponse response = getNoRedirect("/api/redirect");

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

}
