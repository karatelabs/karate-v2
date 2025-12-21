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
import io.karatelabs.common.StringUtils;
import io.karatelabs.common.Xml;
import io.karatelabs.io.http.ApacheHttpClient;
import io.karatelabs.io.http.HttpClient;
import io.karatelabs.io.http.HttpRequestBuilder;
import io.karatelabs.gherkin.Feature;
import io.karatelabs.gherkin.MatchExpression;
import io.karatelabs.js.Context;
import io.karatelabs.js.Engine;
import io.karatelabs.js.GherkinParser;
import io.karatelabs.js.Invokable;
import io.karatelabs.js.JsCallable;
import io.karatelabs.js.SimpleObject;
import io.karatelabs.log.LogContext;
import io.karatelabs.markup.Markup;
import io.karatelabs.markup.ResourceResolver;
import io.karatelabs.match.Match;
import io.karatelabs.match.Result;
import io.karatelabs.match.Value;
import io.karatelabs.process.ProcessBuilder;
import io.karatelabs.process.ProcessConfig;
import io.karatelabs.process.ProcessHandle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import com.jayway.jsonpath.JsonPath;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

public class KarateJs implements SimpleObject {

    private static final Logger logger = LoggerFactory.getLogger(KarateJs.class);

    public final Resource root;
    public final Engine engine;
    public final HttpClient client;
    public final HttpRequestBuilder http;

    private ResourceResolver resolver;
    private Markup _markup;
    private Consumer<String> onDoc;
    private BiConsumer<Context, Result> onMatch;
    private Function<String, Map<String, Object>> setupProvider;
    private Function<String, Map<String, Object>> setupOnceProvider;
    private Runnable abortHandler;
    private BiFunction<String, Object, Map<String, Object>> callProvider;
    private BiFunction<String, Object, Map<String, Object>> callOnceProvider;
    private BiFunction<String, Object, Object> callSingleProvider;
    private Supplier<Map<String, Object>> infoProvider;
    private Supplier<Map<String, Object>> scenarioProvider;
    private Consumer<Object> signalConsumer;
    private BiConsumer<String, Object> configureHandler;
    private Supplier<Resource> currentResourceProvider;
    private String env;

    private final JsCallable read;

    public KarateJs(Resource root) {
        this(root, new ApacheHttpClient());
    }

    public KarateJs(Resource root, HttpClient client) {
        this.root = root;
        this.client = client;
        http = new HttpRequestBuilder(client);
        this.engine = new Engine();
        engine.setOnConsoleLog(s -> LogContext.get().log(s));
        // TODO: implement whitelisting for safety - currently allows access to all Java classes
        engine.setExternalBridge(new io.karatelabs.js.ExternalBridge() {});
        read = (context, args) -> {
            if (args.length == 0) {
                throw new RuntimeException("read() needs at least one argument");
            }
            String rawPath = args[0] + "";

            // Parse tag selector for feature files
            // Supports: file.feature@tag or @tag (same-file)
            String path;
            String tagSelector = null;
            if (rawPath.startsWith("@")) {
                // Same-file tag - return a FeatureCall wrapper
                return new FeatureCall(null, rawPath);
            } else {
                int tagPos = rawPath.indexOf(".feature@");
                if (tagPos != -1) {
                    path = rawPath.substring(0, tagPos + 8);  // "file.feature"
                    tagSelector = "@" + rawPath.substring(tagPos + 9);  // "@tag"
                } else {
                    path = rawPath;
                }
            }

            // V1 compatibility: handle 'this:' prefix for relative paths
            // 'this:file.ext' means relative to current feature file's directory
            Resource resource;
            if (path.startsWith("this:")) {
                path = path.substring(5);  // Remove 'this:' prefix
                // Resolve relative to current feature's resource
                Resource currentResource = currentResourceProvider != null ? currentResourceProvider.get() : null;
                resource = currentResource != null ? currentResource.resolve(path) : root.resolve(path);
            } else {
                resource = root.resolve(path);
            }
            return switch (resource.getExtension()) {
                case "json" -> Json.of(resource.getText()).value();
                case "js" -> engine.eval(resource.getText());
                case "feature" -> {
                    Feature feature = Feature.read(resource);
                    yield tagSelector != null ? new FeatureCall(feature, tagSelector) : feature;
                }
                case "xml" -> {
                    // Parse XML and process embedded expressions
                    Document doc = Xml.toXmlDoc(resource.getText());
                    processXmlEmbeddedExpressions(doc);
                    yield doc;
                }
                default -> resource.getText();
            };
        };
        engine.put("karate", this);
        engine.put("read", read);
        engine.put("match", matchFluent());
    }

    public Markup markup() {
        if (_markup == null) {
            if (resolver != null) {
                _markup = Markup.init(engine, resolver);
            } else {
                _markup = Markup.init(engine, root.getPrefixedPath());
            }
        }
        return _markup;
    }

    public void setOnDoc(Consumer<String> onDoc) {
        this.onDoc = onDoc;
    }

    public void setResourceResolver(ResourceResolver resolver) {
        this.resolver = resolver;
    }

    public void setOnMatch(BiConsumer<Context, Result> onMatch) {
        this.onMatch = onMatch;
    }

    public void setSetupProvider(Function<String, Map<String, Object>> provider) {
        this.setupProvider = provider;
    }

    public void setSetupOnceProvider(Function<String, Map<String, Object>> provider) {
        this.setupOnceProvider = provider;
    }

    public void setAbortHandler(Runnable handler) {
        this.abortHandler = handler;
    }

    public void setCallProvider(BiFunction<String, Object, Map<String, Object>> provider) {
        this.callProvider = provider;
    }

    public void setCallOnceProvider(BiFunction<String, Object, Map<String, Object>> provider) {
        this.callOnceProvider = provider;
    }

    public void setCallSingleProvider(BiFunction<String, Object, Object> provider) {
        this.callSingleProvider = provider;
    }

    public void setInfoProvider(Supplier<Map<String, Object>> provider) {
        this.infoProvider = provider;
    }

    public void setScenarioProvider(Supplier<Map<String, Object>> provider) {
        this.scenarioProvider = provider;
    }

