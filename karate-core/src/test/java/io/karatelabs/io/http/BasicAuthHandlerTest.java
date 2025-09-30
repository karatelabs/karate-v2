package io.karatelabs.io.http;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BasicAuthHandlerTest {

    static final Logger logger = LoggerFactory.getLogger(BasicAuthHandlerTest.class);

    @Test
    void testBasicAuth() {
        HttpServer server = HttpServer.start(0, request -> {
            HttpResponse response = new HttpResponse();
            String authHeader = request.getHeader("authorization");
            response.setBody(authHeader);
            return response;
        });
        int port = server.getPort();
        try (HttpClient client = new ApacheHttpClient()) {
            HttpRequestBuilder http = new HttpRequestBuilder(client);
            BasicAuthHandler handler = new BasicAuthHandler("admin", "password");
            http.auth(handler);
            http.url("http://localhost:" + port);
            http.method("get");
            HttpResponse response = http.invoke();
            String body = response.getBodyString();
            assertEquals("Basic YWRtaW46cGFzc3dvcmQ=", body);
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            server.stop();
        }
    }

}
