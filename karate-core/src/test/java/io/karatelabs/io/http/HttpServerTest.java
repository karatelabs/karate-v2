package io.karatelabs.io.http;

import io.karatelabs.common.Resource;
import io.karatelabs.core.KarateContext;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class HttpServerTest {

    static final Logger logger = LoggerFactory.getLogger(HttpServerTest.class);

    @Test
    void testServerWithJavaClient() {
        HttpServer server = HttpServer.start(0);
        int port = server.getPort();
        try (ApacheHttpClient client = new ApacheHttpClient()) {
            HttpRequestBuilder http = new HttpRequestBuilder(client);
            http.url("http://localhost:" + port);
            http.path("cats");
            http.method("post");
            http.bodyJson("{ name: 'Billie' }");
            HttpResponse response = http.invoke();
            String message = response.getBodyString();
            logger.debug("response: {}", message);
            http = new HttpRequestBuilder(client);
            http.url("http://localhost:" + port);
            http.path("cats");
            http.method("get");
            response = http.invoke();
            message = response.getBodyString();
            logger.debug("response: {}", message);
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            server.stop();
        }
    }

    @Test
    void testServerWithJsClient() {
        HttpServer server = HttpServer.start(0);
        int port = server.getPort();
        KarateContext context = new KarateContext(Resource.get(""));
        String js = "var http = karate.http('http://localhost:" + port + "');\n"
                + "var response = http.path('cats').post({ name: 'Billie' }).body;\n"
                + "console.log('response 1:', response);\n"
                + "response = http.path('cats').get();\n"
                + "console.log('response 2:', response);\n";
        context.eval(js);
        server.stop();
    }

}
