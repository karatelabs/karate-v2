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

import io.karatelabs.common.FileUtils;
import io.karatelabs.common.Resource;
import io.karatelabs.common.ResourceType;
import io.karatelabs.common.StringUtils;
import io.karatelabs.common.Xml;
import net.minidev.json.JSONValue;
import io.karatelabs.gherkin.Feature;
import io.karatelabs.gherkin.FeatureSection;
import io.karatelabs.gherkin.Scenario;
import io.karatelabs.gherkin.Step;
import io.karatelabs.gherkin.Tag;
import io.karatelabs.io.http.HttpRequest;
import io.karatelabs.io.http.HttpResponse;
import io.karatelabs.js.Engine;
import io.karatelabs.js.Invokable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Node;

import com.jayway.jsonpath.JsonPath;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;

/**
 * Mock server request handler that routes requests to matching scenarios.
 * Implements Function&lt;HttpRequest, HttpResponse&gt; for use with HttpServer.
 */
public class MockHandler implements Function<HttpRequest, HttpResponse> {

    private static final Logger logger = LoggerFactory.getLogger(MockHandler.class);

    private static final String ALLOWED_METHODS = "GET, HEAD, POST, PUT, DELETE, PATCH, OPTIONS";

    // Thread-local for current request (used by matcher functions and karate.proceed())
    private static final ThreadLocal<HttpRequest> LOCAL_REQUEST = new ThreadLocal<>();

    /**
     * Get the current request being processed (for use in mock scenarios).
     * Used by karate.proceed() for proxy mode.
     */
    public static HttpRequest getCurrentRequest() {
        return LOCAL_REQUEST.get();
    }

    private final List<Feature> features = new ArrayList<>();
    private final Map<String, Object> globals = new LinkedHashMap<>();
    private final MockConfig config = new MockConfig();
    private final ReentrantLock requestLock = new ReentrantLock();
    private final KarateJs karateJs;
    private final Engine engine;
    private final String pathPrefix;

    // Constructed from feature file path
    public MockHandler(String featurePath) {
        this(Feature.read(featurePath), null);
    }

    public MockHandler(Feature feature) {
        this(feature, null);
    }

    public MockHandler(Feature feature, Map<String, Object> args) {
        this(List.of(feature), args, null);
    }

    public MockHandler(List<Feature> features, Map<String, Object> args, String pathPrefix) {
        this.pathPrefix = pathPrefix;

        // Use KarateJs to get access to karate.* functions (including proceed())
        Resource root = features.isEmpty() ? Resource.path(".") : features.get(0).getResource();
        this.karateJs = new KarateJs(root);
        this.engine = karateJs.engine;

        // Register matcher functions
        registerMatcherFunctions();

        // Initialize each feature
        for (Feature feature : features) {
            this.features.add(feature);
            initFeature(feature, args);
        }

        logger.info("mock handler initialized with {} feature(s), cors: {}", features.size(), config.isCorsEnabled());
    }

    private void registerMatcherFunctions() {
        engine.put("pathMatches", (Invokable) args -> pathMatches(args[0] + ""));
        engine.put("methodIs", (Invokable) args -> methodIs(args[0] + ""));
        engine.put("typeContains", (Invokable) args -> typeContains(args[0] + ""));
        engine.put("acceptContains", (Invokable) args -> acceptContains(args[0] + ""));
        engine.put("headerContains", (Invokable) args -> headerContains(args[0] + "", args[1] + ""));
        engine.put("paramValue", (Invokable) args -> paramValue(args[0] + ""));
        engine.put("paramExists", (Invokable) args -> paramExists(args[0] + ""));
        engine.put("bodyPath", (Invokable) args -> bodyPath(args[0] + ""));
    }

    private void initFeature(Feature feature, Map<String, Object> args) {
        // Put args into globals if provided
        if (args != null) {
            globals.putAll(args);
        }

        // Execute background once on initialization
        if (feature.isBackgroundPresent()) {
            for (Step step : feature.getBackground().getSteps()) {
                executeStep(step);
            }
        }

        logger.debug("initialized feature: {}", feature);
    }

