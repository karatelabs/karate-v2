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

import java.util.function.Consumer;

/**
 * Thread-local log collector for scenario execution.
 * All user-script logging (print, karate.log, HTTP) goes here.
 * The collected log is written to karate-json reports.
 */
public class LogContext {

    private static final ThreadLocal<LogContext> CURRENT = new ThreadLocal<>();

    private static Consumer<String> cascade;

    private final StringBuilder buffer = new StringBuilder();

    // ========== Thread-Local Access ==========

    public static LogContext get() {
        LogContext ctx = CURRENT.get();
        if (ctx == null) {
            ctx = new LogContext();
            CURRENT.set(ctx);
        }
        return ctx;
    }

    public static void set(LogContext ctx) {
        CURRENT.set(ctx);
    }

    public static void clear() {
        CURRENT.remove();
    }

    /**
     * Set a cascade consumer to forward log messages to an external logger (e.g., SLF4J).
     * This is optional and allows test logs to be visible in console/files while
     * still being captured for reports.
     */
    public static void setCascade(Consumer<String> logger) {
        cascade = logger;
    }

    // ========== Logging ==========

    public void log(Object message) {
        String msg = String.valueOf(message);
        buffer.append(msg).append('\n');
        if (cascade != null) {
            cascade.accept(msg);
        }
    }

    public void log(String format, Object... args) {
        String message = format(format, args);
        buffer.append(message).append('\n');
        if (cascade != null) {
            cascade.accept(message);
        }
    }

    // ========== Collect ==========

    /**
     * Get accumulated log and clear buffer (for step/scenario end).
     */
    public String collect() {
        String result = buffer.toString();
        buffer.setLength(0);
        return result;
    }

    /**
     * Get accumulated log without clearing.
     */
    public String peek() {
        return buffer.toString();
    }

    // ========== Format Helper ==========

    /**
     * Simple {} placeholder replacement (no SLF4J dependency).
     */
    static String format(String format, Object... args) {
        if (args == null || args.length == 0) {
            return format;
        }
        StringBuilder sb = new StringBuilder();
        int argIndex = 0;
        int i = 0;
        while (i < format.length()) {
            if (i < format.length() - 1 && format.charAt(i) == '{' && format.charAt(i + 1) == '}') {
                if (argIndex < args.length) {
                    sb.append(args[argIndex++]);
                } else {
                    sb.append("{}");
                }
                i += 2;
            } else {
                sb.append(format.charAt(i));
                i++;
            }
        }
        return sb.toString();
    }
}
