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
package io.karatelabs.output;

/**
 * Backend for JVM logging. Default is stderr, can be replaced with SLF4J.
 */
public interface LogAppender {

    void log(LogLevel level, String format, Object... args);

    void log(LogLevel level, String message, Throwable t);

    /**
     * Default appender: prints to System.err
     */
    LogAppender STDERR = new LogAppender() {
        @Override
        public void log(LogLevel level, String format, Object... args) {
            String msg = LogContext.format(format, args);
            System.err.println("[" + level + "] " + msg);
        }

        @Override
        public void log(LogLevel level, String message, Throwable t) {
            System.err.println("[" + level + "] " + message);
            if (t != null) {
                t.printStackTrace(System.err);
            }
        }
    };
}