    @Override
    public HttpResponse apply(HttpRequest request) {
        requestLock.lock();
        try {
            return handleRequest(request);
        } finally {
            requestLock.unlock();
        }
    }

    private HttpResponse handleRequest(HttpRequest request) {
        // Handle CORS preflight
        if (config.isCorsEnabled() && "OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return handleCorsPreFlight(request);
        }

        // Strip path prefix if configured
        if (pathPrefix != null && request.getPath().startsWith(pathPrefix)) {
            request.setPath(request.getPath().substring(pathPrefix.length()));
        }

        // Process body for form-urlencoded and multipart
        request.processBody();

        // Set thread-local request for matcher functions
        LOCAL_REQUEST.set(request);

        try {
            // Set up request variables in engine
            setupRequestVariables(request);

            // Find matching scenario and execute
            for (Feature feature : features) {
                for (FeatureSection section : feature.getSections()) {
                    if (section.isOutline()) {
                        logger.warn("skipping scenario outline in mock - {}:{}", feature, section.getScenarioOutline().getLine());
                        continue;
                    }

                    Scenario scenario = section.getScenario();
                    if (isMatchingScenario(scenario)) {
                        return executeScenario(scenario, request);
                    }
                }
            }

            // No match found - return 404
            logger.warn("no scenarios matched, returning 404: {} {}", request.getMethod(), request.getPath());
            return createNotFoundResponse();

        } finally {
            LOCAL_REQUEST.remove();
        }
    }

    private HttpResponse handleCorsPreFlight(HttpRequest request) {
        HttpResponse response = new HttpResponse();
        response.setStatus(200);
        response.setHeader("Allow", ALLOWED_METHODS);
        response.setHeader("Access-Control-Allow-Origin", "*");
        response.setHeader("Access-Control-Allow-Methods", ALLOWED_METHODS);
        List<String> requestHeaders = request.getHeaderValues("Access-Control-Request-Headers");
        if (requestHeaders != null && !requestHeaders.isEmpty()) {
            response.setHeader("Access-Control-Allow-Headers", requestHeaders.toArray(new String[0]));
        }
        return response;
    }

    private void setupRequestVariables(HttpRequest request) {
        // Set all globals first
        for (Map.Entry<String, Object> entry : globals.entrySet()) {
            engine.put(entry.getKey(), entry.getValue());
        }

        // Set request variables as per MOCKS.md specification
        engine.put("request", request.getBodyConverted());
        engine.put("requestBytes", request.getBody());
        engine.put("requestPath", request.getPath());
        engine.put("requestUri", request.getPathRaw());
        engine.put("requestUrlBase", request.jsGet("urlBase"));
        engine.put("requestMethod", request.getMethod());
        engine.put("requestHeaders", request.getHeaders());
        engine.put("requestParams", request.jsGet("params"));
        engine.put("requestParts", request.getMultiParts());

        // Initialize response variables with defaults
        engine.put("response", null);
        engine.put("responseStatus", 200);
        engine.put("responseHeaders", new HashMap<>());
        engine.put("responseDelay", 0);
        engine.put("pathParams", new HashMap<>());
    }

    private boolean isMatchingScenario(Scenario scenario) {
        String expression = StringUtils.trimToNull(scenario.getNameAndDescription());

        // Empty/null expression means catch-all (always matches)
        if (expression == null) {
            logger.debug("catch-all scenario matched at line: {}", scenario.getLine());
            return true;
        }

        try {
            Object result = engine.eval(expression);
            if (Boolean.TRUE.equals(result)) {
                logger.debug("scenario matched at line {}: {}", scenario.getLine(), expression);
                return true;
            } else {
                logger.trace("scenario skipped at line {}: {}", scenario.getLine(), expression);
                return false;
            }
        } catch (Exception e) {
            logger.warn("scenario match evaluation failed at line {}: {} - {}", scenario.getLine(), expression, e.getMessage());
            return false;
        }
    }