    public void setEnv(String env) {
        this.env = env;
    }

    public void setSignalConsumer(Consumer<Object> consumer) {
        this.signalConsumer = consumer;
    }

    public void setConfigureHandler(BiConsumer<String, Object> handler) {
        this.configureHandler = handler;
    }

    public void setCurrentResourceProvider(Supplier<Resource> provider) {
        this.currentResourceProvider = provider;
    }

    private Map<String, Object> getInfo() {
        if (infoProvider != null) {
            return infoProvider.get();
        }
        return Map.of();
    }

    private Map<String, Object> getScenario() {
        if (scenarioProvider != null) {
            return scenarioProvider.get();
        }
        return Map.of();
    }

    private Invokable abort() {
        return args -> {
            if (abortHandler != null) {
                abortHandler.run();
            }
            return null;
        };
    }

    private Invokable fail() {
        return args -> {
            String message = args.length > 0 && args[0] != null ? args[0].toString() : "karate.fail() called";
            throw new RuntimeException(message);
        };
    }

    /**
     * Renders an HTML template and returns the result.
     * Also sends to onDoc consumer if set.
     * Called by the 'doc' keyword in StepExecutor.
     *
     * @param options map containing 'read' key with template path
     * @return the rendered HTML string
     */
    public String doc(Map<String, Object> options) {
        String read = (String) options.get("read");
        if (read == null) {
            throw new RuntimeException("doc() requires 'read' key with template path");
        }
        String html = markup().processPath(read, null);
        if (onDoc != null) {
            onDoc.accept(html);
        }
        return html;
    }

    @SuppressWarnings("unchecked")
    private Invokable doc() {
        return args -> {
            if (onDoc == null) {
                logger.warn("doc() called, but no destination set");
                return null;
            }
            if (args.length == 0) {
                throw new RuntimeException("doc() needs at least one argument");
            }
            String read;
            if (args[0] instanceof Map) {
                Map<String, Object> map = (Map<String, Object>) args[0];
                read = (String) map.get("read");
            } else if (args[0] == null) {
                read = null;
            } else {
                read = args[0] + "";
            }
            if (read == null) {
                throw new RuntimeException("doc() read arg should not be null");
            }
            Map<String, Object> vars;
            if (args.length > 1) {
                vars = (Map<String, Object>) args[1];
            } else {
                vars = null;
            }
            String html = markup().processPath(read, vars);
            onDoc.accept(html);
            return null;
        };
    }

    private Invokable http() {
        return args -> {
            if (args.length > 0) {
                http.url(args[0] + "");
            }
            return http;
        };
    }

    private Invokable readAsString() {
        return args -> {
            if (args.length == 0) {
                throw new RuntimeException("read() needs at least one argument");
            }
            Resource resource = root.resolve(args[0] + "");
            return resource.getText();
        };
    }

    private Invokable get() {
        return args -> {
            if (args.length == 0) {
                throw new RuntimeException("get() needs at least one argument");
            }
            String expr = args[0] + "";

            // Check if it's a jsonpath expression like $varname.path or $varname[*].path
            if (expr.startsWith("$") && expr.length() > 1) {
                String withoutDollar = expr.substring(1);
                // Find where the path starts (at . or [)
                int pathStart = -1;
                for (int i = 0; i < withoutDollar.length(); i++) {
                    char c = withoutDollar.charAt(i);
                    if (c == '.' || c == '[') {
                        pathStart = i;
                        break;
                    }
                }
                if (pathStart > 0) {
                    String varName = withoutDollar.substring(0, pathStart);
                    String jsonPath = "$" + withoutDollar.substring(pathStart);
                    Object target = engine.get(varName);
                    if (target != null) {
                        return JsonPath.read(target, jsonPath);
                    }
                    return null;
                } else if (pathStart == 0) {
                    // $. or $[ means use 'response'
                    Object target = engine.get("response");
                    if (target != null) {
                        return JsonPath.read(target, "$" + withoutDollar);
                    }
                    return null;
                }
                // Just $varname - return the variable
                return engine.get(withoutDollar);
            }

            // Simple variable lookup
            Object result = engine.get(expr);
            if (result == null && args.length > 1) {
                return args[1];
            }
            return result;
        };
    }

    @SuppressWarnings("unchecked")
    private Invokable set() {
        return args -> {
            if (args.length < 2) {
                throw new RuntimeException("set() needs at least two arguments: name and value");
            }
            String name = args[0] + "";
            if (args.length == 2) {
                // Simple set: karate.set('name', value)
                engine.put(name, args[1]);
            } else {
                // Path set: karate.set('name', 'path', value)
                String path = args[1] + "";
                Object value = args[2];
                Object target = engine.get(name);

                // Check if this is XPath (path starts with /) or target is XML
                if (path.startsWith("/") || target instanceof Node) {
                    // XPath set on XML
                    Document doc;
                    if (target instanceof Document) {
                        doc = (Document) target;
                    } else if (target instanceof Node) {
                        doc = ((Node) target).getOwnerDocument();
                    } else if (target == null) {
                        // Create new XML document
                        doc = Xml.newDocument();
                        engine.put(name, doc);
                    } else if (target instanceof String && Xml.isXml((String) target)) {
                        // Convert XML string to Document
                        doc = Xml.toXmlDoc((String) target);
                        engine.put(name, doc);
                    } else {
                        throw new RuntimeException("cannot set xpath on non-XML variable: " + name);
                    }
                    if (value instanceof Node) {
                        Xml.setByPath(doc, path, (Node) value);
                    } else {
                        Xml.setByPath(doc, path, value == null ? "" : value.toString());
                    }
                } else if (target == null) {
                    target = new java.util.LinkedHashMap<>();
                    engine.put(name, target);
                    // Direct path set for JSON
                    String navPath = path.startsWith("$.") ? path.substring(2) : path;
                    setAtPath(target, navPath, value);
                } else {
                    // Handle special jsonpath cases
                    if (path.endsWith("[]")) {
                        // Append to array: $.foo[] means add to foo array
                        String arrayPath = path.substring(0, path.length() - 2);
                        if (arrayPath.equals("$")) {
                            // Root is array
                            if (target instanceof List) {
                                ((List<Object>) target).add(value);
                            }
                        } else {
                            // Navigate to array and append
                            String navPath = arrayPath.substring(2); // remove "$."
                            Object arr = navigateToPath(target, navPath);
                            if (arr instanceof List) {
                                ((List<Object>) arr).add(value);
                            }
                        }
                    } else {
                        // Direct path set
                        String navPath = path.startsWith("$.") ? path.substring(2) : path;
                        setAtPath(target, navPath, value);
                    }
                }
            }
            return null;
        };
    }

