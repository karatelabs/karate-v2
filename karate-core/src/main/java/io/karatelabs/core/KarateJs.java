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
import io.karatelabs.http.ApacheHttpClient;
import io.karatelabs.http.HttpClient;
import io.karatelabs.http.HttpRequest;
import io.karatelabs.http.HttpRequestBuilder;
import io.karatelabs.http.HttpResponse;
import io.karatelabs.gherkin.Feature;
import io.karatelabs.gherkin.MatchExpression;
import io.karatelabs.js.Context;
import io.karatelabs.js.Engine;
import io.karatelabs.js.GherkinParser;
import io.karatelabs.js.Invokable;
import io.karatelabs.js.JsCallable;
import io.karatelabs.js.SimpleObject;
import io.karatelabs.output.LogContext;
import io.karatelabs.output.LogLevel;
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

import java.io.File;
import java.io.InputStream;
import java.net.Socket;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
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
    private Supplier<Map<String, Object>> featureProvider;
    private Supplier<List<String>> tagsProvider;
    private Supplier<Map<String, List<String>>> tagValuesProvider;
    private Supplier<Map<String, Object>> scenarioOutlineProvider;
    private Consumer<Object> signalConsumer;
    private BiConsumer<String, Object> configureHandler;
    private Supplier<KarateConfig> configProvider;
    private Supplier<Resource> currentResourceProvider;
    private Supplier<Map<String, String>> propertiesProvider;
    private String env;
    private MockHandler mockHandler; // non-null only in mock context
    private Supplier<String> outputDirProvider; // for karate.write()
    private io.karatelabs.http.HttpRequest prevRequest; // tracks previous HTTP request
    private LogFacade logFacade; // lazy-initialized

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

    public void setFeatureProvider(Supplier<Map<String, Object>> provider) {
        this.featureProvider = provider;
    }

    public void setTagsProvider(Supplier<List<String>> provider) {
        this.tagsProvider = provider;
    }

    public void setTagValuesProvider(Supplier<Map<String, List<String>>> provider) {
        this.tagValuesProvider = provider;
    }

    public void setScenarioOutlineProvider(Supplier<Map<String, Object>> provider) {
        this.scenarioOutlineProvider = provider;
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

    public void setConfigProvider(Supplier<KarateConfig> provider) {
        this.configProvider = provider;
    }

    public void setCurrentResourceProvider(Supplier<Resource> provider) {
        this.currentResourceProvider = provider;
    }

    public void setPropertiesProvider(Supplier<Map<String, String>> provider) {
        this.propertiesProvider = provider;
    }

    public void setMockHandler(MockHandler handler) {
        this.mockHandler = handler;
    }

    public void setOutputDirProvider(Supplier<String> provider) {
        this.outputDirProvider = provider;
    }

    /**
     * Track the previous HTTP request for karate.prevRequest.
     * Called by HttpRequestBuilder after successful HTTP calls.
     */
    public void setPrevRequest(io.karatelabs.http.HttpRequest request) {
        this.prevRequest = request;
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

    private Map<String, Object> getFeature() {
        if (featureProvider != null) {
            return featureProvider.get();
        }
        return Map.of();
    }

    private List<String> getTags() {
        if (tagsProvider != null) {
            return tagsProvider.get();
        }
        return List.of();
    }

    private Map<String, List<String>> getTagValues() {
        if (tagValuesProvider != null) {
            return tagValuesProvider.get();
        }
        return Map.of();
    }

    private Map<String, Object> getScenarioOutline() {
        if (scenarioOutlineProvider != null) {
            return scenarioOutlineProvider.get();
        }
        return null;  // V1 returns null when not in an outline
    }

    /**
     * Returns the config settings (from configure keyword) as a KarateConfig.
     * Returns a copy to prevent mutation from JavaScript.
     */
    private KarateConfig getConfig() {
        if (configProvider != null) {
            // Return a copy to prevent mutation
            return configProvider.get().copy();
        }
        return new KarateConfig();
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

    /**
     * Read a file as raw bytes. Useful for binary content handling.
     * Usage: karate.readAsBytes('path/to/file')
     */
    private Invokable readAsBytes() {
        return args -> {
            if (args.length == 0) {
                throw new RuntimeException("readAsBytes() needs at least one argument");
            }
            String path = args[0] + "";
            // Support currentResource-relative paths via provider
            Resource resource;
            if (currentResourceProvider != null && currentResourceProvider.get() != null) {
                resource = currentResourceProvider.get().resolve(path);
            } else {
                resource = root.resolve(path);
            }
            try (java.io.InputStream is = resource.getStream()) {
                return is.readAllBytes();
            } catch (java.io.IOException e) {
                throw new RuntimeException("Failed to read bytes from: " + path, e);
            }
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

    /**
     * Returns system properties available via karate.properties['key'].
     * If a propertiesProvider is set (from Suite), uses that.
     * Otherwise falls back to JVM System.getProperties().
     */
    private Map<String, String> getProperties() {
        if (propertiesProvider != null) {
            return propertiesProvider.get();
        }
        // Fallback to JVM system properties
        Map<String, String> props = new HashMap<>();
        System.getProperties().forEach((k, v) -> props.put(k.toString(), v.toString()));
        return props;
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

    private Invokable toJava() {
        return args -> {
            logger.warn("karate.toJava() is deprecated and a no-op in V2 - JavaScript arrays work directly with Java");
            if (args.length < 1) {
                return null;
            }
            return args[0]; // no-op, just return the input
        };
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

    /**
     * Embed content in the report. Auto-detects MIME type if not provided.
     * Usage: karate.embed(data), karate.embed(data, mimeType), karate.embed(data, mimeType, name)
     */
    private Invokable embed() {
        return args -> {
            if (args.length < 1) {
                throw new RuntimeException("embed() needs at least one argument: data");
            }
            Object dataArg = args[0];
            String mimeType = args.length > 1 && args[1] != null ? args[1].toString() : detectMimeType(dataArg);
            String name = args.length > 2 ? args[2].toString() : null;

            byte[] data = convertToBytes(dataArg);
            LogContext.get().embed(data, mimeType, name);
            return null;
        };
    }

    /**
     * Auto-detect MIME type from data object (like v1's ResourceType.fromObject).
     */
    private String detectMimeType(Object obj) {
        if (obj instanceof Map || obj instanceof List) {
            return "application/json";
        } else if (obj instanceof Node) {
            return "application/xml";
        } else if (obj instanceof byte[]) {
            return "application/octet-stream";
        } else {
            return "text/plain";
        }
    }

    /**
     * Convert various data types to bytes for embedding.
     */
    private byte[] convertToBytes(Object obj) {
        if (obj == null) {
            return new byte[0];
        }
        if (obj instanceof byte[]) {
            return (byte[]) obj;
        }
        if (obj instanceof String str) {
            return str.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        }
        if (obj instanceof List<?> list) {
            // Check if it's a list of numbers (byte array representation)
            if (!list.isEmpty() && list.get(0) instanceof Number) {
                byte[] bytes = new byte[list.size()];
                for (int i = 0; i < list.size(); i++) {
                    bytes[i] = ((Number) list.get(i)).byteValue();
                }
                return bytes;
            }
            // Otherwise serialize as JSON
            return StringUtils.formatJson(list).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        }
        if (obj instanceof Map) {
            return StringUtils.formatJson(obj).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        }
        if (obj instanceof Node) {
            return Xml.toString((Node) obj, true).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        }
        // Fallback: convert to string
        return obj.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
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

    /**
     * karate.start() - Start a mock server from a feature file.
     * Usage:
     * <pre>
     * var server = karate.start('api.feature');
     * var server = karate.start({ mock: 'api.feature', port: 8080 });
     * var server = karate.start({ mock: 'api.feature', port: 8443, ssl: true });
     * </pre>
     */
    @SuppressWarnings("unchecked")
    private Invokable start() {
        return args -> {
            if (args.length == 0) {
                throw new RuntimeException("start() needs at least one argument: feature path or config map");
            }
            Object arg = args[0];
            MockServer.Builder builder;

            if (arg instanceof String path) {
                // Simple path: karate.start('api.feature')
                builder = MockServer.feature(root.resolve(path));
            } else if (arg instanceof Map) {
                // Config map: karate.start({ mock: 'api.feature', port: 8080 })
                Map<String, Object> config = (Map<String, Object>) arg;
                String mockPath = (String) config.get("mock");
                if (mockPath == null) {
                    throw new RuntimeException("start() config requires 'mock' key with feature path");
                }
                builder = MockServer.feature(root.resolve(mockPath));

                if (config.containsKey("port")) {
                    builder.port(((Number) config.get("port")).intValue());
                }
                if (config.containsKey("ssl")) {
                    builder.ssl(Boolean.TRUE.equals(config.get("ssl")));
                }
                if (config.containsKey("cert")) {
                    builder.certPath((String) config.get("cert"));
                }
                if (config.containsKey("key")) {
                    builder.keyPath((String) config.get("key"));
                }
                if (config.containsKey("arg")) {
                    builder.arg((Map<String, Object>) config.get("arg"));
                }
                if (config.containsKey("pathPrefix")) {
                    builder.pathPrefix((String) config.get("pathPrefix"));
                }
            } else {
                throw new RuntimeException("start() argument must be a string path or config map");
            }

            return builder.start();
        };
    }

    /**
     * karate.proceed() - Forward the current request to a target URL (proxy mode).
     * Can only be used within a mock scenario.
     * Usage:
     * <pre>
     * // Forward to specific target
     * var response = karate.proceed('http://backend:8080');
     *
     * // Forward using Host header from request
     * var response = karate.proceed();
     * </pre>
     */
    private Invokable proceed() {
        return args -> {
            if (mockHandler == null) {
                throw new RuntimeException("proceed() can only be called within a mock scenario");
            }
            HttpRequest currentRequest = mockHandler.getCurrentRequest();

            String targetUrl;
            if (args.length > 0 && args[0] != null) {
                targetUrl = args[0].toString();
            } else {
                // Use Host header from request
                String host = currentRequest.getHeader("Host");
                if (host == null) {
                    throw new RuntimeException("proceed() needs a target URL or Host header in request");
                }
                targetUrl = "http://" + host;
            }

            // Build request manually to avoid header conflicts
            HttpRequestBuilder builder = new HttpRequestBuilder(client);
            builder.url(targetUrl);
            builder.path(currentRequest.getPath());
            builder.method(currentRequest.getMethod());

            // Copy headers except those that will be auto-set
            if (currentRequest.getHeaders() != null) {
                currentRequest.getHeaders().forEach((name, values) -> {
                    String lowerName = name.toLowerCase();
                    // Skip headers that are auto-managed
                    if (!lowerName.equals("content-length") && !lowerName.equals("host")
                            && !lowerName.equals("transfer-encoding")) {
                        builder.header(name, values);
                    }
                });
            }

            // Set body (this will set Content-Length appropriately)
            Object body = currentRequest.getBodyConverted();
            if (body != null) {
                builder.body(body);
            }

            // Execute and return the response
            HttpResponse response = builder.invoke();
            return response;
        };
    }

    // ========== Pending Methods Implementation ==========

    /**
     * karate.logger - Log facade with debug/info/warn/error methods.
     * Uses LogContext to capture logs in reports and optionally cascade to SLF4J.
     */
    private LogFacade getLogger() {
        if (logFacade == null) {
            logFacade = new LogFacade();
        }
        return logFacade;
    }

    /**
     * karate.prevRequest - Returns the previous HTTP request made in this scenario.
     * Returns a map with: method, url, headers, body
     */
    private Map<String, Object> getPrevRequest() {
        if (prevRequest == null) {
            return null;
        }
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("method", prevRequest.getMethod());
        map.put("url", prevRequest.getUrlAndPath());
        map.put("headers", prevRequest.getHeaders());
        map.put("body", prevRequest.getBodyConverted());
        return map;
    }

    /**
     * karate.request - Returns the current mock request (only in mock context).
     * Returns the body content of the current request.
     */
    private Object getRequest() {
        if (mockHandler == null) {
            logger.warn("karate.request is only available in mock context");
            return null;
        }
        io.karatelabs.http.HttpRequest request = mockHandler.getCurrentRequest();
        return request != null ? request.getBodyConverted() : null;
    }

    /**
     * karate.response - Returns the current response variable (only in mock context).
     * Used by mocks to get/set the response to return.
     */
    private Object getResponse() {
        if (mockHandler == null) {
            logger.warn("karate.response is only available in mock context");
            return null;
        }
        // In mock context, 'response' is a variable in the engine
        return engine.get("response");
    }

    /**
     * karate.readAsStream(path) - Read file as InputStream.
     * Useful for streaming large files without loading into memory.
     */
    private Invokable readAsStream() {
        return args -> {
            if (args.length == 0) {
                throw new RuntimeException("readAsStream() needs at least one argument");
            }
            String path = args[0] + "";
            Resource resource;
            if (currentResourceProvider != null && currentResourceProvider.get() != null) {
                resource = currentResourceProvider.get().resolve(path);
            } else {
                resource = root.resolve(path);
            }
            try {
                return resource.getStream();
            } catch (Exception e) {
                throw new RuntimeException("Failed to open stream for: " + path, e);
            }
        };
    }

    /**
     * karate.render(template) - Render HTML template (similar to doc).
     * Returns the rendered HTML string without sending to doc consumer.
     */
    @SuppressWarnings("unchecked")
    private Invokable render() {
        return args -> {
            if (args.length == 0) {
                throw new RuntimeException("render() needs at least one argument");
            }
            String readPath;
            Map<String, Object> vars = null;
            if (args[0] instanceof Map) {
                Map<String, Object> map = (Map<String, Object>) args[0];
                readPath = (String) map.get("read");
            } else if (args[0] == null) {
                throw new RuntimeException("render() read arg should not be null");
            } else {
                readPath = args[0] + "";
            }
            if (args.length > 1 && args[1] instanceof Map) {
                vars = (Map<String, Object>) args[1];
            }
            return markup().processPath(readPath, vars);
        };
    }

    /**
     * karate.stop(port) - Debugging breakpoint that pauses test execution.
     * Opens a server socket and waits for a connection (e.g., curl or browser).
     * Prints instructions to console. NEVER forget to remove after debugging!
     */
    private Invokable stop() {
        return args -> {
            int port = 0; // 0 means pick any available port
            if (args.length > 0 && args[0] != null) {
                if (args[0] instanceof Number) {
                    port = ((Number) args[0]).intValue();
                } else {
                    port = Integer.parseInt(args[0].toString());
                }
            }
            try (java.net.ServerSocket serverSocket = new java.net.ServerSocket(port, 1,
                    java.net.InetAddress.getByName("127.0.0.1"))) {
                int actualPort = serverSocket.getLocalPort();
                System.out.println("*** waiting for socket, type the command below:\ncurl http://localhost:"
                        + actualPort + "\nin a new terminal (or open the URL in a web-browser) to proceed ...");
                // Block until a connection is received
                try (Socket clientSocket = serverSocket.accept()) {
                    // Send a simple HTTP response
                    java.io.OutputStream out = clientSocket.getOutputStream();
                    out.write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nOK".getBytes());
                    out.flush();
                }
                logger.info("*** exited socket wait successfully");
                return true;
            } catch (Exception e) {
                throw new RuntimeException("stop() failed: " + e.getMessage(), e);
            }
        };
    }

    /**
     * karate.toAbsolutePath(path) - Convert a relative path to absolute.
     * Resolves relative to the current feature file's directory.
     */
    private Invokable toAbsolutePath() {
        return args -> {
            if (args.length == 0) {
                throw new RuntimeException("toAbsolutePath() needs a path argument");
            }
            String path = args[0] + "";
            Resource resource;
            if (currentResourceProvider != null && currentResourceProvider.get() != null) {
                resource = currentResourceProvider.get().resolve(path);
            } else {
                resource = root.resolve(path);
            }
            if (resource.isFile() && resource.getPath() != null) {
                return resource.getPath().toAbsolutePath().toString();
            }
            // For classpath resources, return the prefixed path
            return resource.getPrefixedPath();
        };
    }

    /**
     * karate.waitForHttp(url) - Wait for an HTTP endpoint to become available.
     * Polls until a successful response (2xx or 3xx) is received.
     */
    private Invokable waitForHttp() {
        return args -> {
            if (args.length == 0) {
                throw new RuntimeException("waitForHttp() needs a URL argument");
            }
            String url = args[0] + "";
            int timeoutMs = 30000;
            int pollMs = 250;
            if (args.length > 1 && args[1] instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> options = (Map<String, Object>) args[1];
                if (options.containsKey("timeout")) {
                    timeoutMs = ((Number) options.get("timeout")).intValue();
                }
                if (options.containsKey("interval")) {
                    pollMs = ((Number) options.get("interval")).intValue();
                }
            }
            long deadline = System.currentTimeMillis() + timeoutMs;
            java.net.http.HttpClient httpClient = java.net.http.HttpClient.newBuilder()
                    .connectTimeout(Duration.ofMillis(pollMs))
                    .build();
            while (System.currentTimeMillis() < deadline) {
                try {
                    java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                            .uri(URI.create(url))
                            .timeout(Duration.ofMillis(pollMs))
                            .GET()
                            .build();
                    var response = httpClient.send(request, java.net.http.HttpResponse.BodyHandlers.discarding());
                    int status = response.statusCode();
                    if (status >= 200 && status < 400) {
                        return true;
                    }
                } catch (Exception e) {
                    // Connection failed, continue polling
                }
                try {
                    Thread.sleep(pollMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            throw new RuntimeException("HTTP endpoint not available: " + url);
        };
    }

    /**
     * karate.waitForPort(host, port) - Wait for a TCP port to become available.
     * Polls until a TCP connection can be established.
     */
    private Invokable waitForPort() {
        return args -> {
            if (args.length < 2) {
                throw new RuntimeException("waitForPort() needs host and port arguments");
            }
            String host = args[0] + "";
            int port;
            if (args[1] instanceof Number) {
                port = ((Number) args[1]).intValue();
            } else {
                port = Integer.parseInt(args[1].toString());
            }
            int timeoutMs = 30000;
            int pollMs = 250;
            if (args.length > 2 && args[2] instanceof Number) {
                timeoutMs = ((Number) args[2]).intValue();
            }
            if (args.length > 3 && args[3] instanceof Number) {
                pollMs = ((Number) args[3]).intValue();
            }
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (System.currentTimeMillis() < deadline) {
                try (Socket socket = new Socket(host, port)) {
                    // Port is open, success
                    return true;
                } catch (Exception e) {
                    // Port not available, continue polling
                }
                try {
                    Thread.sleep(pollMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            throw new RuntimeException("Port " + host + ":" + port + " not available within timeout");
        };
    }

    /**
     * karate.write(value, path) - Write content to a file.
     * Path is relative to the output directory (e.g., target/karate-reports).
     */
    private Invokable write() {
        return args -> {
            if (args.length < 2) {
                throw new RuntimeException("write() needs value and path arguments");
            }
            Object value = args[0];
            String path = args[1] + "";

            // Get output directory
            String outputDir;
            if (outputDirProvider != null) {
                outputDir = outputDirProvider.get();
            } else {
                outputDir = "target";  // Default fallback
            }

            // Create the full path
            File file = new File(outputDir, path);

            // Ensure parent directories exist
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }

            // Convert value to bytes
            byte[] bytes = convertToBytes(value);

            // Write to file
            try {
                Files.write(file.toPath(), bytes);
                logger.debug("wrote {} bytes to: {}", bytes.length, file.getAbsolutePath());
                return file;
            } catch (Exception e) {
                throw new RuntimeException("Failed to write file: " + file.getAbsolutePath(), e);
            }
        };
    }

    /**
     * LogFacade - Provides karate.logger.trace/debug/info/warn/error methods.
     * Implements SimpleObject for clean JS interop: karate.logger.debug('msg')
     * Logs go to LogContext with level filtering for report capture.
     */
    public static class LogFacade implements SimpleObject {

        @Override
        public Object jsGet(String key) {
            return switch (key) {
                case "trace" -> logMethod(LogLevel.TRACE);
                case "debug" -> logMethod(LogLevel.DEBUG);
                case "info" -> logMethod(LogLevel.INFO);
                case "warn" -> logMethod(LogLevel.WARN);
                case "error" -> logMethod(LogLevel.ERROR);
                default -> null;
            };
        }

        private Invokable logMethod(LogLevel level) {
            return args -> {
                // Check if level is enabled before formatting
                if (!level.isEnabled(LogContext.getLogLevel())) {
                    return null; // Filtered
                }
                StringBuilder sb = new StringBuilder();
                // Add level prefix for TRACE/DEBUG/WARN/ERROR (distinguish from INFO)
                if (level != LogLevel.INFO) {
                    sb.append("[").append(level).append("] ");
                }
                for (int i = 0; i < args.length; i++) {
                    if (i > 0) sb.append(" ");
                    Object val = args[i];
                    if (val instanceof Node) {
                        sb.append(Xml.toString((Node) val, true));
                    } else if (val instanceof Map || val instanceof List) {
                        sb.append(StringUtils.formatJson(val));
                    } else {
                        sb.append(val);
                    }
                }
                LogContext.get().log(level, sb.toString());
                return null;
            };
        }
    }

    @Override
    public Object jsGet(String key) {
        return switch (key) {
            // Stateless utility methods (KarateJsApi)
            case "append" -> KarateJsApi.append();
            case "appendTo" -> KarateJsApi.appendTo();
            case "distinct" -> KarateJsApi.distinct();
            case "extract" -> KarateJsApi.extract();
            case "extractAll" -> KarateJsApi.extractAll();
            case "filter" -> KarateJsApi.filter();
            case "filterKeys" -> KarateJsApi.filterKeys();
            case "forEach" -> KarateJsApi.forEach();
            case "fromJson" -> KarateJsApi.fromJson();
            case "jsonPath" -> KarateJsApi.jsonPath();
            case "keysOf" -> KarateJsApi.keysOf();
            case "lowerCase" -> KarateJsApi.lowerCase();
            case "map" -> KarateJsApi.map();
            case "mapWithKey" -> KarateJsApi.mapWithKey();
            case "merge" -> KarateJsApi.merge();
            case "pause" -> KarateJsApi.pause();
            case "pretty" -> KarateJsApi.pretty();
            case "prettyXml" -> KarateJsApi.prettyXml();
            case "range" -> KarateJsApi.range();
            case "repeat" -> KarateJsApi.repeat();
            case "sizeOf" -> KarateJsApi.sizeOf();
            case "sort" -> KarateJsApi.sort();
            case "toBean" -> KarateJsApi.toBean();
            case "toBytes" -> KarateJsApi.toBytes();
            case "toCsv" -> KarateJsApi.toCsv();
            case "toJson" -> KarateJsApi.toJson();
            case "toString" -> KarateJsApi.toStringValue();
            case "typeOf" -> KarateJsApi.typeOf();
            case "urlDecode" -> KarateJsApi.urlDecode();
            case "urlEncode" -> KarateJsApi.urlEncode();
            case "valuesOf" -> KarateJsApi.valuesOf();
            // Stateful methods that need engine/providers
            case "abort" -> abort();
            case "call" -> call();
            case "callonce" -> callonce();
            case "callSingle" -> callSingle();
            case "config" -> getConfig();
            case "configure" -> configure();
            case "doc" -> doc();
            case "embed" -> embed();
            case "env" -> env;
            case "eval" -> eval();
            case "exec" -> exec();
            case "fail" -> fail();
            case "feature" -> getFeature();
            case "fork" -> fork();
            case "fromString" -> fromString();
            case "get" -> get();
            case "http" -> http();
            case "info" -> getInfo();
            case "log" -> log();
            case "logger" -> getLogger();
            case "match" -> karateMatch();
            case "os" -> getOsInfo();
            case "prevRequest" -> getPrevRequest();
            case "proceed" -> proceed();
            case "properties" -> getProperties();
            case "read" -> read;
            case "readAsBytes" -> readAsBytes();
            case "readAsStream" -> readAsStream();
            case "readAsString" -> readAsString();
            case "remove" -> remove();
            case "render" -> render();
            case "request" -> getRequest();
            case "response" -> getResponse();
            case "scenario" -> getScenario();
            case "scenarioOutline" -> getScenarioOutline();
            case "set" -> set();
            case "setup" -> setup();
            case "setupOnce" -> setupOnce();
            case "setXml" -> setXml();
            case "signal" -> signal();
            case "start" -> start();
            case "stop" -> stop();
            case "tags" -> getTags();
            case "tagValues" -> getTagValues();
            case "toAbsolutePath" -> toAbsolutePath();
            case "toJava" -> toJava();
            case "toStringPretty" -> toStringPretty();
            case "waitForHttp" -> waitForHttp();
            case "waitForPort" -> waitForPort();
            case "write" -> write();
            case "xmlPath" -> xmlPath();
            default -> null;
        };
    }

}
