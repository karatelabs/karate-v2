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

import io.karatelabs.common.Resource;
import io.karatelabs.common.StringUtils;
import io.karatelabs.common.Xml;
import io.karatelabs.http.HttpClient;
import io.karatelabs.http.HttpRequestBuilder;
import io.karatelabs.js.*;
import io.karatelabs.markup.Markup;
import io.karatelabs.markup.ResourceResolver;
import io.karatelabs.match.Result;
import io.karatelabs.output.LogContext;
import org.slf4j.Logger;
import org.w3c.dom.Node;

import java.util.List;
import java.util.Map;
import java.util.function.*;

/**
 * Base class holding shared state and infrastructure for the karate.* API.
 * <p>
 * This abstract class provides:
 * - Core state fields (engine, client, providers, handlers)
 * - Dependency injection via setter methods
 * - Initialization of the JavaScript engine and built-in functions
 * - XML expression processing for embedded #(expr) patterns
 * <p>
 * Subclasses (like {@link KarateJs}) implement the actual API methods
 * that use this infrastructure.
 *
 * @see KarateJs for the main API implementation
 * @see KarateJsUtils for stateless utility methods
 */
abstract class KarateJsBase implements SimpleObject {

    static final Logger logger = LogContext.RUNTIME_LOGGER;

    public final Resource root;
    public final Engine engine;
    public final HttpClient client;
    public final HttpRequestBuilder http;

    ResourceResolver resourceResolver;
    Markup _markup;
    Consumer<String> onDoc;
    BiConsumer<Context, Result> onMatch;
    Function<String, Map<String, Object>> setupProvider;
    Function<String, Map<String, Object>> setupOnceProvider;
    Runnable abortHandler;
    BiFunction<String, Object, Map<String, Object>> callProvider;
    BiFunction<String, Object, Map<String, Object>> callOnceProvider;
    BiFunction<String, Object, Object> callSingleProvider;
    Supplier<Map<String, Object>> infoProvider;
    Supplier<Map<String, Object>> scenarioProvider;
    Supplier<Map<String, Object>> featureProvider;
    Supplier<List<String>> tagsProvider;
    Supplier<Map<String, List<String>>> tagValuesProvider;
    Supplier<Map<String, Object>> scenarioOutlineProvider;
    Consumer<Object> signalConsumer;
    BiConsumer<String, Object> configureHandler;
    Supplier<KarateConfig> configProvider;
    Supplier<Resource> currentResourceProvider;
    Supplier<Map<String, String>> propertiesProvider;
    String env;
    MockHandler mockHandler; // non-null only in mock context
    Supplier<String> outputDirProvider; // for karate.write()
    io.karatelabs.http.HttpRequest prevRequest; // tracks previous HTTP request
    KarateJsLog logFacade; // lazy-initialized

    KarateJsBase(Resource root, HttpClient client) {
        this.root = root;
        this.client = client;
        http = new HttpRequestBuilder(client);
        this.engine = new Engine();
        engine.setOnConsoleLog(s -> LogContext.get().log(s));
        // TODO: implement whitelisting for safety - currently allows access to all Java classes
        engine.setExternalBridge(new io.karatelabs.js.ExternalBridge() {
        });
        // Note: engine.put() for karate, read, match is done in KarateJs constructor
    }

    public void setOnDoc(Consumer<String> onDoc) {
        this.onDoc = onDoc;
    }