    @SuppressWarnings("unchecked")
    private Object navigateToPath(Object target, String path) {
        String[] parts = path.split("\\.");
        Object current = target;
        for (String part : parts) {
            if (current instanceof Map) {
                current = ((Map<String, Object>) current).get(part);
            } else {
                return null;
            }
        }
        return current;
    }

    @SuppressWarnings("unchecked")
    private void setAtPath(Object target, String path, Object value) {
        String[] parts = path.split("\\.");
        Object current = target;
        for (int i = 0; i < parts.length - 1; i++) {
            if (current instanceof Map) {
                Map<String, Object> map = (Map<String, Object>) current;
                Object next = map.get(parts[i]);
                if (next == null) {
                    next = new java.util.LinkedHashMap<>();
                    map.put(parts[i], next);
                }
                current = next;
            }
        }
        if (current instanceof Map) {
            ((Map<String, Object>) current).put(parts[parts.length - 1], value);
        }
    }

    /**
     * Fluent match API for global match() function.
     * Usage: match(obj).contains({...}), match(obj)._equals({...})
     * Returns a Match.Value for chaining.
     */
    private Invokable matchFluent() {
        return args -> {
            if (args.length == 0) {
                throw new RuntimeException("match() needs at least one argument");
            }
            // Return a Value for fluent API: match(obj).contains(...)
            return Match.evaluate(args[0], null, (context, result) -> {
                if (onMatch != null) {
                    onMatch.accept(context, result);
                }
            });
        };
    }

    /**
     * V1-compatible karate.match() function.
     * Usage: karate.match(actual, expected) or karate.match("foo == expected")
     * Returns { pass: boolean, message: String|null }
     */
    private Invokable karateMatch() {
        return args -> {
            if (args.length == 0) {
                throw new RuntimeException("karate.match() needs at least one argument");
            }
            if (args.length >= 2) {
                // Two-argument form: karate.match(actual, expected)
                // Do an equals comparison and return { pass, message }
                Object actual = args[0];
                Object expected = args[1];
                Value value = Match.evaluate(actual, null, null);
                Result result = value._equals(expected);
                return result.toMap();
            } else {
                // One-argument string form: karate.match("foo == expected")
                // Use GherkinParser for proper lexer-based parsing
                String expression = args[0].toString();
                MatchExpression parsed = GherkinParser.parseMatchExpression(expression);

                Object actual = engine.get(parsed.getActualExpr());
                Object expected = engine.eval(parsed.getExpectedExpr());
                Value value = Match.evaluate(actual, null, null);
                Match.Type matchType = Match.Type.valueOf(parsed.getMatchTypeName());
                Result result = value.is(matchType, expected);
                return result.toMap();
            }
        };
    }

    private Invokable toStringPretty() {
        return args -> {
            if (args.length == 0) {
                throw new RuntimeException("toStringPretty() needs at least one argument");
            }
            if (args[0] instanceof List || args[0] instanceof Map) {
                return StringUtils.formatJson(args[0]);
            } else if (args[0] instanceof Node) {
                return Xml.toString((Node) args[0], true);
            } else {
                return args[0] + "";
            }
        };
    }

    private Invokable setup() {
        return args -> {
            if (setupProvider == null) {
                throw new RuntimeException("karate.setup() is not available in this context");
            }
            String name = args.length > 0 && args[0] != null ? args[0].toString() : null;
            return setupProvider.apply(name);
        };
    }

    private Invokable setupOnce() {
        return args -> {
            if (setupOnceProvider == null) {
                throw new RuntimeException("karate.setupOnce() is not available in this context");
            }
            String name = args.length > 0 && args[0] != null ? args[0].toString() : null;
            return setupOnceProvider.apply(name);
        };
    }

    private Invokable call() {
        return args -> {
            if (callProvider == null) {
                throw new RuntimeException("karate.call() is not available in this context");
            }
            if (args.length == 0) {
                throw new RuntimeException("karate.call() requires at least one argument (feature path)");
            }
            String path = args[0].toString();
            Object arg = args.length > 1 ? args[1] : null;
            return callProvider.apply(path, arg);
        };
    }

    /**
     * karate.callonce() - Execute a feature file once per feature and cache the result.
     * Same as: callonce result = call read('path')
     */
    private Invokable callonce() {
        return args -> {
            if (callOnceProvider == null) {
                throw new RuntimeException("karate.callonce() is not available in this context");
            }
            if (args.length == 0) {
                throw new RuntimeException("karate.callonce() requires at least one argument (feature path)");
            }
            String path = args[0].toString();
            Object arg = args.length > 1 ? args[1] : null;
            return callOnceProvider.apply(path, arg);
        };
    }

    /**
     * karate.callSingle() - Execute a feature/JS file once per Suite and cache the result.
     * All parallel threads share the same cached result.
     *
     * Uses Suite-level locking to ensure only one thread executes the call,
     * while others wait and receive the cached result.
     */
    private Invokable callSingle() {
        return args -> {
            if (callSingleProvider == null) {
                throw new RuntimeException("karate.callSingle() is not available in this context");
            }
            if (args.length == 0) {
                throw new RuntimeException("karate.callSingle() requires at least one argument (path)");
            }
            String path = args[0].toString();
            Object arg = args.length > 1 ? args[1] : null;
            return callSingleProvider.apply(path, arg);
        };
    }

