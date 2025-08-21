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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.thymeleaf.IEngineConfiguration;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.TemplateSpec;
import org.thymeleaf.context.IContext;
import org.thymeleaf.context.IEngineContext;
import org.thymeleaf.context.StandardEngineContextFactory;
import org.thymeleaf.dialect.IDialect;
import org.thymeleaf.engine.TemplateData;
import org.thymeleaf.engine.TemplateManager;
import org.thymeleaf.exceptions.TemplateOutputException;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ITemplateResolver;
import org.thymeleaf.util.FastStringWriter;

import java.io.IOException;
import java.io.Writer;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class Markup {

    private static final Logger logger = LoggerFactory.getLogger(Markup.class);

    private final StandardEngineContextFactory standardFactory;
    private final TemplateEngine wrapped;

    Markup(Engine engine, IDialect... dialects) {
        standardFactory = new StandardEngineContextFactory();
        wrapped = new TemplateEngine();
        wrapped.setEngineContextFactory((IEngineConfiguration ec, TemplateData data, Map<String, Object> attrs, IContext context) -> {
            IEngineContext engineContext = standardFactory.createEngineContext(ec, data, attrs, context);
            return new KarateEngineContext(engineContext, engine);
        });
        // the next line is a set which clears and replaces all existing / default
        wrapped.setDialect(new KarateStandardDialect());
        for (IDialect dialect : dialects) {
            wrapped.addDialect(dialect);
        }
    }

    void addTemplateResolver(ITemplateResolver templateResolver) {
        wrapped.addTemplateResolver(templateResolver);
    }

    void setTemplateResolver(ITemplateResolver templateResolver) {
        wrapped.setTemplateResolver(templateResolver);
    }

    String process(String template) {
        return process(template, Collections.emptyMap());
    }

    String process(String template, Map<String, Object> localVars) {
        return process(template, new MarkupEngineContext(localVars));
    }

    String process(String template, IContext context) {
        TemplateSpec templateSpec = new TemplateSpec(template, TemplateMode.HTML);
        Writer stringWriter = new FastStringWriter(100);
        process(templateSpec, context, stringWriter);
        return stringWriter.toString();
    }

    void process(TemplateSpec templateSpec, IContext context, Writer writer) {
        try {
            TemplateManager templateManager = wrapped.getConfiguration().getTemplateManager();
            templateManager.parseAndProcess(templateSpec, context, writer);
            try {
                writer.flush();
            } catch (IOException e) {
                throw new TemplateOutputException("error flushing output writer", templateSpec.getTemplate(), -1, -1, e);
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static Markup initEngine(Engine engine, ResourceResolver resolver, String contextPath) {
        return new Markup(engine, new KarateProcessorDialect(resolver, contextPath));
    }

    public static Markup forStrings(Engine je, ResourceResolver resolver) {
        if (resolver == null) {
            resolver = (path, caller) -> {
                throw new RuntimeException("cannot resolve template: " + path + ", caller: " + caller);
            };
        }
        Markup engine = initEngine(je, resolver, null);
        engine.setTemplateResolver(StringHtmlTemplateResolver.INSTANCE);
        engine.addTemplateResolver(new HtmlTemplateResolver(resolver));
        return engine;
    }

    public static Markup forResourceResolver(Engine je, ResourceResolver resolver) {
        Markup engine = initEngine(je, resolver, null);
        engine.setTemplateResolver(new HtmlTemplateResolver(resolver));
        return engine;
    }

    public static Markup forResourceRoot(Engine je, String root) {
        return forResourceResolver(je, new UrlResourceResolver(root));
    }

    static class MarkupEngineContext implements IContext {

        private final Map<String, Object> vars;

        public MarkupEngineContext(Map<String, Object> vars) {
            if (vars == null) {
                vars = Collections.emptyMap();
            }
            this.vars = vars;
        }

        @Override
        public Locale getLocale() {
            return Locale.US;
        }

        @Override
        public boolean containsVariable(String name) {
            return vars.containsKey(name);
        }

        @Override
        public Set<String> getVariableNames() {
            return vars.keySet();
        }

        @Override
        public Object getVariable(String name) {
            return vars.get(name);
        }

    }

}
