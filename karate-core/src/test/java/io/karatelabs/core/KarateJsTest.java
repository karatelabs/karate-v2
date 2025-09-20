package io.karatelabs.core;

import io.karatelabs.common.Json;
import io.karatelabs.common.Resource;
import io.karatelabs.io.http.ErrorHttpClient;
import io.karatelabs.io.http.HttpServer;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class KarateJsTest {

    @Test
    void testJsClientJsServer() {
        HttpServer server = HttpServer.start(0);
        int port = server.getPort();
        KarateJs context = new KarateJs(Resource.path(""));
        String js = """
                var http = karate.http('http://localhost:%s');
                var response = http.path('cats').post({ name: 'Billie' }).body;
                console.log('response 1:', response);
                response = http.path('cats').get();
                console.log('response 2:', response);
                """.formatted(port);
        context.engine.eval(js);
        server.stop();
    }

    @Test
    void testJsClientNoServerConnection() {
        KarateJs context = new KarateJs(Resource.path(""), new ErrorHttpClient());
        String js = """
                var http = karate.http('http://localhost:99');
                var response = http.path('cats').post({ name: 'Billie' }).body;
                """;
        try {
            context.engine.eval(js);
        } catch (Exception e) {
            assertTrue(e.getMessage().contains("expression: http.path('cats').post({name:'Billie'}) - failed"));
        }
    }

    @Test
    void testRead() {
        KarateJs context = new KarateJs(Resource.path("src/test/resources"));
        String js = """
                var cat = read('json/cat.json');
                """;
        context.engine.eval(js);
        assertEquals(Map.of("name", "Billie", "age", 5), context.engine.get("cat"));
    }

    @Test
    void testMatch() {
        KarateJs context = new KarateJs(Resource.path("src/test/resources"));
        String js = """
                var cat = read('json/cat.json');
                var result = match(cat).contains({ name: 'Billie' });
                """;
        context.engine.eval(js);
        assertEquals(Json.of("{ pass: true, message: null }").asMap(), context.engine.get("result"));
    }

    @Test
    void testMatchFail() {
        KarateJs context = new KarateJs(Resource.path("src/test/resources"));
        String js = """
                var cat = read('json/cat.json');
                match(cat).equals({ name: 'Billie', age: 4 });
                """;
        try {
            context.engine.eval(js);
        } catch (Exception e) {
            assertEquals("""
                    js failed:
                    ==========
                    match(cat).equals({ name: 'Billie', age: 4 });
                    2:1 match failed: EQUALS
                      $ | not equal | match failed for name: 'age' (MAP:MAP)
                      {"name":"Billie","age":5}
                      {"name":"Billie","age":4}
                    
                        $.age | not equal (NUMBER:NUMBER)
                        5
                        4
                    ----------
                    """, e.getMessage());
        }
    }

}