    /**
     * karate.configure() - Apply configuration from JavaScript.
     * Usage: karate.configure('key', value)
     */
    private Invokable configure() {
        return args -> {
            if (args.length < 2) {
                throw new RuntimeException("configure() needs two arguments: key and value");
            }
            String key = args[0].toString();
            Object value = args[1];
            if (configureHandler != null) {
                configureHandler.accept(key, value);
            }
            return null;
        };
    }

    // ========== Collection Utilities ==========

    private Invokable jsonPath() {
        return args -> {
            if (args.length < 2) {
                throw new RuntimeException("jsonPath() needs two arguments: object and path");
            }
            Object json = args[0];
            String path = args[1].toString();
            return JsonPath.read(json, path);
        };
    }

    @SuppressWarnings("unchecked")
    private Invokable forEach() {
        return args -> {
            if (args.length < 2) {
                throw new RuntimeException("forEach() needs two arguments: collection and function");
            }
            Object collection = args[0];
            JsCallable fn = (JsCallable) args[1];
            if (collection instanceof List) {
                List<?> list = (List<?>) collection;
                for (int i = 0; i < list.size(); i++) {
                    fn.call(null, new Object[]{list.get(i), i});
                }
            } else if (collection instanceof Map) {
                Map<String, Object> map = (Map<String, Object>) collection;
                int i = 0;
                for (Map.Entry<String, Object> entry : map.entrySet()) {
                    fn.call(null, new Object[]{entry.getKey(), entry.getValue(), i++});
                }
            }
            return null;
        };
    }

    @SuppressWarnings("unchecked")
    private Invokable map() {
        return args -> {
            if (args.length < 2) {
                throw new RuntimeException("map() needs two arguments: list and function");
            }
            List<?> list = (List<?>) args[0];
            JsCallable fn = (JsCallable) args[1];
            List<Object> result = new ArrayList<>();
            for (int i = 0; i < list.size(); i++) {
                result.add(fn.call(null, new Object[]{list.get(i), i}));
            }
            return result;
        };
    }

    @SuppressWarnings("unchecked")
    private Invokable filter() {
        return args -> {
            if (args.length < 2) {
                throw new RuntimeException("filter() needs two arguments: list and function");
            }
            List<?> list = (List<?>) args[0];
            JsCallable fn = (JsCallable) args[1];
            List<Object> result = new ArrayList<>();
            for (int i = 0; i < list.size(); i++) {
                Object item = list.get(i);
                Object keep = fn.call(null, new Object[]{item, i});
                if (Boolean.TRUE.equals(keep)) {
                    result.add(item);
                }
            }
            return result;
        };
    }

    @SuppressWarnings("unchecked")
    private Invokable merge() {
        return args -> {
            Map<String, Object> result = new LinkedHashMap<>();
            for (Object arg : args) {
                if (arg instanceof Map) {
                    result.putAll((Map<String, Object>) arg);
                }
            }
            return result;
        };
    }

    @SuppressWarnings("unchecked")
    private Invokable append() {
        return args -> {
            if (args.length < 2) {
                throw new RuntimeException("append() needs at least two arguments");
            }
            List<Object> result = new ArrayList<>();
            Object first = args[0];
            if (first instanceof List) {
                result.addAll((List<?>) first);
            } else {
                result.add(first);
            }
            for (int i = 1; i < args.length; i++) {
                Object item = args[i];
                if (item instanceof List) {
                    result.addAll((List<?>) item);
                } else {
                    result.add(item);
                }
            }
            return result;
        };
    }

    @SuppressWarnings("unchecked")
    private Invokable appendTo() {
        return args -> {
            if (args.length < 2) {
                throw new RuntimeException("appendTo() needs at least two arguments: list and item(s)");
            }
            List<Object> list = (List<Object>) args[0];
            for (int i = 1; i < args.length; i++) {
                Object item = args[i];
                if (item instanceof List) {
                    list.addAll((List<?>) item);
                } else {
                    list.add(item);
                }
            }
            return list;
        };
    }

    @SuppressWarnings("unchecked")
    private Invokable sort() {
        return args -> {
            if (args.length < 2) {
                throw new RuntimeException("sort() needs two arguments: list and key function");
            }
            List<?> list = (List<?>) args[0];
            JsCallable fn = (JsCallable) args[1];
            List<Object> result = new ArrayList<>(list);
            result.sort((a, b) -> {
                Object keyA = fn.call(null, new Object[]{a});
                Object keyB = fn.call(null, new Object[]{b});
                if (keyA instanceof Comparable && keyB instanceof Comparable) {
                    return ((Comparable<Object>) keyA).compareTo(keyB);
                }
                return 0;
            });
            return result;
        };
    }

    private Invokable mapWithKey() {
        return args -> {
            if (args.length < 2) {
                throw new RuntimeException("mapWithKey() needs two arguments: list and key name");
            }
            Object listArg = args[0];
            if (listArg == null) {
                return new ArrayList<>();
            }
            List<?> list = (List<?>) listArg;
            String key = args[1].toString();
            List<Map<String, Object>> result = new ArrayList<>();
            for (Object item : list) {
                Map<String, Object> map = new LinkedHashMap<>();
                map.put(key, item);
                result.add(map);
            }
            return result;
        };
    }

    @SuppressWarnings("unchecked")
    private Invokable filterKeys() {
        return args -> {
            if (args.length < 2) {
                throw new RuntimeException("filterKeys() needs at least two arguments");
            }
            Map<String, Object> source = (Map<String, Object>) args[0];
            Map<String, Object> result = new LinkedHashMap<>();

            if (args[1] instanceof Map) {
                // filterKeys(source, keysFromMap) - filter by keys present in the map
                Map<String, Object> keysMap = (Map<String, Object>) args[1];
                for (String key : keysMap.keySet()) {
                    if (source.containsKey(key)) {
                        result.put(key, source.get(key));
                    }
                }
            } else if (args[1] instanceof List) {
                // filterKeys(source, [key1, key2, ...])
                List<String> keys = (List<String>) args[1];
                for (String key : keys) {
                    if (source.containsKey(key)) {
                        result.put(key, source.get(key));
                    }
                }
            } else {
                // filterKeys(source, key1, key2, ...)
                for (int i = 1; i < args.length; i++) {
                    String key = args[i].toString();
                    if (source.containsKey(key)) {
                        result.put(key, source.get(key));
                    }
                }
            }
            return result;
        };
    }

