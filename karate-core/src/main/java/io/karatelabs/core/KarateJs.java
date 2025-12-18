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
import io.karatelabs.gherkin.MatchExpression;
import io.karatelabs.js.Context;
import io.karatelabs.js.Engine;
import io.karatelabs.js.GherkinParser;
import io.karatelabs.js.Invokable;
import io.karatelabs.js.JsCallable;
import io.karatelabs.js.SimpleObject;
import io.karatelabs.markup.Markup;
import io.karatelabs.markup.ResourceResolver;
import io.karatelabs.match.Match;
import io.karatelabs.match.Result;
import io.karatelabs.match.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Node;

import com.jayway.jsonpath.JsonPath;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;

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

    private final Invokable read;

    public KarateJs(Resource root) {
        this(root, new ApacheHttpClient());
    }

    public KarateJs(Resource root, HttpClient client) {
        this.root = root;
        this.client = client;
        http = new HttpRequestBuilder(client);
        this.engine = new Engine();
        engine.setOnConsoleLog(logger::info);
        read = args -> {
            if (args.length == 0) {
                throw new RuntimeException("read() needs at least one argument");
            }
            Resource resource = root.resolve((args[0] + ""));
            return switch (resource.getExtension()) {
                case "json" -> Json.of(resource.getText()).value();
                case "js" -> engine.eval(resource.getText());
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

    private Invokable abort() {
        return args -> {
            if (abortHandler != null) {
                abortHandler.run();
            }
            return null;
        };
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
            Object result = engine.get(args[0] + "");
            if (result == null && args.length > 1) {
                return args[1];
            }
            return result;
        };
    }

    private Invokable set() {
        return args -> {
            if (args.length < 2) {
                throw new RuntimeException("set() needs at least two arguments: name and value");
            }
            engine.put(args[0] + "", args[1]);
            return null;
        };
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

    private Invokable remove() {
        return args -> {
            if (args.length < 2) {
                throw new RuntimeException("remove() needs two arguments: variable name and path");
            }
            String varName = args[0].toString();
            String path = args[1].toString();
            Object var = engine.get(varName);
            if (var instanceof Map && path != null) {
                @SuppressWarnings("unchecked")
                Map<String, Object> map = (Map<String, Object>) var;
                map.remove(path);
            }
            return null;
        };
    }

    @Override
    public Object jsGet(String key) {
        return switch (key) {
            case "abort" -> abort();
            case "append" -> append();
            case "appendTo" -> appendTo();
            case "call" -> call();
            case "doc" -> doc();
            case "eval" -> eval();
            case "filter" -> filter();
            case "filterKeys" -> filterKeys();
            case "forEach" -> forEach();
            case "get" -> get();
            case "http" -> http();
            case "jsonPath" -> jsonPath();
            case "keysOf" -> keysOf();
            case "map" -> map();
            case "mapWithKey" -> mapWithKey();
            case "match" -> karateMatch();
            case "merge" -> merge();
            case "os" -> getOsInfo();
            case "read" -> read;
            case "readAsString" -> readAsString();
            case "remove" -> remove();
            case "repeat" -> repeat();
            case "set" -> set();
            case "setup" -> setup();
            case "setupOnce" -> setupOnce();
            case "sizeOf" -> sizeOf();
            case "sort" -> sort();
            case "toStringPretty" -> toStringPretty();
            case "valuesOf" -> valuesOf();
            default -> null;
        };
    }

}
