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

import io.karatelabs.common.Json;
import io.karatelabs.common.Resource;
import io.karatelabs.gherkin.Feature;
import io.karatelabs.gherkin.Step;
import io.karatelabs.gherkin.Table;
import io.karatelabs.io.http.HttpRequestBuilder;
import io.karatelabs.io.http.HttpResponse;
import io.karatelabs.log.JvmLogger;
import io.karatelabs.log.LogContext;
import io.karatelabs.match.Match;
import io.karatelabs.match.Result;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class StepExecutor {

    private final ScenarioRuntime runtime;

    public StepExecutor(ScenarioRuntime runtime) {
        this.runtime = runtime;
    }

    public StepResult execute(Step step) {
        long startTime = System.currentTimeMillis();
        long startNanos = System.nanoTime();

        // Call beforeStep hooks
        if (!beforeStep(step)) {
            // Hook returned false - skip this step
            StepResult skipped = StepResult.skipped(step, startTime);
            skipped.setLog(LogContext.get().collect());
            return skipped;
        }

        try {
            String keyword = step.getKeyword();
            // If keyword contains a dot, it's a property reference (e.g., "foo.key = 'value'")
            // Treat the whole thing as a JS expression
            if (keyword != null && keyword.contains(".")) {
                String fullExpr = keyword + step.getText();
                runtime.eval(fullExpr);
            } else if (keyword == null) {
                // Plain expression (e.g., "* print foo" or "* foo.bar()")
                executeExpression(step);
            } else {
                switch (keyword) {
                    // Variable assignment
                    case "def" -> executeDef(step);
                    case "set" -> executeSet(step);
                    case "remove" -> executeRemove(step);
                    case "text" -> executeText(step);
                    case "json" -> executeJson(step);
                    case "xml" -> executeXml(step);
                    case "copy" -> executeCopy(step);
                    case "table" -> executeTable(step);

                    // Assertions
                    case "match" -> executeMatch(step);
                    case "assert" -> executeAssert(step);
                    case "print" -> executePrint(step);

                    // HTTP
                    case "url" -> executeUrl(step);
                    case "path" -> executePath(step);
                    case "param" -> executeParam(step);
                    case "params" -> executeParams(step);
                    case "header" -> executeHeader(step);
                    case "headers" -> executeHeaders(step);
                    case "cookie" -> executeCookie(step);
                    case "cookies" -> executeCookies(step);
                    case "form field" -> executeFormField(step);
                    case "form fields" -> executeFormFields(step);
                    case "request" -> executeRequest(step);
                    case "method" -> executeMethod(step);
                    case "status" -> executeStatus(step);
                    case "multipart file" -> executeMultipartFile(step);
                    case "multipart field" -> executeMultipartField(step);
                    case "multipart fields" -> executeMultipartFields(step);
                    case "multipart files" -> executeMultipartFiles(step);
                    case "multipart entity" -> executeMultipartEntity(step);

                    // Control flow
                    case "call" -> executeCall(step);
                    case "callonce" -> executeCallOnce(step);
                    case "eval" -> executeEval(step);

                    // Config
                    case "configure" -> executeConfigure(step);

                    default -> throw new RuntimeException("unknown keyword: " + keyword);
                }
            }

            long elapsedNanos = System.nanoTime() - startNanos;
            StepResult result = StepResult.passed(step, startTime, elapsedNanos);
            result.setLog(LogContext.get().collect());
            afterStep(result);
            return result;

        } catch (AssertionError | Exception e) {
            long elapsedNanos = System.nanoTime() - startNanos;
            StepResult result = StepResult.failed(step, startTime, elapsedNanos, e);
            result.setLog(LogContext.get().collect());
            afterStep(result);
            return result;
        }
    }

    private boolean beforeStep(Step step) {
        FeatureRuntime fr = runtime.getFeatureRuntime();
        if (fr != null && fr.getSuite() != null) {
            for (RuntimeHook hook : fr.getSuite().getHooks()) {
                if (!hook.beforeStep(step, runtime)) {
                    return false;
                }
            }
        }
        return true;
    }

    private void afterStep(StepResult result) {
        FeatureRuntime fr = runtime.getFeatureRuntime();
        if (fr != null && fr.getSuite() != null) {
            for (RuntimeHook hook : fr.getSuite().getHooks()) {
                hook.afterStep(result, runtime);
            }
        }
    }

    // ========== Expression Execution ==========

    private void executeExpression(Step step) {
        runtime.eval(step.getText());
    }

    // ========== Variable Assignment ==========

    private void executeDef(Step step) {
        String text = step.getText();
        int eqIndex = findAssignmentOperator(text);
        if (eqIndex < 0) {
            throw new RuntimeException("def requires '=' assignment: " + text);
        }
        String name = text.substring(0, eqIndex).trim();
        String expr = text.substring(eqIndex + 1).trim();

        // Handle docstring if expression is empty
        if (expr.isEmpty() && step.getDocString() != null) {
            expr = step.getDocString();
        }

        // Check if RHS is a call/callonce expression
        if (expr.startsWith("call ")) {
            String callExpr = expr.substring(5).trim();
            executeCallWithResult(callExpr, name);
        } else if (expr.startsWith("callonce ")) {
            String callExpr = expr.substring(9).trim();
            executeCallOnceWithResult(callExpr, name);
        } else {
            Object value = runtime.eval(expr);
            runtime.setVariable(name, value);
        }
    }

    private void executeCallWithResult(String callExpr, String resultVar) {
        CallExpression call = parseCallExpression(callExpr);
        call.resultVar = resultVar;

        // Resolve the feature file relative to current feature
        FeatureRuntime fr = runtime.getFeatureRuntime();
        Resource calledResource = fr != null
                ? fr.resolve(call.path)
                : Resource.path(call.path);

        Feature calledFeature = Feature.read(calledResource);

        // Create nested FeatureRuntime with isolated scope (sharedScope=false)
        FeatureRuntime nestedFr = new FeatureRuntime(
                fr != null ? fr.getSuite() : null,
                calledFeature,
                fr,
                runtime,
                false,  // Isolated scope - copy variables, don't share
                call.arg
        );

        // Execute the called feature
        FeatureResult result = nestedFr.call();

        // Capture result variables from the last executed scenario (isolated scope)
        if (nestedFr.getLastExecuted() != null) {
            Map<String, Object> resultVars = nestedFr.getLastExecuted().getAllVariables();
            runtime.setVariable(resultVar, resultVars);
        }
    }

    private void executeCallOnceWithResult(String callExpr, String resultVar) {
        String cacheKey = "callonce:" + callExpr;

        // Check cache first
        FeatureRuntime fr = runtime.getFeatureRuntime();
        Map<String, Object> cache = fr != null && fr.getSuite() != null
                ? fr.getSuite().getCallOnceCache()
                : fr != null ? fr.CALLONCE_CACHE : null;

        if (cache != null) {
            @SuppressWarnings("unchecked")
            Map<String, Object> cached = (Map<String, Object>) cache.get(cacheKey);
            if (cached != null) {
                // Return cached result
                runtime.setVariable(resultVar, cached);
                return;
            }
        }

        // Not cached - execute the call
        executeCallWithResult(callExpr, resultVar);

        // Cache the result
        if (cache != null) {
            @SuppressWarnings("unchecked")
            Map<String, Object> resultVars = (Map<String, Object>) runtime.getVariable(resultVar);
            if (resultVars != null) {
                cache.put(cacheKey, new HashMap<>(resultVars));
            }
        }
    }

    private void executeSet(Step step) {
        // Deprecated: prefer using native JS syntax: * foo.bar = 'value'
        JvmLogger.warn("'set' keyword is deprecated, prefer JS syntax: * {} instead", step.getText());
        // Just evaluate the whole thing as a JS expression
        runtime.eval(step.getText());
    }

    private void executeRemove(Step step) {
        String path = step.getText().trim();
        int dotIndex = path.indexOf('.');
        if (dotIndex < 0) {
            // Remove entire variable
            runtime.setVariable(path, null);
        } else {
            // Remove nested property - use delete in JS
            runtime.eval("delete " + path);
        }
    }

    private void executeText(Step step) {
        String text = step.getText();
        int eqIndex = findAssignmentOperator(text);
        String name = text.substring(0, eqIndex).trim();
        // text keyword preserves the docstring as-is (no JS evaluation)
        String value = step.getDocString();
        if (value == null) {
            value = text.substring(eqIndex + 1).trim();
        }
        runtime.setVariable(name, value);
    }

    private void executeJson(Step step) {
        String text = step.getText();
        int eqIndex = findAssignmentOperator(text);
        String name = text.substring(0, eqIndex).trim();
        String expr = text.substring(eqIndex + 1).trim();
        // Evaluate expression and ensure it's JSON
        Object value = runtime.eval(expr);
        runtime.setVariable(name, value);
    }

    private void executeXml(Step step) {
        String text = step.getText();
        int eqIndex = findAssignmentOperator(text);
        String name = text.substring(0, eqIndex).trim();
        String expr;
        if (step.getDocString() != null) {
            expr = step.getDocString();
        } else {
            expr = text.substring(eqIndex + 1).trim();
        }
        // For XML, we evaluate as XML string
        Object value = runtime.eval(expr);
        runtime.setVariable(name, value);
    }

    private void executeCopy(Step step) {
        String text = step.getText();
        int eqIndex = findAssignmentOperator(text);
        String name = text.substring(0, eqIndex).trim();
        String expr = text.substring(eqIndex + 1).trim();
        Object value = runtime.eval(expr);
        // Deep copy using JSON round-trip
        Object copy = Json.of(Json.stringifyStrict(value)).value();
        runtime.setVariable(name, copy);
    }

    private void executeTable(Step step) {
        String text = step.getText().trim();
        Table table = step.getTable();
        if (table == null) {
            throw new RuntimeException("table keyword requires a data table");
        }
        List<Map<String, Object>> rows = table.getRowsAsMapsConverted();
        runtime.setVariable(text, rows);
    }

    // ========== Assertions ==========

    private void executeMatch(Step step) {
        String text = step.getText();
        MatchExpression expr = parseMatchExpression(text);

        Object actual = runtime.eval(expr.actualExpr);
        Object expected = runtime.eval(expr.expectedExpr);

        Result result = Match.that(actual).is(expr.matchType, expected);
        if (!result.pass) {
            throw new AssertionError(result.message);
        }
    }

    private void executeAssert(Step step) {
        Object result = runtime.eval(step.getText());
        if (result instanceof Boolean b) {
            if (!b) {
                throw new AssertionError("assert failed: " + step.getText());
            }
        } else {
            throw new RuntimeException("assert expression must return boolean: " + step.getText());
        }
    }

    private void executePrint(Step step) {
        Object value = runtime.eval(step.getText());
        String output = stringify(value);
        LogContext.get().log(output);
    }

    // ========== HTTP ==========

    private HttpRequestBuilder http() {
        return runtime.getHttp();
    }

    private void executeUrl(Step step) {
        String urlExpr = step.getText();
        String url = (String) runtime.eval(urlExpr);
        http().url(url);
    }

    private void executePath(Step step) {
        String pathExpr = step.getText().trim();
        // If expression contains comma, wrap in array to handle multiple segments
        // e.g., path 'users', '123' becomes ['users', '123']
        Object path;
        if (pathExpr.contains(",")) {
            path = runtime.eval("[" + pathExpr + "]");
        } else {
            path = runtime.eval(pathExpr);
        }
        if (path instanceof List<?> list) {
            for (Object item : list) {
                http().path(item.toString());
            }
        } else {
            http().path(path.toString());
        }
    }

    private void executeParam(Step step) {
        String text = step.getText();
        int eqIndex = findAssignmentOperator(text);
        String name = text.substring(0, eqIndex).trim();
        Object value = runtime.eval(text.substring(eqIndex + 1).trim());
        if (value instanceof List<?> list) {
            for (Object item : list) {
                http().param(name, item.toString());
            }
        } else {
            http().param(name, value.toString());
        }
    }

    @SuppressWarnings("unchecked")
    private void executeParams(Step step) {
        Object value = runtime.eval(step.getText());
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String name = entry.getKey().toString();
                Object v = entry.getValue();
                if (v instanceof List<?> list) {
                    for (Object item : list) {
                        http().param(name, item.toString());
                    }
                } else {
                    http().param(name, v.toString());
                }
            }
        }
    }

    private void executeHeader(Step step) {
        Table table = step.getTable();
        if (table != null) {
            for (Map<String, String> row : table.getRowsAsMaps()) {
                String name = row.get("name");
                String value = row.get("value");
                http().header(name, value);
            }
        } else {
            String text = step.getText();
            int eqIndex = findAssignmentOperator(text);
            String name = text.substring(0, eqIndex).trim();
            Object value = runtime.eval(text.substring(eqIndex + 1).trim());
            http().header(name, value.toString());
        }
    }

    @SuppressWarnings("unchecked")
    private void executeHeaders(Step step) {
        Object value = runtime.eval(step.getText());
        if (value instanceof Map<?, ?> map) {
            http().headers((Map<String, Object>) map);
        }
    }

    private void executeCookie(Step step) {
        // Basic cookie support
        String text = step.getText();
        int eqIndex = findAssignmentOperator(text);
        String name = text.substring(0, eqIndex).trim();
        Object value = runtime.eval(text.substring(eqIndex + 1).trim());
        http().header("Cookie", name + "=" + value);
    }

    @SuppressWarnings("unchecked")
    private void executeCookies(Step step) {
        Object value = runtime.eval(step.getText());
        if (value instanceof Map<?, ?> map) {
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (sb.length() > 0) sb.append("; ");
                sb.append(entry.getKey()).append("=").append(entry.getValue());
            }
            http().header("Cookie", sb.toString());
        }
    }

    private void executeFormField(Step step) {
        String text = step.getText();
        int eqIndex = findAssignmentOperator(text);
        String name = text.substring(0, eqIndex).trim();
        Object value = runtime.eval(text.substring(eqIndex + 1).trim());
        http().formField(name, value.toString());
    }

    @SuppressWarnings("unchecked")
    private void executeFormFields(Step step) {
        Object value = runtime.eval(step.getText());
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                http().formField(entry.getKey().toString(), entry.getValue().toString());
            }
        }
    }

    private void executeRequest(Step step) {
        Object body;
        if (step.getDocString() != null) {
            body = runtime.eval(step.getDocString());
        } else {
            body = runtime.eval(step.getText());
        }
        http().body(body);
    }

    private void executeMethod(Step step) {
        String method = step.getText().trim().toUpperCase();
        HttpResponse response = http().invoke(method);

        // Set response variables
        runtime.setVariable("response", response.getBodyConverted());
        runtime.setVariable("responseStatus", response.getStatus());
        runtime.setVariable("responseHeaders", response.getHeaders());
        runtime.setVariable("responseTime", response.getResponseTime());
        runtime.setVariable("responseBytes", response.getBodyBytes());

        // Log HTTP request/response to context
        LogContext ctx = LogContext.get();
        if (response.getRequest() != null) {
            ctx.log("{} {}", method, response.getRequest().getUrlAndPath());
        }
        ctx.log("{} ({} ms)", response.getStatus(), response.getResponseTime());
    }

    private void executeStatus(Step step) {
        int expected = Integer.parseInt(step.getText().trim());
        Object statusObj = runtime.getVariable("responseStatus");
        int actual = statusObj instanceof Number n ? n.intValue() : Integer.parseInt(statusObj.toString());
        if (actual != expected) {
            throw new AssertionError("expected status: " + expected + ", actual: " + actual);
        }
    }

    /**
     * Handles: multipart file myFile = { read: 'file.txt', filename: 'test.txt', contentType: 'text/plain' }
     * Or shorthand: multipart file myFile = read('file.txt')
     */
    @SuppressWarnings("unchecked")
    private void executeMultipartFile(Step step) {
        String text = step.getText();
        int eqIndex = findAssignmentOperator(text);
        if (eqIndex < 0) {
            throw new RuntimeException("multipart file requires '=' assignment: " + text);
        }
        String name = text.substring(0, eqIndex).trim();
        String expr = text.substring(eqIndex + 1).trim();

        Object value = runtime.eval(expr);

        Map<String, Object> multipartMap = new HashMap<>();
        multipartMap.put("name", name);

        if (value instanceof Map) {
            Map<String, Object> fileMap = (Map<String, Object>) value;
            // Handle { read: 'path', filename: 'name', contentType: 'type' }
            Object readPath = fileMap.get("read");
            if (readPath != null) {
                Resource resource = resolveResource(readPath.toString());
                File file = getFileFromResource(resource);
                if (file != null) {
                    multipartMap.put("value", file);
                } else {
                    // For classpath or in-memory resources, read bytes
                    try (InputStream is = resource.getStream()) {
                        byte[] bytes = is.readAllBytes();
                        multipartMap.put("value", bytes);
                    } catch (Exception e) {
                        throw new RuntimeException("failed to read file: " + readPath, e);
                    }
                }
            } else if (fileMap.get("value") != null) {
                multipartMap.put("value", fileMap.get("value"));
            }
            // Copy other properties
            if (fileMap.get("filename") != null) {
                multipartMap.put("filename", fileMap.get("filename"));
            }
            if (fileMap.get("contentType") != null) {
                multipartMap.put("contentType", fileMap.get("contentType"));
            }
            if (fileMap.get("charset") != null) {
                multipartMap.put("charset", fileMap.get("charset"));
            }
            if (fileMap.get("transferEncoding") != null) {
                multipartMap.put("transferEncoding", fileMap.get("transferEncoding"));
            }
        } else if (value instanceof String) {
            // Direct string value - could be file path or content
            multipartMap.put("value", value);
        } else if (value instanceof byte[]) {
            multipartMap.put("value", value);
        } else {
            multipartMap.put("value", value);
        }

        http().multiPart(multipartMap);
    }

    /**
     * Handles: multipart field name = 'value'
     */
    private void executeMultipartField(Step step) {
        String text = step.getText();
        int eqIndex = findAssignmentOperator(text);
        if (eqIndex < 0) {
            throw new RuntimeException("multipart field requires '=' assignment: " + text);
        }
        String name = text.substring(0, eqIndex).trim();
        String expr = text.substring(eqIndex + 1).trim();

        Object value = runtime.eval(expr);

        Map<String, Object> multipartMap = new HashMap<>();
        multipartMap.put("name", name);
        multipartMap.put("value", value);

        http().multiPart(multipartMap);
    }

    /**
     * Handles: multipart fields { name: 'value', other: 'data' }
     */
    @SuppressWarnings("unchecked")
    private void executeMultipartFields(Step step) {
        Object value = runtime.eval(step.getText());
        if (value instanceof Map) {
            Map<String, Object> fields = (Map<String, Object>) value;
            for (Map.Entry<String, Object> entry : fields.entrySet()) {
                Map<String, Object> multipartMap = new HashMap<>();
                multipartMap.put("name", entry.getKey());
                multipartMap.put("value", entry.getValue());
                http().multiPart(multipartMap);
            }
        } else {
            throw new RuntimeException("multipart fields expects a map: " + step.getText());
        }
    }

    /**
     * Handles: multipart files [{ read: 'file1.txt', name: 'file1' }, { read: 'file2.txt', name: 'file2' }]
     */
    @SuppressWarnings("unchecked")
    private void executeMultipartFiles(Step step) {
        Object value = runtime.eval(step.getText());
        if (value instanceof List) {
            List<Object> files = (List<Object>) value;
            for (Object item : files) {
                if (item instanceof Map) {
                    Map<String, Object> fileMap = (Map<String, Object>) item;
                    Map<String, Object> multipartMap = new HashMap<>();

                    // Name is required
                    String name = (String) fileMap.get("name");
                    if (name == null) {
                        throw new RuntimeException("multipart files entry requires 'name': " + item);
                    }
                    multipartMap.put("name", name);

                    // Handle file read
                    Object readPath = fileMap.get("read");
                    if (readPath != null) {
                        Resource resource = resolveResource(readPath.toString());
                        File file = getFileFromResource(resource);
                        if (file != null) {
                            multipartMap.put("value", file);
                        } else {
                            try (InputStream is = resource.getStream()) {
                                byte[] bytes = is.readAllBytes();
                                multipartMap.put("value", bytes);
                            } catch (Exception e) {
                                throw new RuntimeException("failed to read file: " + readPath, e);
                            }
                        }
                    } else if (fileMap.get("value") != null) {
                        multipartMap.put("value", fileMap.get("value"));
                    }

                    // Copy other properties
                    if (fileMap.get("filename") != null) {
                        multipartMap.put("filename", fileMap.get("filename"));
                    }
                    if (fileMap.get("contentType") != null) {
                        multipartMap.put("contentType", fileMap.get("contentType"));
                    }

                    http().multiPart(multipartMap);
                } else {
                    throw new RuntimeException("multipart files entry must be a map: " + item);
                }
            }
        } else {
            throw new RuntimeException("multipart files expects a list: " + step.getText());
        }
    }

    /**
     * Handles: multipart entity value
     * For sending a single entity as the multipart body (advanced use case)
     */
    @SuppressWarnings("unchecked")
    private void executeMultipartEntity(Step step) {
        Object value;
        if (step.getDocString() != null) {
            value = runtime.eval(step.getDocString());
        } else {
            value = runtime.eval(step.getText());
        }

        if (value instanceof Map) {
            // Single entity map with name, value, etc.
            http().multiPart((Map<String, Object>) value);
        } else {
            // Wrap in a default map
            Map<String, Object> multipartMap = new HashMap<>();
            multipartMap.put("name", "file");
            multipartMap.put("value", value);
            http().multiPart(multipartMap);
        }
    }

    // ========== Multipart Helpers ==========

    private Resource resolveResource(String path) {
        FeatureRuntime fr = runtime.getFeatureRuntime();
        if (fr != null) {
            return fr.resolve(path);
        }
        return Resource.path(path);
    }

    private File getFileFromResource(Resource resource) {
        if (resource.isLocalFile()) {
            Path path = resource.getPath();
            if (path != null && Files.exists(path)) {
                return path.toFile();
            }
        }
        return null;
    }

    // ========== Control Flow ==========

    private void executeCall(Step step) {
        String text = step.getText().trim();
        CallExpression call = parseCallExpression(text);

        // Resolve the feature file relative to current feature
        FeatureRuntime fr = runtime.getFeatureRuntime();
        Resource calledResource = fr != null
                ? fr.resolve(call.path)
                : Resource.path(call.path);

        Feature calledFeature = Feature.read(calledResource);

        // Determine if shared scope (no resultVar) or isolated scope (has resultVar)
        boolean sharedScope = call.resultVar == null;

        // Create nested FeatureRuntime
        FeatureRuntime nestedFr = new FeatureRuntime(
                fr != null ? fr.getSuite() : null,
                calledFeature,
                fr,
                runtime,
                sharedScope,
                call.arg
        );

        // Execute the called feature
        FeatureResult result = nestedFr.call();

        // Capture result variables from the last executed scenario
        if (nestedFr.getLastExecuted() != null) {
            Map<String, Object> resultVars = nestedFr.getLastExecuted().getAllVariables();
            if (call.resultVar != null) {
                // Isolated scope - store result in the specified variable
                runtime.setVariable(call.resultVar, resultVars);
            } else {
                // Shared scope - propagate all variables back to caller
                for (Map.Entry<String, Object> entry : resultVars.entrySet()) {
                    runtime.setVariable(entry.getKey(), entry.getValue());
                }
            }
        }
    }

    private void executeCallOnce(Step step) {
        String text = step.getText().trim();
        String cacheKey = text;

        // Check cache first
        FeatureRuntime fr = runtime.getFeatureRuntime();
        Map<String, Object> cache = fr != null && fr.getSuite() != null
                ? fr.getSuite().getCallOnceCache()
                : fr != null ? fr.CALLONCE_CACHE : null;

        if (cache != null) {
            @SuppressWarnings("unchecked")
            Map<String, Object> cached = (Map<String, Object>) cache.get(cacheKey);
            if (cached != null) {
                // Apply cached variables to current runtime
                for (Map.Entry<String, Object> entry : cached.entrySet()) {
                    runtime.setVariable(entry.getKey(), entry.getValue());
                }
                return;
            }
        }

        // Not cached - execute the call
        executeCall(step);

        // Cache the result
        if (cache != null && fr.getLastExecuted() != null) {
            cache.put(cacheKey, new HashMap<>(runtime.getAllVariables()));
        }
    }

    /**
     * Parses call expression like:
     * - read('file.feature')
     * - read('file.feature') { arg: 1 }
     * - read('file.feature') argVar
     */
    private CallExpression parseCallExpression(String text) {
        CallExpression expr = new CallExpression();

        // Check if it's a read() call
        if (text.startsWith("read(")) {
            int closeParen = text.indexOf(')');
            if (closeParen > 0) {
                // Extract path from read('path')
                String readArg = text.substring(5, closeParen).trim();
                // Remove quotes
                if ((readArg.startsWith("'") && readArg.endsWith("'")) ||
                        (readArg.startsWith("\"") && readArg.endsWith("\""))) {
                    expr.path = readArg.substring(1, readArg.length() - 1);
                } else {
                    // It's a variable reference
                    Object pathObj = runtime.eval(readArg);
                    expr.path = pathObj != null ? pathObj.toString() : readArg;
                }

                // Check for arguments after the read()
                String remainder = text.substring(closeParen + 1).trim();
                if (!remainder.isEmpty()) {
                    // Evaluate as JS - could be an object literal or variable
                    Object argObj = runtime.eval(remainder);
                    if (argObj instanceof Map) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> argMap = (Map<String, Object>) argObj;
                        expr.arg = argMap;
                    }
                }
            }
        } else {
            // Not a read() call - try evaluating as expression
            // Could be a variable that holds a feature path
            Object result = runtime.eval(text);
            if (result instanceof String) {
                expr.path = (String) result;
            } else {
                throw new RuntimeException("call expression must resolve to a feature path: " + text);
            }
        }

        return expr;
    }

    private static class CallExpression {
        String path;
        Map<String, Object> arg;
        String resultVar;
    }

    private void executeEval(Step step) {
        runtime.eval(step.getText());
    }

    // ========== Config ==========

    private void executeConfigure(Step step) {
        String text = step.getText();
        int eqIndex = findAssignmentOperator(text);
        if (eqIndex < 0) {
            throw new RuntimeException("configure requires '=' assignment: " + text);
        }
        String key = text.substring(0, eqIndex).trim();
        Object value = runtime.eval(text.substring(eqIndex + 1).trim());
        runtime.configure(key, value);
    }

    // ========== Helpers ==========

    private int findAssignmentOperator(String text) {
        // Find the first '=' that's not part of '==' or '!=' or '<=' or '>='
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == '=' && i > 0) {
                char prev = text.charAt(i - 1);
                if (prev != '=' && prev != '!' && prev != '<' && prev != '>') {
                    // Check not followed by '='
                    if (i + 1 >= text.length() || text.charAt(i + 1) != '=') {
                        return i;
                    }
                }
            }
            i++;
        }
        return -1;
    }

    private String stringify(Object value) {
        if (value == null) {
            return "null";
        } else if (value instanceof String) {
            return (String) value;
        } else if (value instanceof Map || value instanceof List) {
            return Json.stringifyStrict(value);
        } else {
            return value.toString();
        }
    }

    // ========== Match Expression Parsing ==========

    private MatchExpression parseMatchExpression(String text) {
        MatchExpression expr = new MatchExpression();

        // Check for "each" prefix
        if (text.startsWith("each ")) {
            expr.each = true;
            text = text.substring(5);
        }

        // Find the operator (order matters - check longer operators first)
        String[][] operators = {
                {"!contains", "NOT_CONTAINS"},
                {"contains only deep", "CONTAINS_ONLY_DEEP"},
                {"contains only", "CONTAINS_ONLY"},
                {"contains any deep", "CONTAINS_ANY_DEEP"},
                {"contains any", "CONTAINS_ANY"},
                {"contains deep", "CONTAINS_DEEP"},
                {"contains", "CONTAINS"},
                {"!=", "NOT_EQUALS"},
                {"==", "EQUALS"}
        };

        for (String[] op : operators) {
            int idx = text.indexOf(op[0]);
            if (idx > 0) {
                expr.actualExpr = text.substring(0, idx).trim();
                expr.expectedExpr = text.substring(idx + op[0].length()).trim();
                expr.matchType = toMatchType(op[1], expr.each);
                return expr;
            }
        }

        throw new RuntimeException("invalid match expression: " + text);
    }

    private Match.Type toMatchType(String name, boolean each) {
        if (each) {
            return Match.Type.valueOf("EACH_" + name);
        } else {
            return Match.Type.valueOf(name);
        }
    }

    private static class MatchExpression {
        boolean each;
        String actualExpr;
        String expectedExpr;
        Match.Type matchType;
    }

}
