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

import io.karatelabs.common.DataUtils;
import io.karatelabs.common.Json;
import io.karatelabs.common.Resource;
import io.karatelabs.common.Xml;
import org.w3c.dom.Node;
import io.karatelabs.gherkin.Feature;
import io.karatelabs.gherkin.MatchExpression;
import io.karatelabs.gherkin.Step;
import io.karatelabs.gherkin.Table;
import io.karatelabs.io.http.HttpRequestBuilder;
import io.karatelabs.io.http.HttpResponse;
import io.karatelabs.js.GherkinParser;
import io.karatelabs.js.JsCallable;
import io.karatelabs.log.JvmLogger;
import io.karatelabs.log.LogContext;
import io.karatelabs.match.Match;
import io.karatelabs.match.Result;

import com.jayway.jsonpath.JsonPath;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
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

            // Check if keyword contains punctuation (except underscore) - means it's a JS expression
            // Examples: foo.bar, foo(), foo['bar'].baz('blah')
            String text = step.getText();
            if (keyword != null && hasPunctuation(keyword)) {
                String fullExpr = keyword + text;
                if (step.getDocString() != null) {
                    fullExpr = fullExpr + step.getDocString();
                }
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
                    case "xmlstring" -> executeXmlString(step);
                    case "string" -> executeString(step);
                    case "csv" -> executeCsv(step);
                    case "yaml" -> executeYaml(step);
                    case "copy" -> executeCopy(step);
                    case "table" -> executeTable(step);
                    case "replace" -> executeReplace(step);

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

                    default -> {
                        // Check if text starts with ( - means keyword is a function name like myFunc('a')
                        // We only do this check here (not earlier) because valid keywords like "eval"
                        // could have text starting with ( like "eval (1 + 2)"
                        if (text != null && text.trim().startsWith("(")) {
                            runtime.eval(keyword + text);
                        } else {
                            throw new RuntimeException("unknown keyword: " + keyword);
                        }
                    }
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

        // Check if RHS is a special karate expression (not standard JS)
        if (expr.startsWith("call ")) {
            String callExpr = expr.substring(5).trim();
            executeCallWithResult(callExpr, name);
        } else if (expr.startsWith("callonce ")) {
            String callExpr = expr.substring(9).trim();
            executeCallOnceWithResult(callExpr, name);
        } else if (expr.startsWith("$")) {
            // $varname[*].path - jsonpath shortcut on a variable
            Object value = evalJsonPathShortcut(expr);
            runtime.setVariable(name, value);
        } else if (expr.startsWith("get[") || expr.startsWith("get ")) {
            // get[N] varname path OR get varname path - jsonpath with optional index
            Object value = evalGetExpression(expr);
            runtime.setVariable(name, value);
        } else if (expr.startsWith("<")) {
            // XML literal - parse directly as XML document
            Object value = Xml.toXmlDoc(expr);
            value = processEmbeddedExpressions(value);
            runtime.setVariable(name, value);
        } else {
            Object value = runtime.eval(expr);
            // Process embedded expressions for JSON/Map/List values
            if (value instanceof Map || value instanceof List) {
                value = processEmbeddedExpressions(value);
            }
            runtime.setVariable(name, value);
        }
    }

    private void executeCallWithResult(String callExpr, String resultVar) {
        // Try to evaluate the first token to see if it's a JS function or Feature
        // Syntax: "fun" or "fun arg" where fun is a JS function variable or Feature
        int spaceIdx = callExpr.indexOf(' ');
        String firstToken = spaceIdx > 0 ? callExpr.substring(0, spaceIdx) : callExpr;

        // Check if it's a read() call - that's definitely a feature call
        if (!callExpr.startsWith("read(")) {
            // Try to evaluate as a JS expression
            try {
                Object evaluated = runtime.eval(firstToken);
                if (evaluated instanceof JsCallable) {
                    // It's a JS function - invoke it and store result
                    JsCallable fn = (JsCallable) evaluated;
                    Object arg = null;
                    if (spaceIdx > 0) {
                        String argExpr = callExpr.substring(spaceIdx + 1).trim();
                        if (!argExpr.isEmpty()) {
                            arg = runtime.eval(argExpr);
                        }
                    }
                    Object result = arg != null
                            ? fn.call(null, new Object[]{arg})
                            : fn.call(null, new Object[0]);
                    runtime.setVariable(resultVar, result);
                    return;
                } else if (evaluated instanceof Feature) {
                    // It's a Feature - call it with arguments
                    Feature calledFeature = (Feature) evaluated;
                    Object arg = null;
                    if (spaceIdx > 0) {
                        String argExpr = callExpr.substring(spaceIdx + 1).trim();
                        if (!argExpr.isEmpty()) {
                            arg = runtime.eval(argExpr);
                        }
                    }
                    executeFeatureCall(calledFeature, arg, resultVar);
                    return;
                }
            } catch (Exception e) {
                // Not a valid JS expression, fall through to feature call
            }
        }

        // Standard feature call
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
        FeatureResult featureResult = nestedFr.call();

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

    @SuppressWarnings("unchecked")
    private void executeFeatureCall(Feature calledFeature, Object arg, String resultVar) {
        FeatureRuntime fr = runtime.getFeatureRuntime();

        if (arg instanceof List) {
            // Loop call - iterate over list and collect results
            List<Object> argList = (List<Object>) arg;
            List<Map<String, Object>> results = new ArrayList<>();
            for (Object item : argList) {
                Map<String, Object> argMap = item instanceof Map
                        ? (Map<String, Object>) item : null;
                Map<String, Object> result = callFeatureOnce(calledFeature, fr, argMap);
                results.add(result);
            }
            runtime.setVariable(resultVar, results);
        } else {
            // Single call
            Map<String, Object> argMap = arg instanceof Map
                    ? (Map<String, Object>) arg : null;
            Map<String, Object> result = callFeatureOnce(calledFeature, fr, argMap);
            runtime.setVariable(resultVar, result);
        }
    }

    private Map<String, Object> callFeatureOnce(Feature calledFeature, FeatureRuntime parentFr, Map<String, Object> arg) {
        // Create nested FeatureRuntime with isolated scope (sharedScope=false)
        FeatureRuntime nestedFr = new FeatureRuntime(
                parentFr != null ? parentFr.getSuite() : null,
                calledFeature,
                parentFr,
                runtime,
                false,  // Isolated scope - copy variables, don't share
                arg
        );

        // Execute the called feature
        FeatureResult featureResult = nestedFr.call();

        // Capture result variables from the last executed scenario (isolated scope)
        if (nestedFr.getLastExecuted() != null) {
            return nestedFr.getLastExecuted().getAllVariables();
        }
        return new HashMap<>();
    }

    @SuppressWarnings("unchecked")
    private void executeSet(Step step) {
        String text = step.getText().trim();
        Table table = step.getTable();

        if (table != null) {
            // Table-based set: * set varname | path | value |
            executeSetWithTable(text, table);
            return;
        }

        // Check for Karate-style XML xpath: "varname /xpath = value"
        // The key indicator is "space + /" pattern before the equals sign
        int eqIndex = findAssignmentOperator(text);
        if (eqIndex > 0) {
            String leftPart = text.substring(0, eqIndex).trim();
            // Look for "varname /xpath" pattern (space followed by /)
            int spaceSlashIdx = leftPart.indexOf(" /");
            if (spaceSlashIdx > 0) {
                String varName = leftPart.substring(0, spaceSlashIdx).trim();
                String xpath = leftPart.substring(spaceSlashIdx + 1).trim();
                String valueExpr = text.substring(eqIndex + 1).trim();

                Object target = runtime.getVariable(varName);
                if (target == null) {
                    throw new RuntimeException("variable is null or not set: " + varName);
                }
                if (!(target instanceof Node)) {
                    throw new RuntimeException("cannot set xpath on non-XML variable: " + varName);
                }

                Object value = evalValue(valueExpr);
                org.w3c.dom.Document doc = target instanceof org.w3c.dom.Document
                        ? (org.w3c.dom.Document) target
                        : ((Node) target).getOwnerDocument();
                if (value instanceof Node) {
                    Xml.setByPath(doc, xpath, (Node) value);
                } else {
                    Xml.setByPath(doc, xpath, value == null ? "" : value.toString());
                }
                return;
            }
        }

        // Default: evaluate as JS expression (e.g., "foo.b = 2", "arr[0] = 99")
        runtime.eval(text);
    }

    private Object evalValue(String valueExpr) {
        if (valueExpr.startsWith("<")) {
            // XML literal
            return Xml.toXmlDoc(valueExpr);
        }
        return runtime.eval(valueExpr);
    }

    @SuppressWarnings("unchecked")
    private void executeSetWithTable(String varExpr, Table table) {
        List<String> headers = new ArrayList<>(table.getKeys());
        List<List<String>> rows = table.getRows();

        // Determine the target variable and optional base path
        // varExpr could be: "cat", "cat.kitten", "cat $.kitten"
        String varName;
        String basePath = null;
        int spaceIdx = varExpr.indexOf(' ');
        int dotIdx = varExpr.indexOf('.');
        if (spaceIdx > 0) {
            varName = varExpr.substring(0, spaceIdx);
            basePath = varExpr.substring(spaceIdx + 1).trim();
        } else if (dotIdx > 0) {
            varName = varExpr.substring(0, dotIdx);
            basePath = varExpr.substring(dotIdx + 1);
        } else {
            varName = varExpr;
        }

        // Check if headers indicate array construction (column headers are numbers or "path"/"value")
        boolean isPathValueFormat = headers.contains("path") && headers.contains("value");
        boolean isArrayFormat = !isPathValueFormat && headers.size() > 1;

        Object target = runtime.getVariable(varName);
        if (target == null) {
            target = isArrayFormat ? new ArrayList<>() : new LinkedHashMap<>();
            runtime.setVariable(varName, target);
        }

        // Navigate to base path if specified
        if (basePath != null && target instanceof Map) {
            String cleanPath = basePath.startsWith("$.") ? basePath.substring(2) : basePath;
            Object nested = getOrCreatePath((Map<String, Object>) target, cleanPath);
            target = nested;
        }

        if (isPathValueFormat) {
            // path | value format - skip header row (index 0)
            int pathIdx = headers.indexOf("path");
            int valueIdx = headers.indexOf("value");
            for (int i = 1; i < rows.size(); i++) {
                List<String> row = rows.get(i);
                String path = row.get(pathIdx);
                // Handle bounds - value column might not exist if row is shorter
                String valueExpr = valueIdx < row.size() ? row.get(valueIdx) : "";
                if (valueExpr == null || valueExpr.isEmpty()) continue;
                // V1 behavior: (expr) with parens means "keep this value even if null"
                boolean keepNull = valueExpr.startsWith("(") && valueExpr.endsWith(")");
                Object value = runtime.eval(valueExpr);
                if (value != null || keepNull) {
                    setValueAtPath(target, path, value);
                }
            }
        } else if (isArrayFormat) {
            // Column headers are array indices or just positional
            // path | 0 | 1 | 2 format
            int pathIdx = headers.indexOf("path");
            if (pathIdx < 0) pathIdx = 0; // first column is path

            List<Object> resultList;
            if (target instanceof List) {
                resultList = (List<Object>) target;
            } else {
                resultList = new ArrayList<>();
                runtime.setVariable(varName, resultList);
            }

            // Ensure list has enough elements
            int maxIdx = 0;
            for (int i = 0; i < headers.size(); i++) {
                if (i == pathIdx) continue;
                try {
                    int idx = Integer.parseInt(headers.get(i));
                    maxIdx = Math.max(maxIdx, idx + 1);
                } catch (NumberFormatException e) {
                    maxIdx = Math.max(maxIdx, i);
                }
            }
            while (resultList.size() < maxIdx) {
                resultList.add(new LinkedHashMap<>());
            }

            // Fill in values - skip header row (index 0)
            for (int rowIdx = 1; rowIdx < rows.size(); rowIdx++) {
                List<String> row = rows.get(rowIdx);
                String path = row.get(pathIdx);
                for (int i = 0; i < headers.size(); i++) {
                    if (i == pathIdx) continue;
                    // Check bounds - rows may have fewer cells than headers if trailing cells are empty
                    if (i >= row.size()) continue;
                    String valueExpr = row.get(i);
                    if (valueExpr == null || valueExpr.isEmpty()) continue;

                    int targetIdx;
                    try {
                        targetIdx = Integer.parseInt(headers.get(i));
                    } catch (NumberFormatException e) {
                        targetIdx = i - (pathIdx < i ? 1 : 0);
                    }

                    while (resultList.size() <= targetIdx) {
                        resultList.add(new LinkedHashMap<>());
                    }

                    // V1 behavior: (expr) with parens means "keep this value even if null"
                    boolean keepNull = valueExpr.startsWith("(") && valueExpr.endsWith(")");
                    Object value = runtime.eval(valueExpr);
                    if (value != null || keepNull) {
                        Object element = resultList.get(targetIdx);
                        if (element == null) {
                            element = new LinkedHashMap<>();
                            resultList.set(targetIdx, element);
                        }
                        setValueAtPath(element, path, value);
                    }
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private Object getOrCreatePath(Map<String, Object> target, String path) {
        String[] parts = path.split("\\.");
        Map<String, Object> current = target;
        for (String part : parts) {
            Object next = current.get(part);
            if (next == null) {
                next = new LinkedHashMap<>();
                current.put(part, next);
            }
            if (next instanceof Map) {
                current = (Map<String, Object>) next;
            } else {
                return next;
            }
        }
        return current;
    }

    @SuppressWarnings("unchecked")
    private void setValueAtPath(Object target, String path, Object value) {
        if (target instanceof Map) {
            Map<String, Object> map = (Map<String, Object>) target;
            // Handle array bracket notation in path: bar[0]
            if (path.contains("[")) {
                int bracketIdx = path.indexOf('[');
                String key = path.substring(0, bracketIdx);
                int closeIdx = path.indexOf(']');
                int arrayIdx = Integer.parseInt(path.substring(bracketIdx + 1, closeIdx));
                String remainder = closeIdx + 1 < path.length() ? path.substring(closeIdx + 1) : "";

                Object arr = map.get(key);
                if (arr == null) {
                    arr = new ArrayList<>();
                    map.put(key, arr);
                }
                if (arr instanceof List) {
                    List<Object> list = (List<Object>) arr;
                    while (list.size() <= arrayIdx) {
                        list.add(remainder.isEmpty() ? null : new LinkedHashMap<>());
                    }
                    if (remainder.isEmpty()) {
                        list.set(arrayIdx, value);
                    } else {
                        String nextPath = remainder.startsWith(".") ? remainder.substring(1) : remainder;
                        setValueAtPath(list.get(arrayIdx), nextPath, value);
                    }
                }
            } else if (path.contains(".")) {
                int dotIdx = path.indexOf('.');
                String key = path.substring(0, dotIdx);
                String rest = path.substring(dotIdx + 1);
                Object nested = map.get(key);
                if (nested == null) {
                    nested = new LinkedHashMap<>();
                    map.put(key, nested);
                }
                setValueAtPath(nested, rest, value);
            } else {
                map.put(path, value);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void executeRemove(Step step) {
        String text = step.getText().trim();

        // Check for jsonpath syntax: "varName $.path" or "varName $path"
        int spaceIndex = text.indexOf(' ');
        if (spaceIndex > 0) {
            String varName = text.substring(0, spaceIndex);
            String jsonPath = text.substring(spaceIndex + 1).trim();

            Object target = runtime.getVariable(varName);
            if (target == null) return;

            // Handle jsonpath removal
            if (jsonPath.startsWith("$")) {
                // Extract the path after $ - e.g., "$.foo" -> "foo", "$['foo']" -> handle bracket notation
                String pathWithoutDollar = jsonPath.substring(1);
                if (pathWithoutDollar.startsWith(".")) {
                    pathWithoutDollar = pathWithoutDollar.substring(1);
                }

                // For simple paths like "foo" or "foo.bar", traverse and remove
                if (target instanceof Map) {
                    Map<String, Object> map = (Map<String, Object>) target;
                    removeAtPath(map, pathWithoutDollar);
                }
            }
        } else {
            // Check for dot notation: "varName.key" or just "varName"
            int dotIndex = text.indexOf('.');
            if (dotIndex < 0) {
                // Remove entire variable
                runtime.setVariable(text, null);
            } else {
                // Remove nested property - use delete in JS
                runtime.eval("delete " + text);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void removeAtPath(Map<String, Object> map, String path) {
        // Handle bracket notation like "['foo']"
        if (path.startsWith("[")) {
            int closeIdx = path.indexOf(']');
            if (closeIdx > 0) {
                String key = path.substring(1, closeIdx);
                // Remove quotes from key if present
                if ((key.startsWith("'") && key.endsWith("'")) ||
                    (key.startsWith("\"") && key.endsWith("\""))) {
                    key = key.substring(1, key.length() - 1);
                }
                String remainder = closeIdx + 1 < path.length() ? path.substring(closeIdx + 1) : "";
                if (remainder.isEmpty()) {
                    map.remove(key);
                } else {
                    Object nested = map.get(key);
                    if (nested instanceof Map) {
                        String nextPath = remainder.startsWith(".") ? remainder.substring(1) : remainder;
                        removeAtPath((Map<String, Object>) nested, nextPath);
                    }
                }
            }
        } else if (path.contains(".")) {
            // Dot notation: "foo.bar"
            int dotIdx = path.indexOf('.');
            String key = path.substring(0, dotIdx);
            String rest = path.substring(dotIdx + 1);
            Object nested = map.get(key);
            if (nested instanceof Map) {
                removeAtPath((Map<String, Object>) nested, rest);
            }
        } else {
            // Simple key
            map.remove(path);
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
        // Evaluate expression and process embedded expressions
        Object value = runtime.eval(expr);
        value = processEmbeddedExpressions(value);
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
        // Evaluate expression and convert to XML Document
        Object value = runtime.eval(expr);
        if (value instanceof String) {
            value = Xml.toXmlDoc((String) value);
        } else if (value instanceof Node) {
            // Already XML, keep as is
        }
        runtime.setVariable(name, value);
    }

    private void executeXmlString(Step step) {
        String text = step.getText();
        int eqIndex = findAssignmentOperator(text);
        String name = text.substring(0, eqIndex).trim();
        String expr = text.substring(eqIndex + 1).trim();
        Object value = runtime.eval(expr);
        // Convert to XML string representation
        String xmlString;
        if (value instanceof Node) {
            xmlString = Xml.toString((Node) value, false);
        } else {
            xmlString = value.toString();
        }
        runtime.setVariable(name, xmlString);
    }

    private void executeString(Step step) {
        String text = step.getText();
        int eqIndex = findAssignmentOperator(text);
        String name = text.substring(0, eqIndex).trim();
        String expr = text.substring(eqIndex + 1).trim();
        Object value = runtime.eval(expr);
        // Convert to JSON string representation
        String stringValue = Json.stringifyStrict(value);
        runtime.setVariable(name, stringValue);
    }

    private void executeCsv(Step step) {
        String text = step.getText();
        int eqIndex = findAssignmentOperator(text);
        String name = text.substring(0, eqIndex).trim();
        String csvText;
        if (step.getDocString() != null) {
            // Doc string: csv data = """..."""
            csvText = step.getDocString();
        } else {
            // Expression: csv data = someVar
            String expr = text.substring(eqIndex + 1).trim();
            Object value = runtime.eval(expr);
            csvText = value.toString();
        }
        List<Map<String, Object>> result = DataUtils.fromCsv(csvText);
        runtime.setVariable(name, result);
    }

    private void executeYaml(Step step) {
        String text = step.getText();
        int eqIndex = findAssignmentOperator(text);
        String name = text.substring(0, eqIndex).trim();
        String yamlText;
        if (step.getDocString() != null) {
            // Doc string: yaml data = """..."""
            yamlText = step.getDocString();
        } else {
            // Expression: yaml data = someVar
            String expr = text.substring(eqIndex + 1).trim();
            Object value = runtime.eval(expr);
            yamlText = value.toString();
        }
        Object result = DataUtils.fromYaml(yamlText);
        runtime.setVariable(name, result);
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
        // Get raw string values, then evaluate each as a Karate expression (V1 behavior)
        List<Map<String, String>> rawRows = table.getRowsAsMaps();
        List<Map<String, Object>> result = new ArrayList<>(rawRows.size());
        for (Map<String, String> rawRow : rawRows) {
            Map<String, Object> row = new LinkedHashMap<>(rawRow.size());
            for (Map.Entry<String, String> entry : rawRow.entrySet()) {
                String expr = entry.getValue();
                if (expr == null || expr.isEmpty()) {
                    // Skip empty values (V1 strips these by default)
                    continue;
                }
                // V1 behavior: (expr) with parens means "keep this value even if null"
                // Without parens, null values are skipped
                boolean keepNull = expr.startsWith("(") && expr.endsWith(")");
                Object value = runtime.eval(expr);
                if (value != null || keepNull) {
                    row.put(entry.getKey(), value);
                }
            }
            result.add(row);
        }
        runtime.setVariable(text, result);
    }

    private void executeReplace(Step step) {
        String text = step.getText();
        Table table = step.getTable();

        if (table != null) {
            // Table syntax: replace varName
            //   | token | value   |
            //   | one   | 'cruel' |
            String varName = text.trim();
            Object varValue = runtime.getVariable(varName);
            if (varValue == null) {
                throw new RuntimeException("no variable found with name: " + varName);
            }
            String varText = varValue.toString();

            // Process each row in the table
            List<Map<String, String>> rows = table.getRowsAsMaps();
            for (Map<String, String> row : rows) {
                String token = row.get("token");
                String valueExpr = row.get("value");

                // Evaluate the replacement expression
                Object replaceValue = runtime.eval(valueExpr);
                String replacement = replaceValue != null ? replaceValue.toString() : "";

                // If token is alphanumeric, wrap with < >
                if (token != null && !token.isEmpty() && Character.isLetterOrDigit(token.charAt(0))) {
                    token = '<' + token + '>';
                }

                // Perform replacement
                if (token != null) {
                    varText = varText.replace(token, replacement);
                }
            }
            runtime.setVariable(varName, varText);
        } else {
            // Single-line syntax: replace varName.token = 'replacement'
            int eqIndex = findAssignmentOperator(text);
            if (eqIndex < 0) {
                throw new RuntimeException("replace requires '=' assignment: " + text);
            }
            String nameAndToken = text.substring(0, eqIndex).trim();
            String replaceExpr = text.substring(eqIndex + 1).trim();

            // Parse varName.token
            int dotIndex = nameAndToken.indexOf('.');
            if (dotIndex < 0) {
                throw new RuntimeException("replace requires varName.token syntax: " + nameAndToken);
            }
            String varName = nameAndToken.substring(0, dotIndex).trim();
            String token = nameAndToken.substring(dotIndex + 1).trim();

            // Get the variable value as string
            Object varValue = runtime.getVariable(varName);
            if (varValue == null) {
                throw new RuntimeException("no variable found with name: " + varName);
            }
            String varText = varValue.toString();

            // Evaluate the replacement expression
            Object replaceValue = runtime.eval(replaceExpr);
            String replacement = replaceValue != null ? replaceValue.toString() : "";

            // If token is alphanumeric, wrap with < >
            if (!token.isEmpty() && Character.isLetterOrDigit(token.charAt(0))) {
                token = '<' + token + '>';
            }

            // Perform replacement and update variable
            String replaced = varText.replace(token, replacement);
            runtime.setVariable(varName, replaced);
        }
    }

    // ========== Assertions ==========

    private void executeMatch(Step step) {
        String text = step.getText();
        MatchExpression expr = GherkinParser.parseMatchExpression(text);

        Object actual = evalMatchExpression(expr.getActualExpr());

        // Check for docstring as expected value (e.g., match foo contains deep """ {...} """)
        Object expected;
        String docString = step.getDocString();
        if (docString != null && (expr.getExpectedExpr() == null || expr.getExpectedExpr().isEmpty())) {
            // DocString provides the expected value
            expected = evalMatchExpression(docString);
        } else {
            expected = evalMatchExpression(expr.getExpectedExpr());
        }

        Match.Type matchType = Match.Type.valueOf(expr.getMatchTypeName());

        // Use Match.execute with runtime's engine so embedded expressions can access variables
        Result result = Match.execute(runtime.getEngine(), matchType, actual, expected);
        if (!result.pass) {
            throw new AssertionError(result.message);
        }
    }

    /**
     * Evaluates expressions in match statements, handling both JS and jsonpath.
     * Detects jsonpath patterns like var[*].path, $var[*].path, and uses
     * jsonpath evaluation for those instead of JS.
     */
    private Object evalMatchExpression(String expr) {
        if (expr == null || expr.isEmpty()) {
            return null;
        }

        // Handle XML literal - expression starting with <
        if (expr.startsWith("<")) {
            return Xml.toXmlDoc(expr);
        }

        // Handle get[N] or get syntax (same as in def)
        if (expr.startsWith("get[") || expr.startsWith("get ")) {
            return evalGetExpression(expr);
        }

        // Handle $varname.path jsonpath shortcut
        if (expr.startsWith("$")) {
            return evalJsonPathShortcut(expr);
        }

        // Handle "varname /" syntax - XPath root shortcut for XML variable
        // e.g., "temp /" means the root of the XML document stored in 'temp'
        if (expr.endsWith(" /") || expr.equals("/")) {
            String varName = expr.endsWith(" /") ? expr.substring(0, expr.length() - 2).trim() : null;
            if (varName != null) {
                Object target = runtime.getVariable(varName);
                if (target instanceof Node) {
                    return target; // Return the XML node directly
                }
            }
        }

        // Handle "varname /xpath/path" syntax - XPath on XML variable
        if (expr.contains(" /") && !expr.contains("//")) {
            int spaceSlashIdx = expr.indexOf(" /");
            String varName = expr.substring(0, spaceSlashIdx).trim();
            String xpath = expr.substring(spaceSlashIdx + 1).trim();
            if (!varName.contains(" ") && varName.matches("[a-zA-Z_][a-zA-Z0-9_]*")) {
                Object target = runtime.getVariable(varName);
                if (target instanceof Node) {
                    return KarateJs.evalXmlPath((Node) target, xpath);
                }
            }
        }

        // Check if expression contains jsonpath wildcard [*] or filter [?(...)]
        if (expr.contains("[*]") || expr.contains("[?")) {
            // Check if it's var[*].path or var[?...].path pattern
            int bracketIdx = expr.indexOf('[');
            if (bracketIdx > 0) {
                String varName = expr.substring(0, bracketIdx);
                // Verify it's a simple variable name (no dots before bracket)
                if (!varName.contains(".") && varName.matches("[a-zA-Z_][a-zA-Z0-9_]*")) {
                    Object target = runtime.getVariable(varName);
                    if (target != null) {
                        String jsonPath = "$" + expr.substring(bracketIdx);
                        return JsonPath.read(target, jsonPath);
                    }
                }
            }
        }

        // Default: evaluate as JS
        Object result = runtime.eval(expr);

        // Check for "not present" case: if result is null and expression is property access
        // We need to distinguish between "property exists and is null" vs "property doesn't exist"
        if (result == null && expr.contains(".") && !expr.contains("(")) {
            // Check if it's a simple property access like "foo.bar"
            int lastDot = expr.lastIndexOf('.');
            if (lastDot > 0) {
                String basePath = expr.substring(0, lastDot);
                String propName = expr.substring(lastDot + 1);
                // Use JS to check if property exists
                try {
                    Object exists = runtime.eval("typeof " + basePath + " !== 'undefined' && " + basePath + " !== null && '" + propName + "' in " + basePath);
                    if (Boolean.FALSE.equals(exists)) {
                        return "#notpresent";
                    }
                } catch (Exception e) {
                    // If check fails, treat as not present
                    return "#notpresent";
                }
            }
        }
        return result;
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

        // Try to evaluate the first token to see if it's a JS function
        // Syntax: "call fun" or "call fun arg" where fun is a JS function variable
        int spaceIdx = text.indexOf(' ');
        String firstToken = spaceIdx > 0 ? text.substring(0, spaceIdx) : text;

        // Check if it's a read() call - that's definitely a feature call
        if (!text.startsWith("read(")) {
            // Try to evaluate as a JS expression
            try {
                Object evaluated = runtime.eval(firstToken);
                if (evaluated instanceof JsCallable) {
                    // It's a JS function - invoke it
                    JsCallable fn = (JsCallable) evaluated;
                    Object arg = null;
                    if (spaceIdx > 0) {
                        String argExpr = text.substring(spaceIdx + 1).trim();
                        if (!argExpr.isEmpty()) {
                            arg = runtime.eval(argExpr);
                        }
                    }
                    Object result = arg != null
                            ? fn.call(null, new Object[]{arg})
                            : fn.call(null, new Object[0]);
                    // For shared scope call (no assignment), result is ignored
                    return;
                }
            } catch (Exception e) {
                // Not a valid JS expression, fall through to feature call
            }
        }

        // Standard feature call
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
        String expr = step.getDocString();
        if (expr == null) {
            expr = step.getText();
        }
        runtime.eval(expr);
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

    /**
     * Check if string contains JS expression punctuation like . ( [
     * Used to detect if a "keyword" is actually a JS expression like foo.bar or foo()
     * Space is allowed (for multi-word keywords like "form field")
     */
    private boolean hasPunctuation(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            // Check for JS expression punctuation: . ( ) [ ] ' "
            if (c == '.' || c == '(' || c == ')' || c == '[' || c == ']' || c == '\'' || c == '"') {
                return true;
            }
        }
        return false;
    }

    // ========== Karate Expression Helpers ==========

    /**
     * Evaluates $varname[*].path or $varname.path syntax.
     * This is a shortcut for jsonpath on a variable.
     * Examples: $foo[*].name, $response.data, $[*].id (on response)
     */
    private Object evalJsonPathShortcut(String expr) {
        // $varname.path or $varname[...].path
        // Parse: extract variable name and jsonpath
        String withoutDollar = expr.substring(1); // remove leading $

        // Special case: $[...] or $. means use 'response' variable
        if (withoutDollar.startsWith("[") || withoutDollar.startsWith(".")) {
            Object target = runtime.getVariable("response");
            String path = "$" + withoutDollar;
            return JsonPath.read(target, path);
        }

        // Find where the path starts (at . or [)
        int pathStart = -1;
        for (int i = 0; i < withoutDollar.length(); i++) {
            char c = withoutDollar.charAt(i);
            if (c == '.' || c == '[') {
                pathStart = i;
                break;
            }
        }

        String varName;
        String jsonPath;
        if (pathStart < 0) {
            // No path, just $varname - return the variable itself
            varName = withoutDollar;
            return runtime.getVariable(varName);
        } else {
            varName = withoutDollar.substring(0, pathStart);
            jsonPath = "$" + withoutDollar.substring(pathStart);
        }

        Object target = runtime.getVariable(varName);
        if (target == null) {
            return null;
        }
        return JsonPath.read(target, jsonPath);
    }

    /**
     * Evaluates get[N] varname path or get varname path syntax.
     * Examples: get[0] foo[*].name, get foo $..bar
     */
    private Object evalGetExpression(String expr) {
        int index = -1;
        String remainder;

        if (expr.startsWith("get[")) {
            // get[N] syntax
            int closeBracket = expr.indexOf(']');
            if (closeBracket < 0) {
                throw new RuntimeException("Invalid get expression, missing ]: " + expr);
            }
            index = Integer.parseInt(expr.substring(4, closeBracket));
            remainder = expr.substring(closeBracket + 1).trim();
        } else {
            // get varname path
            remainder = expr.substring(4).trim();
        }

        // Parse varname and path - could be "varname path" or "varname[...]"
        String varName;
        String jsonPath;

        // Check if path starts with $ (explicit jsonpath)
        if (remainder.startsWith("$")) {
            // get $..path or get[0] $[*].foo - operates on 'response'
            varName = "response";
            jsonPath = remainder;
        } else {
            // Find space or bracket to split varname from path
            int spaceIdx = remainder.indexOf(' ');
            int bracketIdx = remainder.indexOf('[');

            if (spaceIdx > 0 && (bracketIdx < 0 || spaceIdx < bracketIdx)) {
                // "varname path" format - e.g., "json $['sp ace']" or "json .foo"
                varName = remainder.substring(0, spaceIdx);
                String path = remainder.substring(spaceIdx).trim();
                // Don't add $ if path already starts with $
                jsonPath = path.startsWith("$") ? path : "$" + path;
            } else if (bracketIdx > 0) {
                // "varname[*].path" format
                varName = remainder.substring(0, bracketIdx);
                jsonPath = "$" + remainder.substring(bracketIdx);
            } else {
                // Just varname, no path
                varName = remainder;
                jsonPath = "$";
            }
        }

        Object target = runtime.getVariable(varName);
        if (target == null) {
            return null;
        }

        Object result = JsonPath.read(target, jsonPath);

        // Apply index if specified
        if (index >= 0 && result instanceof List) {
            List<?> list = (List<?>) result;
            if (index < list.size()) {
                return list.get(index);
            }
            return null;
        }

        return result;
    }

    // ========== Embedded Expression Processing ==========

    /**
     * Marker object to indicate a key should be removed (for ##() optional expressions).
     */
    private static final Object REMOVE_MARKER = new Object();

    /**
     * Process embedded expressions (#() and ##()) in a value.
     * - #(expr) evaluates expr and substitutes the result
     * - ##(expr) evaluates expr; if null, removes the key (returns REMOVE_MARKER)
     */
    private Object processEmbeddedExpressions(Object value) {
        if (value instanceof Node) {
            processXmlEmbeddedExpressions((Node) value);
            return value;
        } else if (value instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) value;
            Map<String, Object> result = new LinkedHashMap<>();
            for (Map.Entry<String, Object> entry : map.entrySet()) {
                Object processed = processEmbeddedExpressions(entry.getValue());
                if (processed != REMOVE_MARKER) {
                    result.put(entry.getKey(), processed);
                }
            }
            return result;
        } else if (value instanceof List) {
            @SuppressWarnings("unchecked")
            List<Object> list = (List<Object>) value;
            List<Object> result = new ArrayList<>();
            for (Object item : list) {
                Object processed = processEmbeddedExpressions(item);
                if (processed != REMOVE_MARKER) {
                    result.add(processed);
                }
            }
            return result;
        } else if (value instanceof String str) {
            return processEmbeddedString(str);
        }
        return value;
    }

    /**
     * Process a string that may contain embedded expressions.
     */
    private Object processEmbeddedString(String str) {
        // Check for optional embedded: ##(...)
        if (str.startsWith("##(") && str.endsWith(")")) {
            String expr = str.substring(3, str.length() - 1);
            Object result = runtime.eval(expr);
            return result == null ? REMOVE_MARKER : result;
        }
        // Check for regular embedded: #(...)
        if (str.startsWith("#(") && str.endsWith(")")) {
            String expr = str.substring(2, str.length() - 1);
            return runtime.eval(expr);
        }
        // Check for embedded expressions within a larger string
        // e.g., "Hello #(name)!" or "Value: ##(optional)"
        if (str.contains("#(")) {
            return processInlineEmbedded(str);
        }
        return str;
    }

    /**
     * Process inline embedded expressions like "Hello #(name)!"
     */
    private Object processInlineEmbedded(String str) {
        StringBuilder result = new StringBuilder();
        int i = 0;
        while (i < str.length()) {
            int hashPos = str.indexOf('#', i);
            if (hashPos < 0) {
                result.append(str.substring(i));
                break;
            }
            result.append(str.substring(i, hashPos));

            boolean optional = false;
            int exprStart;
            if (hashPos + 1 < str.length() && str.charAt(hashPos + 1) == '#') {
                // ##( optional
                if (hashPos + 2 < str.length() && str.charAt(hashPos + 2) == '(') {
                    optional = true;
                    exprStart = hashPos + 3;
                } else {
                    result.append("##");
                    i = hashPos + 2;
                    continue;
                }
            } else if (hashPos + 1 < str.length() && str.charAt(hashPos + 1) == '(') {
                // #( regular
                exprStart = hashPos + 2;
            } else {
                result.append('#');
                i = hashPos + 1;
                continue;
            }

            // Find matching closing paren
            int depth = 1;
            int j = exprStart;
            while (j < str.length() && depth > 0) {
                char c = str.charAt(j);
                if (c == '(') depth++;
                else if (c == ')') depth--;
                j++;
            }
            if (depth != 0) {
                // Unbalanced parens, treat as literal
                result.append(optional ? "##(" : "#(");
                i = exprStart;
                continue;
            }

            String expr = str.substring(exprStart, j - 1);
            Object value = runtime.eval(expr);
            if (optional && value == null) {
                // For inline optional, substitute empty string
                // (key removal only applies to whole-value expressions)
            } else if (value != null) {
                result.append(stringify(value));
            }
            i = j;
        }
        return result.toString();
    }

    /**
     * Process embedded expressions in XML nodes.
     * Handles both attribute values and text content with #() and ##() expressions.
     */
    private void processXmlEmbeddedExpressions(Node node) {
        if (node.getNodeType() == Node.DOCUMENT_NODE) {
            node = node.getFirstChild();
        }
        if (node == null) return;

        // Process attributes
        org.w3c.dom.NamedNodeMap attribs = node.getAttributes();
        if (attribs != null) {
            List<org.w3c.dom.Attr> toRemove = new ArrayList<>();
            for (int i = 0; i < attribs.getLength(); i++) {
                org.w3c.dom.Attr attrib = (org.w3c.dom.Attr) attribs.item(i);
                String value = attrib.getValue();
                if (value != null && value.contains("#(")) {
                    boolean optional = value.startsWith("##(");
                    if (value.startsWith("#(") || optional) {
                        String expr = value.substring(optional ? 3 : 2, value.length() - 1);
                        try {
                            Object result = runtime.eval(expr);
                            if (optional && result == null) {
                                toRemove.add(attrib);
                            } else {
                                attrib.setValue(result == null ? "" : stringify(result));
                            }
                        } catch (Exception e) {
                            // Leave as-is on error
                        }
                    } else {
                        // Inline embedded in attribute
                        attrib.setValue(processInlineEmbedded(value).toString());
                    }
                }
            }
            for (org.w3c.dom.Attr attr : toRemove) {
                attribs.removeNamedItem(attr.getName());
            }
        }

        // Process child nodes
        org.w3c.dom.NodeList children = node.getChildNodes();
        List<Node> childList = new ArrayList<>();
        for (int i = 0; i < children.getLength(); i++) {
            childList.add(children.item(i));
        }

        List<Node> elementsToRemove = new ArrayList<>();
        for (Node child : childList) {
            String value = child.getNodeValue();
            if (value != null) {
                value = value.trim();
                if (value.startsWith("#(") || value.startsWith("##(")) {
                    boolean optional = value.startsWith("##(");
                    String expr = value.substring(optional ? 3 : 2, value.length() - 1);
                    try {
                        Object result = runtime.eval(expr);
                        if (optional && result == null) {
                            elementsToRemove.add(child);
                        } else if (result instanceof Node) {
                            // Replace with XML node
                            Node evalNode = (Node) result;
                            if (evalNode.getNodeType() == Node.DOCUMENT_NODE) {
                                evalNode = evalNode.getFirstChild();
                            }
                            evalNode = node.getOwnerDocument().importNode(evalNode, true);
                            child.getParentNode().replaceChild(evalNode, child);
                        } else {
                            child.setNodeValue(result == null ? "" : stringify(result));
                        }
                    } catch (Exception e) {
                        // Leave as-is on error
                    }
                } else if (value.contains("#(")) {
                    // Inline embedded in text
                    child.setNodeValue(processInlineEmbedded(value).toString());
                }
            } else if (child.hasChildNodes() || child.hasAttributes()) {
                processXmlEmbeddedExpressions(child);
            }
        }

        // Remove elements marked for removal (for ##() that evaluated to null)
        for (Node toRemove : elementsToRemove) {
            Node parent = toRemove.getParentNode();
            if (parent != null) {
                Node grandParent = parent.getParentNode();
                if (grandParent != null) {
                    grandParent.removeChild(parent);
                }
            }
        }
    }

}