    private HttpResponse executeScenario(Scenario scenario, HttpRequest request) {
        // Execute all steps in the scenario
        for (Step step : scenario.getSteps()) {
            try {
                executeStep(step);
            } catch (Exception e) {
                logger.error("step execution failed at line {}: {}", step.getLine(), e.getMessage());
                // Return 500 error on step failure
                HttpResponse response = new HttpResponse();
                response.setStatus(500);
                response.setBody(Map.of("error", e.getMessage()));
                return response;
            }
        }

        // Execute afterScenario hook if configured
        Invokable afterScenario = config.getAfterScenario();
        if (afterScenario != null) {
            try {
                afterScenario.invoke();
            } catch (Exception e) {
                logger.warn("afterScenario hook failed: {}", e.getMessage());
            }
        }

        // Build response from variables
        return buildResponse(request);
    }

    private void executeStep(Step step) {
        String keyword = step.getKeyword();
        String text = step.getText();

        if (keyword == null) {
            // Plain expression
            String expr = text;
            if (step.getDocString() != null) {
                expr = expr + step.getDocString();
            }
            engine.eval(expr);
        } else if ("def".equals(keyword)) {
            executeDef(step);
        } else if ("configure".equals(keyword)) {
            executeConfigure(step);
        } else if ("print".equals(keyword)) {
            Object value = engine.eval(text);
            logger.info("[print] {}", value);
        } else {
            // Treat as expression (e.g., "* response = { foo: 'bar' }")
            String fullExpr = keyword + " " + text;
            if (step.getDocString() != null) {
                fullExpr = fullExpr + step.getDocString();
            }
            engine.eval(fullExpr);
        }
    }

    private void executeDef(Step step) {
        String text = step.getText().trim();
        int eqPos = text.indexOf('=');
        if (eqPos == -1) {
            throw new RuntimeException("def requires '=' assignment: " + text);
        }
        String name = text.substring(0, eqPos).trim();
        String expr = text.substring(eqPos + 1).trim();

        // Handle doc string
        if (step.getDocString() != null) {
            expr = expr + step.getDocString();
        }

        Object value = engine.eval(expr);
        engine.put(name, value);
        globals.put(name, value);
    }

    private void executeConfigure(Step step) {
        String text = step.getText().trim();
        int eqPos = text.indexOf('=');
        if (eqPos == -1) {
            throw new RuntimeException("configure requires '=' assignment: " + text);
        }
        String key = text.substring(0, eqPos).trim();
        String expr = text.substring(eqPos + 1).trim();
        Object value = engine.eval(expr);

        switch (key) {
            case "cors":
                config.setCorsEnabled(Boolean.TRUE.equals(value));
                break;
            case "responseHeaders":
                if (value instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> headers = (Map<String, Object>) value;
                    config.setResponseHeaders(headers);
                }
                break;
            case "afterScenario":
                if (value instanceof Invokable) {
                    config.setAfterScenario((Invokable) value);
                }
                break;
            default:
                logger.warn("unknown configure key: {}", key);
        }
    }

