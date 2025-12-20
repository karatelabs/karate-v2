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
package io.karatelabs.log;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Consumer;

/**
 * SLF4J cascade for forwarding test output to a dedicated log category.
 * <p>
 * Usage:
 * <pre>
 * LogContext.setCascade(Slf4jCascade.create());
 * </pre>
 * <p>
 * This forwards all test logs (print, karate.log, HTTP) to the
 * "karate.run" SLF4J category at INFO level.
 * <p>
 * Configure in your logback.xml:
 * <pre>
 * &lt;logger name="karate.run" level="INFO"&gt;
 *     &lt;appender-ref ref="FILE" /&gt;
 * &lt;/logger&gt;
 * </pre>
 */
public final class Slf4jCascade {

    /**
     * Default SLF4J category for test logs.
     */
    public static final String DEFAULT_CATEGORY = "karate.run";

    private Slf4jCascade() {
    }

    /**
     * Create a cascade consumer using the default category "karate.run" at INFO level.
     */
    public static Consumer<String> create() {
        return create(DEFAULT_CATEGORY);
    }

    /**
     * Create a cascade consumer using a custom category name at INFO level.
     */
    public static Consumer<String> create(String categoryName) {
        Logger logger = LoggerFactory.getLogger(categoryName);
        return logger::info;
    }

    /**
     * Create a cascade consumer with custom category and log level.
     */
    public static Consumer<String> create(String categoryName, LogLevel level) {
        Logger logger = LoggerFactory.getLogger(categoryName);
        return switch (level) {
            case TRACE -> logger::trace;
            case DEBUG -> logger::debug;
            case INFO -> logger::info;
            case WARN -> logger::warn;
            case ERROR -> logger::error;
        };
    }

}
