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
import io.karatelabs.io.http.ApacheHttpClient;
import io.karatelabs.io.http.HttpClient;
import io.karatelabs.io.http.HttpRequestBuilder;
import io.karatelabs.js.Engine;
import io.karatelabs.js.Invokable;
import io.karatelabs.js.SimpleObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class KarateJs implements SimpleObject {

    private static final Logger logger = LoggerFactory.getLogger(KarateJs.class);

    public final Engine engine;
    private final HttpClient client;
    private final Resource root;

    public KarateJs(Resource root) {
        this(root, new ApacheHttpClient());
    }

    public KarateJs(Resource root, HttpClient client) {
        this.root = root;
        this.client = client;
        this.engine = new Engine();
        engine.context.setOnConsole(this::onConsole);
        engine.setRootBinding("karate", this);
    }

    void onConsole(String text) {
        logger.info(text);
    }

    private Invokable http() {
        return args -> {
            HttpRequestBuilder http = new HttpRequestBuilder(client);
            if (args.length > 0) {
                http.url(args[0] + "");
            }
            return http;
        };
    }

    private Invokable read() {
        return args -> {
            if (args.length == 0) {
                throw new RuntimeException("read() needs at least one argument");
            }
            Resource resource = root.resolve((args[0] + ""));
            switch (resource.getExtension()) {
                case "json":
                    return Json.of(resource.getText()).value();
                case "js":
                    String js = resource.getText();
                    return engine.eval(js);
                default:
                    return resource.getText();
            }
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

    @Override
    public Object get(String key) {
        switch (key) {
            case "http":
                return http();
            case "read":
                return read();
            case "readAsString":
                return readAsString();
        }
        return null;
    }

    public Object eval(String script) {
        return engine.eval(script);
    }

}
