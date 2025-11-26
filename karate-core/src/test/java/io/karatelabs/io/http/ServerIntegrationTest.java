package io.karatelabs.io.http;

import io.karatelabs.common.Resource;
import io.karatelabs.core.KarateJs;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests demonstrating full request flow:
 * HTTP request -> server handler -> JS evaluation -> response
 * HTTP request -> server handler -> HTML template rendering -> response
 */
class ServerIntegrationTest {

    static final Logger logger = LoggerFactory.getLogger(ServerIntegrationTest.class);

    static ServerTestHarness harness;

    @BeforeAll
    static void beforeAll() {
        harness = new ServerTestHarness("classpath:markup");
        harness.start();
    }

    @AfterAll
    static void afterAll() {
        harness.stop();
    }

    // ========== JS Evaluation Tests ==========

    @Test
    void testJsEvalSimple() {
        harness.setHandler(ctx -> {
            ctx.eval("response.body = 'hello world'");
            return ctx.response();
        });

        HttpResponse response = harness.get("/test");
        assertEquals(200, response.getStatus());
        assertEquals("hello world", response.getBodyString());
    }

    @Test
    void testJsEvalRequestParam() {
        harness.setHandler(ctx -> {
            ctx.eval("response.body = 'hello ' + request.param('name')");
            return ctx.response();
        });

        HttpResponse response = harness.get("/test?name=karate");
        assertEquals("hello karate", response.getBodyString());
    }

    @Test
    void testJsEvalRequestParamInt() {
        harness.setHandler(ctx -> {
            ctx.eval("var count = request.paramInt('count'); response.body = 'count is ' + (count * 2)");
            return ctx.response();
        });

        HttpResponse response = harness.get("/test?count=21");
        assertEquals("count is 42", response.getBodyString());
    }

    @Test
    void testJsEvalRequestMethod() {
        harness.setHandler(ctx -> {
            ctx.eval("response.body = request.get ? 'GET request' : 'not GET'");
            return ctx.response();
        });

        HttpResponse response = harness.get("/test");
        assertEquals("GET request", response.getBodyString());
    }

    @Test
    void testJsEvalPostMethod() {
        harness.setHandler(ctx -> {
            ctx.eval("response.body = request.post ? 'POST request' : 'not POST'");
            return ctx.response();
        });

        HttpResponse response = harness.post("/test", "data");
        assertEquals("POST request", response.getBodyString());
    }

    @Test
    void testJsEvalSession() {
        harness.setHandler(ctx -> {
            ctx.eval("session.user = 'john'; response.body = 'user: ' + session.user");
            return ctx.response();
        });

        HttpResponse response = harness.get("/test");
        assertEquals("user: john", response.getBodyString());
    }

    @Test
    void testJsEvalResponseStatus() {
        harness.setHandler(ctx -> {
            ctx.eval("response.status = 201; response.body = 'created'");
            return ctx.response();
        });

        HttpResponse response = harness.get("/test");
        assertEquals(201, response.getStatus());
        assertEquals("created", response.getBodyString());
    }

    @Test
    void testJsEvalJsonResponse() {
        harness.setHandler(ctx -> {
            ctx.eval("response.body = { name: 'karate', version: 2 }");
            return ctx.response();
        });

        HttpResponse response = harness.get("/test");
        assertTrue(response.getBodyString().contains("karate"));
        assertTrue(response.getBodyString().contains("2"));
    }

    // ========== HTML Template Rendering Tests ==========

    @Test
    void testTemplateRenderSimple() {
        harness.setHandler(ctx -> {
            String html = ctx.renderString("<div th:text=\"'hello world'\"></div>");
            ctx.response().setBody(html);
            return ctx.response();
        });

        HttpResponse response = harness.get("/test");
        assertEquals("<div>hello world</div>", response.getBodyString());
    }

    @Test
    void testTemplateRenderWithVariable() {
        harness.setHandler(ctx -> {
            String html = ctx.renderString("<div th:text=\"message\"></div>", Map.of("message", "greetings"));
            ctx.response().setBody(html);
            return ctx.response();
        });

        HttpResponse response = harness.get("/test");
        assertEquals("<div>greetings</div>", response.getBodyString());
    }

