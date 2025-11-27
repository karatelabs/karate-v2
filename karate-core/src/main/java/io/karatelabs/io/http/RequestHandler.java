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
package io.karatelabs.io.http;

import io.karatelabs.common.FileUtils;
import io.karatelabs.common.Resource;
import io.karatelabs.common.ResourceType;
import io.karatelabs.js.Engine;
import io.karatelabs.markup.Markup;
import io.karatelabs.markup.ResourceResolver;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * Main request handler implementing routing for static files, API endpoints, and HTML templates.
 * Implements Function&lt;HttpRequest, HttpResponse&gt; for use with HttpServer.
 * <p>
 * Routing logic:
 * <ol>
 *   <li>If path starts with static prefix (e.g., /pub/) → serve static file</li>
 *   <li>If path starts with API prefix (e.g., /api/) → execute JS file</li>
 *   <li>Otherwise → render HTML template</li>
 * </ol>
 * <p>
 * Usage:
 * <pre>
 * ServerConfig config = new ServerConfig()
 *     .resourceRoot("classpath:web")
 *     .sessionStore(new InMemorySessionStore());
 *
 * RequestHandler handler = new RequestHandler(config, resolver);
 * HttpServer server = HttpServer.start(8080, handler);
 * </pre>
 */
public class RequestHandler implements Function<HttpRequest, HttpResponse> {

    private final ServerConfig config;
    private final ResourceResolver resolver;
    private final Markup markup;
    private final Engine engine;

    public RequestHandler(ServerConfig config, ResourceResolver resolver) {
        this.config = config;
        this.resolver = resolver;
        this.engine = new Engine();
        this.markup = Markup.init(engine, resolver);
    }

    @Override
    public HttpResponse apply(HttpRequest request) {
        HttpResponse response = new HttpResponse();
        String path = request.getPath();

        try {
            // Process form/multipart body if present
            request.processBody();

            // Create context for this request
            ServerContext context = createContext(request, response);

            // Load existing session from cookie if present
            loadSession(request, context);

            // Call request interceptor if configured
            if (config.getRequestInterceptor() != null) {
                config.getRequestInterceptor().accept(request);
            }

            // Route the request
            HttpResponse result = route(request, response, context);

            // Handle redirect if set
            if (context.hasRedirect()) {
                result.setStatus(302);
                result.setHeader("Location", context.getRedirectPath());
                result.setBody("");
            }

            // Save session if modified
            saveSession(context, result);

            return result;

        } catch (RedirectException e) {
            response.setStatus(302);
            response.setHeader("Location", e.getLocation());
            return response;

        } catch (Exception e) {
            return handleError(request, response, e);
        }
    }

    private ServerContext createContext(HttpRequest request, HttpResponse response) {
        ServerContext context = new ServerContext(request, response, config);
        context.setResourceResolver(path -> resolver.resolve(path, null));
        return context;
    }

    private void loadSession(HttpRequest request, ServerContext context) {
        if (!config.isSessionEnabled()) {
            return;
        }
        String sessionId = getSessionIdFromCookie(request);
        if (sessionId != null) {
            Session session = config.getSessionStore().get(sessionId);
            if (session != null) {
                context.setSession(session);
            }
        }
    }

    private String getSessionIdFromCookie(HttpRequest request) {
        String cookieHeader = request.getHeader("Cookie");
        if (cookieHeader == null) {
            return null;
        }
        // Parse cookie header: "name1=value1; name2=value2"
        String cookieName = config.getSessionCookieName();
        for (String part : cookieHeader.split(";")) {
            String trimmed = part.trim();
            if (trimmed.startsWith(cookieName + "=")) {
                return trimmed.substring(cookieName.length() + 1);
            }
        }
        return null;
    }

    private void saveSession(ServerContext context, HttpResponse response) {
        Session session = context.getSession();
        if (session == null || session.isTemporary()) {
            return;
        }
        // Save to store
        config.getSessionStore().save(session);

        // Set cookie if new session
        String cookieValue = config.getSessionCookieName() + "=" + session.getId() + "; Path=/; HttpOnly";
        if (!config.isDevMode()) {
            cookieValue += "; Secure; SameSite=Strict";
        }
        response.setHeader("Set-Cookie", cookieValue);
    }

    private HttpResponse route(HttpRequest request, HttpResponse response, ServerContext context) {
        String path = request.getPath();

        // 1. Static files
        if (config.isStaticPath(path)) {
            return handleStatic(request, response, context);
        }

        // 2. API routes
        if (config.isApiPath(path)) {
            return handleApi(request, response, context);
        }

        // 3. Template routes
        return handleTemplate(request, response, context);
    }

    private HttpResponse handleStatic(HttpRequest request, HttpResponse response, ServerContext context) {
        String path = request.getPath();

        // Use path without leading slash as resource path
        // e.g., /pub/app.js -> pub/app.js
        String resourcePath = path.startsWith("/") ? path.substring(1) : path;

        try {
            Resource resource = resolver.resolve(resourcePath, null);
            if (resource == null) {
                return notFound(response, path);
            }

            byte[] content = FileUtils.toBytes(resource.getStream());

            // Determine content type based on extension
            ResourceType resourceType = ResourceType.fromFileExtension(resourcePath);
            if (resourceType != null) {
                response.setBody(content, resourceType);
            } else {
                // Fallback for unknown types
                response.setBody(content, ResourceType.BINARY);
                response.setHeader("Content-Type", getContentType(resourcePath));
            }

            // Cache headers
            if (config.isDevMode()) {
                response.setHeader("Cache-Control", "no-cache, no-store");
            } else {
                response.setHeader("Cache-Control", "public, max-age=86400"); // 1 day
            }

            return response;

        } catch (Exception e) {
            return notFound(response, path);
        }
    }

