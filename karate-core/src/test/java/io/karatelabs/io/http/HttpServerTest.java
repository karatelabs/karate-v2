package io.karatelabs.io.http;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class HttpServerTest {

    static final Logger logger = LoggerFactory.getLogger(HttpServerTest.class);

    @Test
    void testJavaClientJsServer() {
        HttpServer server = HttpServer.start(0);
        int port = server.getPort();
        try (HttpClient client = new ApacheHttpClient()) {
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

}