    private Invokable sizeOf() {
        return args -> {
            if (args.length == 0) {
                throw new RuntimeException("sizeOf() needs one argument");
            }
            Object obj = args[0];
            if (obj instanceof List) {
                return ((List<?>) obj).size();
            } else if (obj instanceof Map) {
                return ((Map<?, ?>) obj).size();
            } else if (obj instanceof String) {
                return ((String) obj).length();
            }
            return 0;
        };
    }

    @SuppressWarnings("unchecked")
    private Invokable keysOf() {
        return args -> {
            if (args.length == 0) {
                throw new RuntimeException("keysOf() needs one argument");
            }
            Map<String, Object> map = (Map<String, Object>) args[0];
            return new ArrayList<>(map.keySet());
        };
    }

    @SuppressWarnings("unchecked")
    private Invokable valuesOf() {
        return args -> {
            if (args.length == 0) {
                throw new RuntimeException("valuesOf() needs one argument");
            }
            Object obj = args[0];
            if (obj instanceof Map) {
                return new ArrayList<>(((Map<String, Object>) obj).values());
            } else if (obj instanceof List) {
                return new ArrayList<>((List<?>) obj);
            }
            return new ArrayList<>();
        };
    }

    private Invokable repeat() {
        return args -> {
            if (args.length < 2) {
                throw new RuntimeException("repeat() needs two arguments: count and function");
            }
            int count = ((Number) args[0]).intValue();
            JsCallable fn = (JsCallable) args[1];
            List<Object> result = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                result.add(fn.call(null, new Object[]{i}));
            }
            return result;
        };
    }

    private Invokable eval() {
        return args -> {
            if (args.length == 0) {
                throw new RuntimeException("eval() needs one argument");
            }
            return engine.eval(args[0].toString());
        };
    }

    private Invokable extract() {
        return args -> {
            if (args.length < 3) {
                throw new RuntimeException("extract() needs three arguments: text, regex, group");
            }
            String text = args[0].toString();
            String regex = args[1].toString();
            int group = ((Number) args[2]).intValue();
            Pattern pattern = Pattern.compile(regex);
            Matcher matcher = pattern.matcher(text);
            if (!matcher.find()) {
                return null;
            }
            return matcher.group(group);
        };
    }

    private Invokable extractAll() {
        return args -> {
            if (args.length < 3) {
                throw new RuntimeException("extractAll() needs three arguments: text, regex, group");
            }
            String text = args[0].toString();
            String regex = args[1].toString();
            int group = ((Number) args[2]).intValue();
            Pattern pattern = Pattern.compile(regex);
            Matcher matcher = pattern.matcher(text);
            List<String> list = new ArrayList<>();
            while (matcher.find()) {
                list.add(matcher.group(group));
            }
            return list;
        };
    }

    private Map<String, Object> getOsInfo() {
        Map<String, Object> result = new LinkedHashMap<>();
        String osName = System.getProperty("os.name", "unknown").toLowerCase();
        result.put("name", System.getProperty("os.name", "unknown"));
        if (osName.contains("win")) {
            result.put("type", "windows");
        } else if (osName.contains("mac")) {
            result.put("type", "macosx");
        } else if (osName.contains("nix") || osName.contains("nux")) {
            result.put("type", "linux");
        } else {
            result.put("type", "unknown");
        }
        return result;
    }

    // ========== Type Utilities ==========

    /**
     * Parses a string as JSON, XML, or returns the string as-is.
     * - If the string looks like JSON (starts with { or [), parse as JSON
     * - If the string looks like XML (starts with <), parse as XML
     * - Otherwise, return the string unchanged
     */
    private Invokable fromString() {
        return args -> {
            if (args.length == 0 || args[0] == null) {
                return null;
            }
            String text = args[0].toString();
            if (text.isEmpty()) {
                return text;
            }
            if (StringUtils.looksLikeJson(text)) {
                try {
                    return Json.of(text).value();
                } catch (Exception e) {
                    logger.warn("fromString JSON parse failed: {}", e.getMessage());
                    return text;
                }
            } else if (StringUtils.isXml(text)) {
                try {
                    return Xml.toXmlDoc(text);
                } catch (Exception e) {
                    logger.warn("fromString XML parse failed: {}", e.getMessage());
                    return text;
                }
            }
            return text;
        };
    }

    /**
     * Returns the Karate type of a value:
     * - 'null' for null
     * - 'boolean' for Boolean
     * - 'number' for Number
     * - 'string' for String
     * - 'bytes' for byte[]
     * - 'list' for List
     * - 'map' for Map
     * - 'xml' for Node
     * - 'function' for JsCallable/Invokable
     * - 'object' for other types
     */
    private Invokable typeOf() {
        return args -> {
            if (args.length == 0 || args[0] == null) {
                return "null";
            }
            Object value = args[0];
            if (value instanceof Boolean) {
                return "boolean";
            } else if (value instanceof Number) {
                return "number";
            } else if (value instanceof String) {
                return "string";
            } else if (value instanceof byte[]) {
                return "bytes";
            } else if (value instanceof List) {
                return "list";
            } else if (value instanceof Map) {
                return "map";
            } else if (value instanceof Node) {
                return "xml";
            } else if (value instanceof JsCallable || value instanceof Invokable) {
                return "function";
            }
            return "object";
        };
    }

    private Invokable toBean() {
        return args -> {
            if (args.length < 2) {
                throw new RuntimeException("toBean() needs two arguments: object and class name");
            }
            Object obj = args[0];
            String className = args[1].toString();
            // Convert to JSON string and deserialize to the target class
            String jsonString = Json.of(obj).toString();
            return Json.fromJson(jsonString, className);
        };
    }

    private Invokable toJava() {
        return args -> {
            logger.warn("karate.toJava() is deprecated and a no-op in V2 - JavaScript arrays work directly with Java");
            if (args.length < 1) {
                return null;
            }
            return args[0]; // no-op, just return the input
        };
    }

    private Invokable toJson() {
        return args -> {
            if (args.length < 1) {
                throw new RuntimeException("toJson() needs at least one argument");
            }
            Object obj = args[0];
            boolean removeNulls = args.length > 1 && Boolean.TRUE.equals(args[1]);
            Object result = Json.of(obj).value();
            if (removeNulls) {
                removeNullValues(result);
            }
            return result;
        };
    }

    @SuppressWarnings("unchecked")
    private void removeNullValues(Object obj) {
        if (obj instanceof Map) {
            Map<String, Object> map = (Map<String, Object>) obj;
            map.entrySet().removeIf(e -> e.getValue() == null);
            map.values().forEach(this::removeNullValues);
        } else if (obj instanceof List) {
            ((List<?>) obj).forEach(this::removeNullValues);
        }
    }

    private Invokable remove() {
        return args -> {
            if (args.length < 2) {
                throw new RuntimeException("remove() needs two arguments: variable name and path");
            }
            String varName = args[0].toString();
            String path = args[1].toString();
            Object var = engine.get(varName);
            if (var instanceof Node && path != null && path.startsWith("/")) {
                // XPath remove on XML
                Document doc = var instanceof Document ? (Document) var : ((Node) var).getOwnerDocument();
                Xml.removeByPath(doc, path);
            } else if (var instanceof Map && path != null) {
                @SuppressWarnings("unchecked")
                Map<String, Object> map = (Map<String, Object>) var;
                map.remove(path);
            }
            return null;
        };
    }

    @SuppressWarnings("unchecked")
    private Invokable log() {
        return args -> {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < args.length; i++) {
                if (i > 0) sb.append(" ");
                Object arg = args[i];
                if (arg instanceof Node) {
                    sb.append(Xml.toString((Node) arg, true));
                } else if (arg instanceof Map || arg instanceof List) {
                    sb.append(StringUtils.formatJson(arg));
                } else {
                    sb.append(arg);
                }
            }
            LogContext.get().log(sb.toString());
            return null;
        };
    }

    private Invokable lowerCase() {
        return args -> {
            if (args.length == 0) {
                throw new RuntimeException("lowerCase() needs one argument");
            }
            Object obj = args[0];
            if (obj instanceof String) {
                return ((String) obj).toLowerCase();
            } else if (obj instanceof Map) {
                // Convert to JSON string, lowercase, parse back
                String json = Json.stringifyStrict(obj).toLowerCase();
                return Json.of(json).value();
            } else if (obj instanceof List) {
                String json = Json.stringifyStrict(obj).toLowerCase();
                return Json.of(json).value();
            } else if (obj instanceof Node) {
                String xml = Xml.toString((Node) obj, false).toLowerCase();
                return Xml.toXmlDoc(xml);
            }
            return obj;
        };
    }

    private Invokable pretty() {
        return args -> {
            if (args.length == 0) {
                throw new RuntimeException("pretty() needs one argument");
            }
            Object obj = args[0];
            if (obj instanceof Map || obj instanceof List) {
                return StringUtils.formatJson(obj);
            } else if (obj instanceof Node) {
                return Xml.toString((Node) obj, true);
            } else {
                return obj != null ? obj.toString() : "null";
            }
        };
    }

    private Invokable prettyXml() {
        return args -> {
            if (args.length == 0) {
                throw new RuntimeException("prettyXml() needs one argument");
            }
            Object obj = args[0];
            if (obj instanceof Node) {
                return Xml.toString((Node) obj, true);
            } else if (obj instanceof String) {
                return Xml.toString(Xml.toXmlDoc((String) obj), true);
            } else {
                throw new RuntimeException("prettyXml() argument must be XML node or string");
            }
        };
    }

    private Invokable xmlPath() {
        return args -> {
            if (args.length < 2) {
                throw new RuntimeException("xmlPath() needs two arguments: xml and path");
            }
            Object xmlObj = args[0];
            String path = args[1].toString();
            Node doc;
            if (xmlObj instanceof Node) {
                doc = (Node) xmlObj;
            } else if (xmlObj instanceof String) {
                doc = Xml.toXmlDoc((String) xmlObj);
            } else {
                throw new RuntimeException("xmlPath() first argument must be XML node or string, but was: " + (xmlObj == null ? "null" : xmlObj.getClass()));
            }
            try {
                return evalXmlPath(doc, path);
            } catch (Exception e) {
                throw new RuntimeException("xmlPath failed for path: " + path + " - " + e.getMessage(), e);
            }
        };
    }

    /**
     * Evaluate an XPath expression on an XML node.
     * Returns the appropriate type: String, Number, Node, or List of nodes.
     */
    public static Object evalXmlPath(Node doc, String path) {
        NodeList nodeList;
        try {
            nodeList = Xml.getNodeListByPath(doc, path);
        } catch (Exception e) {
            // XPath functions like count() don't return nodes
            String strValue = Xml.getTextValueByPath(doc, path);
            if (path.startsWith("count")) {
                try {
                    return Integer.parseInt(strValue);
                } catch (NumberFormatException nfe) {
                    return strValue;
                }
            }
            return strValue;
        }
        int count = nodeList.getLength();
        if (count == 0) {
            return null; // Not present
        }
        if (count == 1) {
            return nodeToValue(nodeList.item(0));
        }
        // Multiple nodes - return a list
        List<Object> list = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            list.add(nodeToValue(nodeList.item(i)));
        }
        return list;
    }

    private static Object nodeToValue(Node node) {
        if (node.getNodeType() == Node.ATTRIBUTE_NODE) {
            return node.getNodeValue();
        }
        if (Xml.getChildElementCount(node) == 0) {
            // Leaf node - return text content
            return node.getTextContent();
        }
        // Return as a new XML document
        return Xml.toNewDocument(node);
    }

    /**
     * Process embedded expressions in XML nodes.
     * Handles #(expr) and ##(optional) patterns in text content and attributes.
     */
    private void processXmlEmbeddedExpressions(Node node) {
        if (node == null) return;
        if (node.getNodeType() == Node.DOCUMENT_NODE) {
            node = node.getFirstChild();
        }
        if (node == null) return;

        // Process attributes
        org.w3c.dom.NamedNodeMap attribs = node.getAttributes();
        if (attribs != null) {
            for (int i = 0; i < attribs.getLength(); i++) {
                org.w3c.dom.Attr attr = (org.w3c.dom.Attr) attribs.item(i);
                String value = attr.getValue();
                if (value != null && value.contains("#(")) {
                    attr.setValue(processEmbeddedString(value));
                }
            }
        }

        // Process child nodes
        List<Node> elementsToRemove = new ArrayList<>();
        List<Node[]> nodesToReplace = new ArrayList<>();
        org.w3c.dom.NodeList children = node.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() == Node.TEXT_NODE) {
                String text = child.getTextContent();
                if (text != null && text.contains("#(")) {
                    String trimmed = text.trim();
                    // Check for ##(optional) pattern
                    if (trimmed.startsWith("##(") && trimmed.endsWith(")")) {
                        String expr = trimmed.substring(3, trimmed.length() - 1);
                        Object result = engine.eval(expr);
                        if (result == null) {
                            // Mark parent element for removal
                            elementsToRemove.add(child.getParentNode());
                        } else if (result instanceof Node) {
                            // Schedule replacement with imported XML node
                            nodesToReplace.add(new Node[]{child, (Node) result});
                        } else {
                            child.setTextContent(result.toString());
                        }
                    } else if (trimmed.startsWith("#(") && trimmed.endsWith(")") && !trimmed.substring(2).contains("#(")) {
                        // Single #(expr) pattern - may need to import XML node
                        String expr = trimmed.substring(2, trimmed.length() - 1);
                        try {
                            Object result = engine.eval(expr);
                            if (result instanceof Node) {
                                // Schedule replacement with imported XML node
                                nodesToReplace.add(new Node[]{child, (Node) result});
                            } else {
                                child.setTextContent(valueToString(result));
                            }
                        } catch (Exception e) {
                            // Keep original if evaluation fails
                        }
                    } else {
                        child.setTextContent(processEmbeddedString(text));
                    }
                }
            } else if (child.getNodeType() == Node.ELEMENT_NODE) {
                processXmlEmbeddedExpressions(child);
            } else if (child.getNodeType() == Node.CDATA_SECTION_NODE) {
                // Process embedded expressions in CDATA, keeping result as text
                String text = child.getTextContent();
                if (text != null && text.contains("#(")) {
                    child.setTextContent(processEmbeddedString(text));
                }
            }
        }

        // Replace text nodes with imported XML nodes
        for (Node[] pair : nodesToReplace) {
            Node textNode = pair[0];
            Node xmlNode = pair[1];
            Node parent = textNode.getParentNode();
            if (parent != null) {
                Document ownerDoc = parent.getOwnerDocument();
                // Get the root element of the XML node to import
                Node toImport = xmlNode;
                if (toImport.getNodeType() == Node.DOCUMENT_NODE) {
                    toImport = ((Document) toImport).getDocumentElement();
                }
                // Import and insert the node
                Node imported = ownerDoc.importNode(toImport, true);
                parent.replaceChild(imported, textNode);
            }
        }

        // Remove elements marked for removal
        for (Node toRemove : elementsToRemove) {
            Node parent = toRemove.getParentNode();
            if (parent != null) {
                parent.removeChild(toRemove);
            }
        }
    }

    /**
     * Convert a value to string, handling XML nodes properly.
     */
    private String valueToString(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof Node) {
            return Xml.toString((Node) value, false);
        }
        return value.toString();
    }

    /**
     * Process a string with embedded expressions like "Hello #(name)!"
     */
    private String processEmbeddedString(String str) {
        StringBuilder result = new StringBuilder();
        int i = 0;
        while (i < str.length()) {
            int hashPos = str.indexOf('#', i);
            if (hashPos == -1 || hashPos >= str.length() - 1) {
                result.append(str.substring(i));
                break;
            }
            result.append(str, i, hashPos);
            char next = str.charAt(hashPos + 1);
            if (next == '(') {
                // #(expr) pattern
                int closePos = findMatchingParen(str, hashPos + 1);
                if (closePos > 0) {
                    String expr = str.substring(hashPos + 2, closePos);
                    try {
                        Object value = engine.eval(expr);
                        result.append(valueToString(value));
                    } catch (Exception e) {
                        // If expression fails (variable not defined), keep original
                        result.append(str, hashPos, closePos + 1);
                    }
                    i = closePos + 1;
                } else {
                    result.append('#');
                    i = hashPos + 1;
                }
            } else if (next == '#' && hashPos + 2 < str.length() && str.charAt(hashPos + 2) == '(') {
                // ##(optional) pattern - handle inline
                int closePos = findMatchingParen(str, hashPos + 2);
                if (closePos > 0) {
                    String expr = str.substring(hashPos + 3, closePos);
                    try {
                        Object value = engine.eval(expr);
                        result.append(valueToString(value));
                    } catch (Exception e) {
                        // If expression fails, keep original
                        result.append(str, hashPos, closePos + 1);
                    }
                    i = closePos + 1;
                } else {
                    result.append("##");
                    i = hashPos + 2;
                }
            } else {
                result.append('#');
                i = hashPos + 1;
            }
        }
        return result.toString();
    }

    private static int findMatchingParen(String str, int openPos) {
        int depth = 1;
        for (int i = openPos + 1; i < str.length(); i++) {
            char c = str.charAt(i);
            if (c == '(') depth++;
            else if (c == ')') {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
    }

    private Invokable setXml() {
        return args -> {
            if (args.length < 2) {
                throw new RuntimeException("setXml() needs at least two arguments: name and xml");
            }
            String name = args[0].toString();
            if (args.length == 2) {
                // Simple form: setXml('name', '<xml/>')
                String xml = args[1].toString();
                engine.put(name, Xml.toXmlDoc(xml));
            } else {
                // Path form: setXml('name', '/path', '<xml/>')
                String path = args[1].toString();
                String xml = args[2].toString();
                Object target = engine.get(name);
                if (target instanceof Node) {
                    Node doc = (Node) target;
                    if (doc.getNodeType() != Node.DOCUMENT_NODE) {
                        doc = doc.getOwnerDocument();
                    }
                    Xml.setByPath((org.w3c.dom.Document) doc, path, Xml.toXmlDoc(xml));
                }
            }
            return null;
        };
    }

    // ========== Process Execution ==========

    /**
     * karate.exec() - Synchronous process execution.
     * Returns stdout as string.
     * Usage:
     *   karate.exec('ls -la')
     *   karate.exec(['ls', '-la'])
     *   karate.exec({ line: 'ls -la', workingDir: '/tmp' })
     */
    @SuppressWarnings("unchecked")
    private Invokable exec() {
        return args -> {
            if (args.length == 0) {
                throw new RuntimeException("exec() needs at least one argument");
            }
            ProcessBuilder builder = ProcessBuilder.create();
            Object arg = args[0];
            if (arg instanceof String) {
                builder.line((String) arg);
            } else if (arg instanceof List) {
                builder.args((List<String>) arg);
            } else if (arg instanceof Map) {
                builder = ProcessBuilder.fromMap((Map<String, Object>) arg);
            } else {
                throw new RuntimeException("exec() argument must be string, array, or object");
            }
            ProcessHandle handle = ProcessHandle.start(builder.build());
            handle.waitSync();
            return handle.getStdOut();
        };
    }

    /**
     * karate.fork() - Asynchronous process execution.
     * Returns ProcessHandle for async control.
     * Usage:
     *   var proc = karate.fork('ping google.com')
     *   var proc = karate.fork({ args: ['node', 'server.js'], listener: fn })
     *   var proc = karate.fork({ args: [...], start: false })  // deferred start
     *   proc.onStdOut(fn).start()
     *   proc.waitSync()
     *   proc.stdOut
     *   proc.exitCode
     *   proc.close()
     */
    @SuppressWarnings("unchecked")
    private Invokable fork() {
        return args -> {
            if (args.length == 0) {
                throw new RuntimeException("fork() needs at least one argument");
            }
            ProcessBuilder builder = ProcessBuilder.create();
            Consumer<String> listener = null;
            Consumer<String> errorListener = null;
            boolean autoStart = true;

            Object arg = args[0];
            if (arg instanceof String) {
                builder.line((String) arg);
            } else if (arg instanceof List) {
                builder.args((List<String>) arg);
            } else if (arg instanceof Map) {
                Map<String, Object> options = (Map<String, Object>) arg;
                builder = ProcessBuilder.fromMap(options);

                // Extract listener function (receives line string directly)
                Object listenerObj = options.get("listener");
                if (listenerObj instanceof JsCallable jsListener) {
                    listener = line -> {
                        try {
                            jsListener.call(null, line);
                        } catch (Exception e) {
                            logger.warn("process listener error: {}", e.getMessage());
                        }
                    };
                }

                // Extract errorListener function (receives line string directly)
                Object errorListenerObj = options.get("errorListener");
                if (errorListenerObj instanceof JsCallable jsErrorListener) {
                    errorListener = line -> {
                        try {
                            jsErrorListener.call(null, line);
                        } catch (Exception e) {
                            logger.warn("process errorListener error: {}", e.getMessage());
                        }
                    };
                }

                // Check start option (default true)
                Object startObj = options.get("start");
                if (startObj instanceof Boolean) {
                    autoStart = (Boolean) startObj;
                }
            } else {
                throw new RuntimeException("fork() argument must be string, array, or object");
            }

            if (listener != null) {
                builder.listener(listener);
            }
            if (errorListener != null) {
                builder.errorListener(errorListener);
            }

            ProcessHandle handle = ProcessHandle.create(builder.build());

            // Wire signal consumer for listen/listenResult integration
            if (signalConsumer != null) {
                handle.setSignalConsumer(signalConsumer);
            }

            if (autoStart) {
                handle.start();
            }
            return handle;
        };
    }

    /**
     * karate.signal() - Signal a result for listen/listenResult.
     * Triggers listenResult in the waiting scenario.
     */
    private Invokable signal() {
        return args -> {
            if (signalConsumer != null && args.length > 0) {
                signalConsumer.accept(args[0]);
            }
            return null;
        };
    }

    @Override
    public Object jsGet(String key) {
        return switch (key) {
            case "abort" -> abort();
            case "append" -> append();
            case "fail" -> fail();
            case "appendTo" -> appendTo();
            case "call" -> call();
            case "callonce" -> callonce();
            case "callSingle" -> callSingle();
            case "configure" -> configure();
            case "doc" -> doc();
            case "env" -> env;
            case "eval" -> eval();
            case "exec" -> exec();
            case "extract" -> extract();
            case "extractAll" -> extractAll();
            case "fork" -> fork();
            case "filter" -> filter();
            case "filterKeys" -> filterKeys();
            case "forEach" -> forEach();
            case "fromString" -> fromString();
            case "get" -> get();
            case "http" -> http();
            case "info" -> getInfo();
            case "jsonPath" -> jsonPath();
            case "keysOf" -> keysOf();
            case "log" -> log();
            case "lowerCase" -> lowerCase();
            case "map" -> map();
            case "mapWithKey" -> mapWithKey();
            case "match" -> karateMatch();
            case "merge" -> merge();
            case "os" -> getOsInfo();
            case "pretty" -> pretty();
            case "prettyXml" -> prettyXml();
            case "read" -> read;
            case "readAsString" -> readAsString();
            case "remove" -> remove();
            case "repeat" -> repeat();
            case "scenario" -> getScenario();
            case "set" -> set();
            case "setup" -> setup();
            case "signal" -> signal();
            case "setupOnce" -> setupOnce();
            case "setXml" -> setXml();
            case "sizeOf" -> sizeOf();
            case "sort" -> sort();
            case "toBean" -> toBean();
            case "toJava" -> toJava();
            case "toJson" -> toJson();
            case "toStringPretty" -> toStringPretty();
            case "typeOf" -> typeOf();
            case "valuesOf" -> valuesOf();
            case "xmlPath" -> xmlPath();
            default -> null;
        };
    }

}
