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

/**
 * Static logger for Java infrastructure code.
 * Default: prints to System.err with level prefix.
 * Can be configured to use SLF4J at runtime.
 */
public class JvmLogger {

    private static LogAppender appender = LogAppender.STDERR;
    private static LogLevel threshold = LogLevel.INFO;

    // ========== Configuration ==========

    public static void setAppender(LogAppender appender) {
        JvmLogger.appender = appender;
    }

    public static void setLevel(LogLevel level) {
        JvmLogger.threshold = level;
    }

    // ========== Logging ==========

    public static void trace(String format, Object... args) {
        log(LogLevel.TRACE, format, args);
    }

    public static void debug(String format, Object... args) {
        log(LogLevel.DEBUG, format, args);
    }

    public static void info(String format, Object... args) {
        log(LogLevel.INFO, format, args);
    }

    public static void warn(String format, Object... args) {
        log(LogLevel.WARN, format, args);
    }

    public static void error(String format, Object... args) {
        log(LogLevel.ERROR, format, args);
    }

    public static void error(String message, Throwable t) {
        if (LogLevel.ERROR.isEnabled(threshold)) {
            appender.log(LogLevel.ERROR, message, t);
        }
    }

    private static void log(LogLevel level, String format, Object... args) {
        if (level.isEnabled(threshold)) {
            appender.log(level, format, args);
        }
    }

    // ========== Level Checks ==========

    public static boolean isTraceEnabled() {
        return LogLevel.TRACE.isEnabled(threshold);
    }

    public static boolean isDebugEnabled() {
        return LogLevel.DEBUG.isEnabled(threshold);
    }

    public static boolean isInfoEnabled() {
        return LogLevel.INFO.isEnabled(threshold);
    }

    public static boolean isWarnEnabled() {
        return LogLevel.WARN.isEnabled(threshold);
    }

    public static boolean isErrorEnabled() {
        return LogLevel.ERROR.isEnabled(threshold);
    }
}
