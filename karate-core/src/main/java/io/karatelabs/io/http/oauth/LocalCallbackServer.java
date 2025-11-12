package io.karatelabs.io.http.oauth;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Local HTTP server to capture OAuth redirect with authorization code.
 * Listens on http://127.0.0.1:PORT/callback
 */
public class LocalCallbackServer {

    private static final Logger logger = LoggerFactory.getLogger(LocalCallbackServer.class);

    private HttpServer server;
    private CompletableFuture<String> codeFuture;
    private int port;

    /**
     * Start server on random available port
     */
    public String start() throws IOException {
        return start(0);
    }

    /**
     * Start server on specific port (0 = random)
     * @return The redirect URI: http://127.0.0.1:PORT/callback
     */
    public String start(int preferredPort) throws IOException {
        codeFuture = new CompletableFuture<>();

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", preferredPort), 0);
        port = server.getAddress().getPort();

        logger.info("OAuth callback server started on port {}", port);

        server.createContext("/callback", this::handleCallback);
        server.setExecutor(null); // Use default executor
        server.start();

        return "http://127.0.0.1:" + port + "/callback";
    }

    private void handleCallback(HttpExchange exchange) throws IOException {
        String query = exchange.getRequestURI().getQuery();
        Map<String, String> params = parseQuery(query);

        String code = params.get("code");
        String error = params.get("error");
        String state = params.get("state");

        logger.debug("Received OAuth callback: code={}, error={}, state={}",
            code != null ? "present" : "null", error, state);

        if (code != null) {
            codeFuture.complete(code);
            sendSuccessPage(exchange);
        } else {
            String errorDesc = params.getOrDefault("error_description", "Unknown error");
            codeFuture.completeExceptionally(
                new OAuth2Exception(error + ": " + errorDesc)
            );
            sendErrorPage(exchange, error, errorDesc);
        }
    }

    private Map<String, String> parseQuery(String query) {
        Map<String, String> params = new HashMap<>();
        if (query == null || query.isEmpty()) {
            return params;
        }

        for (String param : query.split("&")) {
            String[] parts = param.split("=", 2);
            if (parts.length == 2) {
                String key = URLDecoder.decode(parts[0], StandardCharsets.UTF_8);
                String value = URLDecoder.decode(parts[1], StandardCharsets.UTF_8);
                params.put(key, value);
            }
        }
        return params;
    }

    private void sendSuccessPage(HttpExchange exchange) throws IOException {
        String html = """
            <!DOCTYPE html>
            <html>
            <head>
                <title>Authorization Successful</title>
                <style>
                    body { font-family: Arial, sans-serif; text-align: center; padding: 50px; }
                    h1 { color: #4caf50; }
                    p { color: #666; }
                </style>
            </head>
            <body>
                <h1>✓ Authorization Successful</h1>
                <p>You can close this window and return to the application.</p>
            </body>
            </html>
            """;
        sendResponse(exchange, 200, html);
    }

    private void sendErrorPage(HttpExchange exchange, String error, String description) throws IOException {
        String html = String.format("""
            <!DOCTYPE html>
            <html>
            <head>
                <title>Authorization Failed</title>
                <style>
                    body { font-family: Arial, sans-serif; text-align: center; padding: 50px; }
                    h1 { color: #f44336; }
                    p { color: #666; }
                    code { background: #f5f5f5; padding: 2px 6px; border-radius: 4px; }
                </style>
            </head>
            <body>
                <h1>✗ Authorization Failed</h1>
                <p><strong>Error:</strong> <code>%s</code></p>
                <p>%s</p>
            </body>
            </html>
            """, error, description);
        sendResponse(exchange, 400, html);
    }

    private void sendResponse(HttpExchange exchange, int statusCode, String html) throws IOException {
        byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    public CompletableFuture<String> getCodeFuture() {
        return codeFuture;
    }

    public int getPort() {
        return port;
    }

    public void stop() {
        if (server != null) {
            logger.info("Stopping OAuth callback server on port {}", port);
            server.stop(0);
        }
    }
}
