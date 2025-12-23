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

import io.karatelabs.core.ScenarioResult;
import io.karatelabs.core.StepResult;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

/**
 * Thread-local log collector for scenario execution.
 * All user-script logging (print, karate.log, HTTP) goes here.
 * Also collects embeds (HTML from doc, images, etc.) for reports.
 * The collected log/embeds are written to karate-json reports.
 */
public class LogContext {

    private static final ThreadLocal<LogContext> CURRENT = new ThreadLocal<>();

    private static BiConsumer<LogLevel, String> cascade;
    private static LogLevel threshold = LogLevel.INFO;

    private final StringBuilder buffer = new StringBuilder();
    private List<StepResult.Embed> embeds;
    private List<ScenarioResult> nestedResults;

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
     * The consumer receives (LogLevel, message) for level-aware forwarding.
     */
    public static void setCascade(BiConsumer<LogLevel, String> logger) {
        cascade = logger;
    }

    /**
     * Set the minimum log level for report capture.
     * Logs below this level will be filtered out.
     */
    public static void setLogLevel(LogLevel level) {
        threshold = level;
    }

    /**
     * Get the current log level threshold.
     */
    public static LogLevel getLogLevel() {
        return threshold;
    }

    // ========== Logging ==========

    /**
     * Log a message at the specified level.
     * Message is filtered if level is below threshold.
     */
    public void log(LogLevel level, String message) {
        if (!level.isEnabled(threshold)) {
            return; // Filtered
        }
        buffer.append(message).append('\n');
        if (cascade != null) {
            cascade.accept(level, message);
        }
    }

    /**
     * Log a message at the specified level with format arguments.
     */
    public void log(LogLevel level, String format, Object... args) {
        if (!level.isEnabled(threshold)) {
            return; // Filtered
        }
        String message = format(format, args);
        buffer.append(message).append('\n');
        if (cascade != null) {
            cascade.accept(level, message);
        }
    }

    /**
     * Log a message at INFO level.
     * Used by karate.log() and print statements.
     */
    public void log(Object message) {
        log(LogLevel.INFO, String.valueOf(message));
    }

    /**
     * Log a message at INFO level with format arguments.
     */
    public void log(String format, Object... args) {
        log(LogLevel.INFO, format, args);
    }

    // ========== Embeds ==========

    /**
     * Add an embed (HTML, image, etc.) to be included in step result.
     */
    public void embed(byte[] data, String mimeType, String name) {
        if (embeds == null) {
            embeds = new ArrayList<>();
        }
        embeds.add(new StepResult.Embed(data, mimeType, name));
    }

    /**
     * Add an embed with just data and mime type.
     */
    public void embed(byte[] data, String mimeType) {
        embed(data, mimeType, null);
    }

    /**
     * Collect and clear embeds.
     */
    public List<StepResult.Embed> collectEmbeds() {
        List<StepResult.Embed> result = embeds;
        embeds = null;
        return result;
    }

    // ========== Nested Results (for call steps) ==========

    /**
     * Add a nested scenario result from a called feature.
     * Used to capture feature call results for HTML report display.
     */
    public void addNestedResult(ScenarioResult result) {
        if (nestedResults == null) {
            nestedResults = new ArrayList<>();
        }
        nestedResults.add(result);
    }

    /**
     * Collect and clear nested results.
     */
    public List<ScenarioResult> collectNestedResults() {
        List<ScenarioResult> result = nestedResults;
        nestedResults = null;
        return result;
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