    private HttpResponse handleApi(HttpRequest request, HttpResponse response, ServerContext context) {
        String path = request.getPath();

        // Convert API path to JS file path: /api/users -> api/users.js
        String jsPath = path.substring(1); // Remove leading /
        if (!jsPath.endsWith(".js")) {
            jsPath = jsPath + ".js";
        }

        try {
            Resource resource = resolver.resolve(jsPath, null);
            if (resource == null) {
                return notFound(response, path);
            }

            String jsCode = resource.getText();

            // Create fresh engine for this request
            Engine reqEngine = new Engine();
            reqEngine.put("request", request);
            reqEngine.put("response", response);
            reqEngine.put("context", context);
            if (context.getSession() != null) {
                reqEngine.put("session", context.getSession());
            }

            // Execute the JS
            reqEngine.eval(jsCode);

            // Set default content type if not set
            if (response.getHeader("Content-Type") == null) {
                Object body = response.getBody();
                if (body instanceof Map || body instanceof java.util.List) {
                    response.setHeader("Content-Type", "application/json");
                }
            }

            return response;

        } catch (Exception e) {
            return handleError(request, response, e);
        }
    }

    private HttpResponse handleTemplate(HttpRequest request, HttpResponse response, ServerContext context) {
        String path = request.getPath();

        // Convert path to template: /signin -> signin.html, / -> index.html
        String templatePath = path.equals("/") ? "index.html" : path.substring(1);
        if (!templatePath.endsWith(".html")) {
            templatePath = templatePath + ".html";
        }

        try {
            // Check if template exists
            Resource templateResource = resolver.resolve(templatePath, null);
            if (templateResource == null) {
                return notFound(response, path);
            }

            // Set template name in context
            context.setTemplateName(templatePath);

            // Prepare variables for template
            Map<String, Object> vars = new HashMap<>();
            vars.put("request", request);
            vars.put("response", response);
            vars.put("context", context);
            if (context.getSession() != null) {
                vars.put("session", context.getSession());
            }

            // Render template
            String html = markup.processPath(templatePath, vars);

            // Check if template switched
            if (context.isSwitched()) {
                String newTemplate = context.getSwitchTemplate();
                context.setTemplateName(newTemplate);
                html = markup.processPath(newTemplate, vars);
            }

            response.setBody(html);
            response.setHeader("Content-Type", "text/html; charset=utf-8");

            return response;

        } catch (Exception e) {
            return handleError(request, response, e);
        }
    }

    private HttpResponse handleError(HttpRequest request, HttpResponse response, Exception e) {
        response.setStatus(500);

        // Try custom error template
        if (config.getErrorTemplate500() != null) {
            try {
                Map<String, Object> vars = new HashMap<>();
                vars.put("error", e.getMessage());
                vars.put("request", request);
                String html = markup.processPath(config.getErrorTemplate500(), vars);
                response.setBody(html);
                response.setHeader("Content-Type", "text/html; charset=utf-8");
                return response;
            } catch (Exception ignored) {
            }
        }

        // Fallback to simple text
        response.setBody("Internal Server Error: " + e.getMessage());
        response.setHeader("Content-Type", "text/plain");
        return response;
    }

    private HttpResponse notFound(HttpResponse response, String path) {
        response.setStatus(404);

        // Try custom 404 template
        if (config.getErrorTemplate404() != null) {
            try {
                Map<String, Object> vars = new HashMap<>();
                vars.put("path", path);
                String html = markup.processPath(config.getErrorTemplate404(), vars);
                response.setBody(html);
                response.setHeader("Content-Type", "text/html; charset=utf-8");
                return response;
            } catch (Exception ignored) {
            }
        }

        // Fallback to simple text
        response.setBody("Not Found: " + path);
        response.setHeader("Content-Type", "text/plain");
        return response;
    }

    private String getContentType(String path) {
        ResourceType type = ResourceType.fromFileExtension(path);
        if (type != null) {
            return type.contentType;
        }
        // Fallback for common types
        if (path.endsWith(".js")) return "application/javascript";
        if (path.endsWith(".css")) return "text/css";
        if (path.endsWith(".html")) return "text/html";
        if (path.endsWith(".json")) return "application/json";
        if (path.endsWith(".png")) return "image/png";
        if (path.endsWith(".jpg") || path.endsWith(".jpeg")) return "image/jpeg";
        if (path.endsWith(".svg")) return "image/svg+xml";
        if (path.endsWith(".ico")) return "image/x-icon";
        if (path.endsWith(".woff")) return "font/woff";
        if (path.endsWith(".woff2")) return "font/woff2";
        return "application/octet-stream";
    }

    // Getter for testing
    public ServerConfig getConfig() {
        return config;
    }

    /**
     * Exception used internally to signal a redirect.
     */
    public static class RedirectException extends RuntimeException {
        private final String location;

        public RedirectException(String location) {
            super("Redirect to: " + location);
            this.location = location;
        }

        public String getLocation() {
            return location;
        }
    }

}
