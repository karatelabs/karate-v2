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
import io.karatelabs.io.http.HttpRequest;
import io.karatelabs.io.http.HttpResponse;
import io.karatelabs.js.Engine;
import io.karatelabs.js.Invokable;
import io.karatelabs.js.JsCallable;
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
 *
 * Uses ScenarioRuntime and StepExecutor for proper step execution,
 * ensuring all keywords (def, xml, json, call, etc.) work correctly.
 */
public class MockHandler implements Function<HttpRequest, HttpResponse> {

    private static final Logger logger = LoggerFactory.getLogger(MockHandler.class);

    private static final String ALLOWED_METHODS = "GET, HEAD, POST, PUT, DELETE, PATCH, OPTIONS";

    // Internal key for storing the current HttpRequest in the engine
    static final String HTTP_REQUEST_KEY = "__httpRequest";

    private final List<Feature> features = new ArrayList<>();
    private final Map<String, Object> globals = new LinkedHashMap<>();
    private final MockConfig config = new MockConfig();
    private final ReentrantLock requestLock = new ReentrantLock();
    private final String pathPrefix;

    // Runtime per feature (like V1's scenarioRuntimes map)
    private final Map<Feature, ScenarioRuntime> runtimes = new LinkedHashMap<>();

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

        // Initialize each feature with its own runtime
        for (Feature feature : features) {
            this.features.add(feature);
            ScenarioRuntime runtime = initRuntime(feature, args);
            runtimes.put(feature, runtime);
        }