    public void setResourceResolver(ResourceResolver resourceResolver) {
        this.resourceResolver = resourceResolver;
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

    // ========== Scenario Lifecycle Methods ==========
    // These methods use providers/handlers but not the JS engine directly.

    Map<String, Object> getInfo() {
        if (infoProvider != null) {
            return infoProvider.get();
        }
        return Map.of();
    }

    Map<String, Object> getScenario() {
        if (scenarioProvider != null) {
            return scenarioProvider.get();
        }
        return Map.of();
    }

    Map<String, Object> getFeature() {
        if (featureProvider != null) {
            return featureProvider.get();
        }
        return Map.of();
    }

    List<String> getTags() {
        if (tagsProvider != null) {
            return tagsProvider.get();
        }
        return List.of();
    }

    Map<String, List<String>> getTagValues() {
        if (tagValuesProvider != null) {
            return tagValuesProvider.get();
        }
        return Map.of();
    }

    Map<String, Object> getScenarioOutline() {
        if (scenarioOutlineProvider != null) {
            return scenarioOutlineProvider.get();
        }
        return null;  // V1 returns null when not in an outline
    }

    /**
     * Returns the config settings (from configure keyword) as a KarateConfig.
     * Returns a copy to prevent mutation from JavaScript.
     */
    KarateConfig getConfig() {
        if (configProvider != null) {
            return configProvider.get().copy();
        }
        return new KarateConfig();
    }

    Invokable abort() {
        return args -> {
            if (abortHandler != null) {
                abortHandler.run();
            }
            return null;
        };
    }

    Invokable fail() {
        return args -> {
            String message = args.length > 0 && args[0] != null ? args[0].toString() : "karate.fail() called";
            throw new RuntimeException(message);
        };
    }

    Invokable setup() {
        return args -> {
            if (setupProvider == null) {
                throw new RuntimeException("karate.setup() is not available in this context");
            }
            String name = args.length > 0 && args[0] != null ? args[0].toString() : null;
            return setupProvider.apply(name);
        };
    }

    Invokable setupOnce() {
        return args -> {
            if (setupOnceProvider == null) {
                throw new RuntimeException("karate.setupOnce() is not available in this context");
            }
            String name = args.length > 0 && args[0] != null ? args[0].toString() : null;
            return setupOnceProvider.apply(name);
        };
    }

    /**
     * karate.callonce() - Execute a feature file once per feature and cache the result.
     */
    Invokable callonce() {
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
     */
    Invokable callSingle() {
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
     */
    Invokable configure() {
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

    /**
     * Returns system properties available via karate.properties['key'].
     */
    Map<String, String> getProperties() {
        if (propertiesProvider != null) {
            return propertiesProvider.get();
        }
        Map<String, String> props = new java.util.LinkedHashMap<>();
        System.getProperties().forEach((k, v) -> props.put(k.toString(), v.toString()));
        return props;
    }

    @SuppressWarnings("unchecked")
    Invokable log() {
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
     */
    Invokable embed() {
        return args -> {
            if (args.length < 1) {
                throw new RuntimeException("embed() needs at least one argument: data");
            }
            Object dataArg = args[0];
            String mimeType = args.length > 1 && args[1] != null
                    ? args[1].toString() : KarateJsUtils.detectMimeType(dataArg);
            String name = args.length > 2 ? args[2].toString() : null;

            byte[] data = KarateJsUtils.convertToBytes(dataArg);
            LogContext.get().embed(data, mimeType, name);
            return null;
        };
    }

    /**
     * karate.signal() - Signal a result for listen/listenResult.
     */
    Invokable signal() {
        return args -> {
            if (signalConsumer != null && args.length > 0) {
                signalConsumer.accept(args[0]);
            }
            return null;
        };
    }

    KarateJsLog getLogger() {
        if (logFacade == null) {
            logFacade = new KarateJsLog();
        }
        return logFacade;
    }

    /**
     * karate.prevRequest - Returns the previous HTTP request made in this scenario.
     */
    Map<String, Object> getPrevRequest() {
        if (prevRequest == null) {
            return null;
        }
        Map<String, Object> map = new java.util.LinkedHashMap<>();
        map.put("method", prevRequest.getMethod());
        map.put("url", prevRequest.getUrlAndPath());
        map.put("headers", prevRequest.getHeaders());
        map.put("body", prevRequest.getBodyConverted());
        return map;
    }

}
