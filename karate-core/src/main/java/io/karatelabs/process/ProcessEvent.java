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
package io.karatelabs.process;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Event emitted by process execution.
 * Provides unified event model for stdout, stderr, and exit.
 */
public record ProcessEvent(
        Type type,
        String data,
        Integer exitCode
) {

    public enum Type {
        STDOUT,
        STDERR,
        EXIT
    }

    public static ProcessEvent stdout(String line) {
        return new ProcessEvent(Type.STDOUT, line, null);
    }

    public static ProcessEvent stderr(String line) {
        return new ProcessEvent(Type.STDERR, line, null);
    }

    public static ProcessEvent exit(int exitCode) {
        return new ProcessEvent(Type.EXIT, null, exitCode);
    }

    public boolean isStdout() {
        return type == Type.STDOUT;
    }

    public boolean isStderr() {
        return type == Type.STDERR;
    }

    public boolean isExit() {
        return type == Type.EXIT;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("type", type.name().toLowerCase());
        if (data != null) {
            map.put("data", data);
        }
        if (exitCode != null) {
            map.put("exitCode", exitCode);
        }
        return map;
    }

}