        logger.info("mock handler initialized with {} feature(s), cors: {}", features.size(), config.isCorsEnabled());
    }

    /**
     * Initialize a runtime for a mock feature.
     * Creates a proper FeatureRuntime and ScenarioRuntime like V1 does.
     */
    private ScenarioRuntime initRuntime(Feature feature, Map<String, Object> args) {
        // Create FeatureRuntime (without Suite to skip karate-config.js loading)
        FeatureRuntime featureRuntime = new FeatureRuntime(null, feature);

        // Get first scenario for the runtime, or create a dummy one
        Scenario scenario = getFirstScenario(feature);

        // Create ScenarioRuntime with the FeatureRuntime (enables proper resource resolution)
        ScenarioRuntime runtime = new ScenarioRuntime(featureRuntime, scenario);

        // Register matcher functions in the engine (lambdas capture engine reference)
        Engine engine = runtime.getEngine();
        engine.put("pathMatches", (Invokable) a -> {
            HttpRequest req = (HttpRequest) engine.get(HTTP_REQUEST_KEY);
            if (req == null) return false;
            boolean matched = req.pathMatches(a[0] + "");
            if (matched) {
                engine.put("pathParams", req.getPathParams());
            }
            return matched;
        });
        engine.put("methodIs", (Invokable) a -> {
            HttpRequest req = (HttpRequest) engine.get(HTTP_REQUEST_KEY);
            return req != null && (a[0] + "").equalsIgnoreCase(req.getMethod());
        });
        engine.put("typeContains", (Invokable) a -> {
            HttpRequest req = (HttpRequest) engine.get(HTTP_REQUEST_KEY);
            if (req == null) return false;
            String contentType = req.getContentType();
            return contentType != null && contentType.contains(a[0] + "");
        });
        engine.put("acceptContains", (Invokable) a -> {
            HttpRequest req = (HttpRequest) engine.get(HTTP_REQUEST_KEY);
            if (req == null) return false;
            String accept = req.getHeader("Accept");
            return accept != null && accept.contains(a[0] + "");
        });
        engine.put("headerContains", (Invokable) a -> {
            HttpRequest req = (HttpRequest) engine.get(HTTP_REQUEST_KEY);
            if (req == null) return false;
            List<String> values = req.getHeaderValues(a[0] + "");
            if (values != null) {
                String search = a[1] + "";
                for (String v : values) {
                    if (v.contains(search)) return true;
                }
            }
            return false;
        });
        engine.put("paramValue", (Invokable) a -> {
            HttpRequest req = (HttpRequest) engine.get(HTTP_REQUEST_KEY);
            return req != null ? req.getParam(a[0] + "") : null;
        });
        engine.put("paramExists", (Invokable) a -> {
            HttpRequest req = (HttpRequest) engine.get(HTTP_REQUEST_KEY);
            if (req == null) return false;
            List<String> values = req.getParamValues(a[0] + "");
            return values != null && !values.isEmpty();
        });
        engine.put("bodyPath", (Invokable) a -> {
            HttpRequest req = (HttpRequest) engine.get(HTTP_REQUEST_KEY);
            if (req == null) return null;
            Object body = req.getBodyConverted();
            if (body == null) return null;
            String path = a[0] + "";
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
        });

        // Put args into globals if provided
        if (args != null) {
            globals.putAll(args);
            for (var entry : args.entrySet()) {
                engine.put(entry.getKey(), entry.getValue());
            }
        }

        // Execute background once on initialization using StepExecutor
        StepExecutor executor = new StepExecutor(runtime);
        if (feature.isBackgroundPresent()) {
            for (Step step : feature.getBackground().getSteps()) {
                StepResult result = executor.execute(step);
                if (result.isFailed()) {
                    throw new RuntimeException("mock background failed at line " + step.getLine() + ": " +
                        result.getError().getMessage(), result.getError());
                }
            }
            // Save background variables to globals
            saveGlobals(engine);

            // Transfer configure settings to MockConfig
            KarateConfig karateConfig = runtime.getConfig();
            if (karateConfig.isCorsEnabled()) {
                config.setCorsEnabled(true);
            }
            Object responseHeaders = karateConfig.getResponseHeaders();
            if (responseHeaders instanceof Map) {
                config.setResponseHeaders((Map<String, Object>) responseHeaders);
            }
            Object afterScenario = karateConfig.getAfterScenario();
            if (afterScenario instanceof JsCallable callable) {
                config.setAfterScenario(callable);
            }
        }

        logger.debug("initialized feature: {}", feature);
        return runtime;
    }

    private Scenario getFirstScenario(Feature feature) {
        for (FeatureSection section : feature.getSections()) {
            if (section.getScenario() != null) {
                return section.getScenario();
            }
        }
        // Fallback - create minimal feature with scenario
        Feature minimalFeature = Feature.read(Resource.text("Feature: Mock\nScenario: dummy\n* def x = 1"));
        return minimalFeature.getSections().getFirst().getScenario();
    }

    /**
     * Save current engine variables to globals for persistence across requests.
     */
    private void saveGlobals(Engine engine) {
        Map<String, Object> bindings = engine.getBindings();
        for (var entry : bindings.entrySet()) {
            String key = entry.getKey();
            // Skip built-in variables and request-specific variables
            if (!isBuiltInVariable(key)) {
                globals.put(key, entry.getValue());
            }
        }
    }

    private boolean isBuiltInVariable(String name) {
        return name.equals("karate") || name.equals("read") || name.equals("match") ||
               name.startsWith("request") || name.startsWith("response") ||
               name.equals("pathParams") || name.equals("pathMatches") ||
               name.equals("methodIs") || name.equals("typeContains") ||
               name.equals("acceptContains") || name.equals("headerContains") ||
               name.equals("paramValue") || name.equals("paramExists") ||
               name.equals("bodyPath");
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

        // Find matching scenario and execute
        for (Feature feature : features) {
            ScenarioRuntime runtime = runtimes.get(feature);
            Engine engine = runtime.getEngine();

            // Set up request variables (includes storing HttpRequest for matcher functions)
            setupRequestVariables(engine, request);

            for (FeatureSection section : feature.getSections()) {
                if (section.isOutline()) {
                    logger.warn("skipping scenario outline in mock - {}:{}", feature, section.getScenarioOutline().getLine());
                    continue;
                }

                Scenario scenario = section.getScenario();
                if (isMatchingScenario(scenario, engine)) {
                    return executeScenario(runtime, scenario, request);
                }
            }
        }

        // No match found - return 404
        logger.warn("no scenarios matched, returning 404: {} {}", request.getMethod(), request.getPath());
        return createNotFoundResponse();
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

    private void setupRequestVariables(Engine engine, HttpRequest request) {
        // Set all globals first
        for (Map.Entry<String, Object> entry : globals.entrySet()) {
            engine.put(entry.getKey(), entry.getValue());
        }

        // Store HttpRequest for matcher functions and karate.proceed()
        engine.put(HTTP_REQUEST_KEY, request);

        // Set request variables as per MOCKS.md specification
        engine.put("request", request.getBodyConverted());
        engine.put("requestBytes", request.getBody());
        engine.put("requestPath", request.getPath());
        engine.put("requestUri", request.getPathRaw());
        engine.put("requestUrlBase", request.jsGet("urlBase"));
        engine.put("requestMethod", request.getMethod());
        engine.put("requestHeaders", request.getHeaders());
        engine.put("requestParams", request.getParams());  // Keep as Map<String, List<String>> like v1
        engine.put("requestParts", request.getMultiParts());

        // Initialize response variables with defaults
        engine.put("response", null);
        engine.put("responseStatus", 200);
        engine.put("responseHeaders", new HashMap<>());
        engine.put("responseDelay", 0);
        engine.put("pathParams", new HashMap<>());
    }

    private boolean isMatchingScenario(Scenario scenario, Engine engine) {
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

    private HttpResponse executeScenario(ScenarioRuntime runtime, Scenario scenario, HttpRequest request) {
        Engine engine = runtime.getEngine();
        StepExecutor executor = new StepExecutor(runtime);

        // Execute all steps in the scenario using StepExecutor
        for (Step step : scenario.getSteps()) {
            StepResult result = executor.execute(step);
            if (result.isFailed()) {
                logger.error("step execution failed at line {}: {}", step.getLine(), result.getError().getMessage());
                // Return 500 error on step failure
                HttpResponse response = new HttpResponse();
                response.setStatus(500);
                response.setBody(Map.of("error", result.getError().getMessage()));
                return response;
            }
        }

        // Save any new variables to globals
        saveGlobals(engine);

        // Execute afterScenario hook if configured
        // Pass null context - the JS function uses its declaredContext which has access to karate object
        JsCallable afterScenario = config.getAfterScenario();
        if (afterScenario != null) {
            try {
                afterScenario.call(null);
            } catch (Exception e) {
                logger.warn("afterScenario hook failed: {}", e.getMessage());
            }
        }

        // Build response from variables
        return buildResponse(engine, request);
    }

    @SuppressWarnings("unchecked")
    private HttpResponse buildResponse(Engine engine, HttpRequest request) {
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

        // Set response delay (handled by HttpServerHandler using Netty scheduler)
        if (responseDelay instanceof Number) {
            int delay = ((Number) responseDelay).intValue();
            if (delay > 0) {
                response.setDelay(delay);
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
