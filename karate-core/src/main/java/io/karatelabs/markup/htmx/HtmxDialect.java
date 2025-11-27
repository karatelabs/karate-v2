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
package io.karatelabs.markup.htmx;

import org.thymeleaf.dialect.AbstractProcessorDialect;
import org.thymeleaf.processor.IProcessor;

import java.util.HashSet;
import java.util.Set;

/**
 * Thymeleaf dialect for HTMX support.
 * Provides processors for ka:get, ka:post, ka:put, ka:patch, ka:delete, ka:vals, ka:target, ka:swap, etc.
 * These are converted to their hx-* equivalents during template processing.
 */
public class HtmxDialect extends AbstractProcessorDialect {

    private final HtmxConfig config;

    /**
     * Create a new HtmxDialect with default configuration.
     */
    public HtmxDialect() {
        this(new HtmxConfig());
    }

    /**
     * Create a new HtmxDialect with the given configuration.
     * @param config the HTMX configuration
     */
    public HtmxDialect(HtmxConfig config) {
        // Priority 3000 - processed after KarateProcessorDialect (2000) and standard dialect (1000)
        super("Htmx", "ka", 3000);
        this.config = config;
    }

    /**
     * Get the configuration for this dialect.
     * @return the HTMX configuration
     */
    public HtmxConfig getConfig() {
        return config;
    }

    @Override
    public Set<IProcessor> getProcessors(String dialectPrefix) {
        Set<IProcessor> processors = new HashSet<>();

        // HTTP method processors (ka:get, ka:post, ka:put, ka:patch, ka:delete)
        processors.add(new HxMethodProcessor(dialectPrefix, "get", config));
        processors.add(new HxMethodProcessor(dialectPrefix, "post", config));
        processors.add(new HxMethodProcessor(dialectPrefix, "put", config));
        processors.add(new HxMethodProcessor(dialectPrefix, "patch", config));
        processors.add(new HxMethodProcessor(dialectPrefix, "delete", config));

        // ka:vals processor
        processors.add(new HxValsProcessor(dialectPrefix));

        // TODO: HxGenericProcessor (ka:target, ka:swap, ka:trigger, etc.)

        return processors;
    }

}