    @Test
    void testTemplateRenderRequestAccess() {
        harness.setHandler(ctx -> {
            // Template can access request object
            String html = ctx.renderString("<div th:text=\"request.param('name')\"></div>");
            ctx.response().setBody(html);
            return ctx.response();
        });

        HttpResponse response = harness.get("/test?name=karate");
        assertEquals("<div>karate</div>", response.getBodyString());
    }

    @Test
    void testTemplateRenderSessionAccess() {
        harness.setHandler(ctx -> {
            // Set session data in JS, access in template
            ctx.eval("session.username = 'john'");
            String html = ctx.renderString("<div th:text=\"session.username\"></div>");
            ctx.response().setBody(html);
            return ctx.response();
        });

        HttpResponse response = harness.get("/test");
        assertEquals("<div>john</div>", response.getBodyString());
    }

    @Test
    void testTemplateRenderConditional() {
        harness.setHandler(ctx -> {
            String html = ctx.renderString(
                    "<div th:if=\"show\">visible</div><div th:unless=\"show\">hidden</div>",
                    Map.of("show", true)
            );
            ctx.response().setBody(html);
            return ctx.response();
        });

        HttpResponse response = harness.get("/test");
        assertEquals("<div>visible</div>", response.getBodyString());
    }

    @Test
    void testTemplateRenderLoop() {
        harness.setHandler(ctx -> {
            String html = ctx.renderString(
                    "<ul><li th:each=\"item : items\" th:text=\"item\"></li></ul>",
                    Map.of("items", java.util.List.of("a", "b", "c"))
            );
            ctx.response().setBody(html);
            return ctx.response();
        });

        HttpResponse response = harness.get("/test");
        assertEquals("<ul><li>a</li><li>b</li><li>c</li></ul>", response.getBodyString());
    }

    @Test
    void testTemplateRenderFromFile() {
        harness.setHandler(ctx -> ctx.respondWithTemplate("temp.html"));

        HttpResponse response = harness.get("/test");
        assertEquals("text/html", response.getHeader("Content-Type"));
        assertTrue(response.getBodyString().contains("temp"));
    }

    // ========== Combined JS + Template Tests ==========

    @Test
    void testJsAndTemplateIntegration() {
        harness.setHandler(ctx -> {
            // Compute data in JS
            ctx.eval("var greeting = 'Hello, ' + request.param('name') + '!'");
            ctx.eval("session.greeting = greeting");

            // Render template with session data
            String html = ctx.renderString("<div th:text=\"session.greeting\"></div>");
            ctx.response().setBody(html);
            return ctx.response();
        });

        HttpResponse response = harness.get("/test?name=World");
        assertEquals("<div>Hello, World!</div>", response.getBodyString());
    }

    @Test
    void testJsModifiesResponseThenTemplate() {
        harness.setHandler(ctx -> {
            // JS sets status
            ctx.eval("response.status = 201");

            // Template sets body
            String html = ctx.renderString("<div>created</div>");
            ctx.response().setBody(html);
            ctx.response().setHeader("Content-Type", "text/html");
            return ctx.response();
        });

        HttpResponse response = harness.get("/test");
        assertEquals(201, response.getStatus());
        assertEquals("<div>created</div>", response.getBodyString());
    }

    // ========== JS HTTP Client Tests ==========

    @Test
    void testJsHttpClient() {
        // Test the JS karate.http() client making requests to our server
        harness.setHandler(ctx -> {
            ctx.response().setBody("hello world");
            return ctx.response();
        });

        KarateJs context = new KarateJs(Resource.path(""));
        String js = """
                var http = karate.http('http://localhost:%s');
                var response = http.path('cats').post({ name: 'Billie' });
                var body1 = response.body;
                response = http.path('cats').get();
                var body2 = response.body;
                """.formatted(harness.getPort());
        context.engine.eval(js);

        assertEquals("hello world", context.engine.get("body1"));
        assertEquals("hello world", context.engine.get("body2"));
    }

}
