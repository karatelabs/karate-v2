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
import io.karatelabs.js.Context;
import io.karatelabs.js.Engine;
import io.karatelabs.js.Invokable;
import io.karatelabs.js.SimpleObject;
import io.karatelabs.markup.Markup;
import io.karatelabs.markup.ResourceResolver;
import io.karatelabs.match.Match;
import io.karatelabs.match.Result;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Node;

import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
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
        engine.put("match", match(true));
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

    private Invokable match(boolean keyword) {
        return args -> {
            if (args.length == 0) {
                throw new RuntimeException("match() needs at least one argument");
            }
            return Match.evaluate(args[0], null, (context, result) -> {
                if (keyword && onMatch != null) {
                    onMatch.accept(context, result);
                }
            });
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

    @Override
    public Object jsGet(String key) {
        return switch (key) {
            case "doc" -> doc();
            case "get" -> get();
            case "http" -> http();
            case "match" -> match(false);
            case "read" -> read;
            case "readAsString" -> readAsString();
            case "setup" -> setup();
            case "setupOnce" -> setupOnce();
            case "toStringPretty" -> toStringPretty();
            default -> null;
        };
    }

}
