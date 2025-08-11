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
package io.karatelabs.markup;

import io.karatelabs.js.Engine;

public class Markup {

    private Markup() {
        // only static methods
    }

    private static KarateTemplateEngine initEngine(Engine engine, TemplateSourceResolver resolver, String contextPath) {
        return new KarateTemplateEngine(engine, new KarateScriptDialect(resolver, contextPath));
    }

    public static KarateTemplateEngine forStrings(Engine je, TemplateSourceResolver resolver) {
        if (resolver == null) {
            resolver = (path, caller) -> {
                throw new RuntimeException("cannot resolve template: " + path + ", caller: " + caller);
            };
        }
        KarateTemplateEngine engine = initEngine(je, resolver, null);
        engine.setTemplateResolver(StringHtmlTemplateResolver.INSTANCE);
        engine.addTemplateResolver(new HtmlTemplateResolver(resolver));
        return engine;
    }

    public static KarateTemplateEngine forResourceResolver(Engine je, TemplateSourceResolver resolver) {
        KarateTemplateEngine engine = initEngine(je, resolver, null);
        engine.setTemplateResolver(new HtmlTemplateResolver(resolver));
        return engine;
    }

    public static KarateTemplateEngine forResourceRoot(Engine je, String root) {
        return forResourceResolver(je, new UrlTemplateSourceResolver(root));
    }

}
