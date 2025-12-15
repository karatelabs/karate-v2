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
import io.karatelabs.gherkin.Step;
import io.karatelabs.gherkin.Table;
import io.karatelabs.io.http.HttpRequestBuilder;
import io.karatelabs.io.http.HttpResponse;
import io.karatelabs.log.LogContext;
import io.karatelabs.match.Match;
import io.karatelabs.match.Result;

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

        try {
            String keyword = step.getKeyword();
            if (keyword == null) {
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
                    case "form" -> executeForm(step);
                    case "request" -> executeRequest(step);
                    case "method" -> executeMethod(step);
                    case "status" -> executeStatus(step);
                    case "multipart" -> executeMultipart(step);

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
            return result;

        } catch (AssertionError | Exception e) {
            long elapsedNanos = System.nanoTime() - startNanos;
            StepResult result = StepResult.failed(step, startTime, elapsedNanos, e);
            result.setLog(LogContext.get().collect());
            return result;
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
        Object value = runtime.eval(expr);
        runtime.setVariable(name, value);
    }

    private void executeSet(Step step) {
        // set supports nested path setting: set foo.bar = value
        String text = step.getText();
        int eqIndex = findAssignmentOperator(text);
        if (eqIndex < 0) {
            throw new RuntimeException("set requires '=' assignment: " + text);
        }
        String path = text.substring(0, eqIndex).trim();
        String expr = text.substring(eqIndex + 1).trim();
        Object value = runtime.eval(expr);

        // Check if it's a simple variable or nested path
        int dotIndex = path.indexOf('.');
        int bracketIndex = path.indexOf('[');
        if (dotIndex < 0 && bracketIndex < 0) {
            // Simple variable
            runtime.setVariable(path, value);
        } else {
            // Nested path - use JS engine to set
            runtime.eval(path + " = " + Json.stringifyStrict(value));
        }
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
        String pathExpr = step.getText();
        Object path = runtime.eval(pathExpr);
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

    private void executeForm(Step step) {
        // Form field
        String text = step.getText();
        int eqIndex = findAssignmentOperator(text);
        String name = text.substring(0, eqIndex).trim();
        Object value = runtime.eval(text.substring(eqIndex + 1).trim());
        // Set content type if not already set
        if (http().getContentType() == null) {
            http().contentType("application/x-www-form-urlencoded");
        }
        // For now, set body as form data
        http().body(name + "=" + value);
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

    private void executeMultipart(Step step) {
        // Basic multipart support - to be expanded
        String text = step.getText();
        if (text.startsWith("file")) {
            // multipart file
            throw new RuntimeException("multipart file not yet implemented");
        } else if (text.startsWith("field")) {
            // multipart field
            throw new RuntimeException("multipart field not yet implemented");
        }
    }

    // ========== Control Flow ==========

    private void executeCall(Step step) {
        // Basic call support - to be expanded with FeatureRuntime
        String text = step.getText();
        // For now, just evaluate as JS expression
        runtime.eval(text);
    }

    private void executeCallOnce(Step step) {
        // CallOnce requires caching - to be implemented with FeatureRuntime
        executeCall(step);
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