    @SuppressWarnings("unchecked")
    private HttpResponse buildResponse(HttpRequest request) {
        HttpResponse response = new HttpResponse();

        // Get response variables from engine
        Object responseBody = engine.get("response");
        Object responseStatus = engine.get("responseStatus");
        Object responseHeaders = engine.get("responseHeaders");
        Object responseDelay = engine.get("responseDelay");

        // Handle karate.proceed() result - if response is an HttpResponse, pass it through
        if (responseBody instanceof HttpResponse proceedResponse) {
            // Pass through the proceed response directly
            response.setStatus(proceedResponse.getStatus());
            response.setBody(proceedResponse.getBodyBytes(), proceedResponse.getResourceType());
            if (proceedResponse.getHeaders() != null) {
                response.setHeaders(proceedResponse.getHeaders());
            }
            // Apply CORS if enabled (still need to add this after)
            if (config.isCorsEnabled()) {
                response.setHeader("Access-Control-Allow-Origin", "*");
            }
            return response;
        }

        // Set status
        if (responseStatus instanceof Number) {
            response.setStatus(((Number) responseStatus).intValue());
        }

        // Apply configured response headers first
        Map<String, Object> configuredHeaders = config.getResponseHeaders();
        if (configuredHeaders != null) {
            response.setHeaders(configuredHeaders);
        }

        // Apply scenario-level response headers (override configured)
        if (responseHeaders instanceof Map) {
            response.setHeaders((Map<String, Object>) responseHeaders);
        }

        // Add CORS header if enabled
        if (config.isCorsEnabled()) {
            response.setHeader("Access-Control-Allow-Origin", "*");
        }

        // Set body (auto-detect content type)
        if (responseBody != null) {
            if (responseBody instanceof Map || responseBody instanceof List) {
                response.setBody(FileUtils.toBytes(JSONValue.toJSONString(responseBody)), ResourceType.JSON);
            } else if (responseBody instanceof Node) {
                response.setBody(FileUtils.toBytes(Xml.toString((Node) responseBody)), ResourceType.XML);
            } else if (responseBody instanceof String) {
                response.setBody((String) responseBody);
            } else if (responseBody instanceof byte[]) {
                response.setBody((byte[]) responseBody, null);
            }
        }

        // Handle response delay
        if (responseDelay instanceof Number) {
            int delay = ((Number) responseDelay).intValue();
            if (delay > 0) {
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        return response;
    }

    private HttpResponse createNotFoundResponse() {
        HttpResponse response = new HttpResponse();
        response.setStatus(404);
        response.setBody(Map.of("error", "no matching scenario"));
        return response;
    }

    // ===== Matcher Functions =====

    public boolean pathMatches(String pattern) {
        HttpRequest request = LOCAL_REQUEST.get();
        if (request == null) {
            return false;
        }
        boolean matched = request.pathMatches(pattern);
        if (matched) {
            // Store path params in engine for scenario access
            engine.put("pathParams", request.getPathParams());
        }
        return matched;
    }

    public boolean methodIs(String method) {
        HttpRequest request = LOCAL_REQUEST.get();
        if (request == null) {
            return false;
        }
        return method.equalsIgnoreCase(request.getMethod());
    }

    public boolean typeContains(String text) {
        HttpRequest request = LOCAL_REQUEST.get();
        if (request == null) {
            return false;
        }
        String contentType = request.getContentType();
        return contentType != null && contentType.contains(text);
    }

    public boolean acceptContains(String text) {
        HttpRequest request = LOCAL_REQUEST.get();
        if (request == null) {
            return false;
        }
        String accept = request.getHeader("Accept");
        return accept != null && accept.contains(text);
    }

    public boolean headerContains(String name, String value) {
        HttpRequest request = LOCAL_REQUEST.get();
        if (request == null) {
            return false;
        }
        List<String> values = request.getHeaderValues(name);
        if (values != null) {
            for (String v : values) {
                if (v.contains(value)) {
                    return true;
                }
            }
        }
        return false;
    }

    public String paramValue(String name) {
        HttpRequest request = LOCAL_REQUEST.get();
        if (request == null) {
            return null;
        }
        return request.getParam(name);
    }

    public boolean paramExists(String name) {
        HttpRequest request = LOCAL_REQUEST.get();
        if (request == null) {
            return false;
        }
        List<String> values = request.getParamValues(name);
        return values != null && !values.isEmpty();
    }

    public Object bodyPath(String path) {
        HttpRequest request = LOCAL_REQUEST.get();
        if (request == null) {
            return null;
        }
        Object body = request.getBodyConverted();
        if (body == null) {
            return null;
        }

        if (path.startsWith("/")) {
            // XPath for XML
            if (body instanceof Node) {
                return Xml.getTextValueByPath((Node) body, path);
            }
            return null;
        } else {
            // JsonPath for JSON
            try {
                return JsonPath.read(body, path);
            } catch (Exception e) {
                logger.debug("bodyPath evaluation failed: {}", e.getMessage());
                return null;
            }
        }
    }

    // ===== Accessors =====

    public MockConfig getConfig() {
        return config;
    }

    public Map<String, Object> getGlobals() {
        return globals;
    }

    public Object getVariable(String name) {
        return globals.get(name);
    }

}
