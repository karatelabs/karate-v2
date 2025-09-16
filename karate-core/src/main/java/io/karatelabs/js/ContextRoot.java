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
package io.karatelabs.js;

import java.util.function.Consumer;

class ContextRoot extends DefaultContext {

    private Consumer<String> onConsoleLog;

    ContextListener listener;
    JavaBridge javaBridge;

    ContextRoot() {
        super(null, null, -1, null, null, null);
    }

    @Override
    public String toString() {
        return "ROOT";
    }

    void setOnConsoleLog(Consumer<String> onConsoleLog) {
        this.onConsoleLog = onConsoleLog;
        put("console", createConsole());
    }

    @Override
    Object get(String key) {
        if (_bindings != null && _bindings.containsKey(key)) {
            return _bindings.get(key);
        }
        Object global = initGlobal(key);
        if (global != null) {
            put(key, global);
            return global;
        }
        return Terms.UNDEFINED;
    }

    @Override
    boolean hasKey(String key) {
        if (_bindings != null && _bindings.containsKey(key)) {
            return true;
        }
        return switch (key) {
            case "console", "parseInt", "parseFloat", "undefined", "Array", "Date", "Error", "Infinity", "Java",
                 "JSON", "Math", "NaN", "Number", "Boolean", "Object", "RegExp", "String", "TypeError",
                 "TextEncoder", "TextDecoder" -> true;
            default -> false;
        };
    }

    @SuppressWarnings("unchecked")
    private Object initGlobal(String key) {
        return switch (key) {
            case "console" -> createConsole();
            case "parseInt" -> (Invokable) args -> Terms.parseFloat(args[0] + "", true);
            case "parseFloat" -> (Invokable) args -> Terms.parseFloat(args[0] + "", false);
            case "undefined" -> Terms.UNDEFINED;
            case "Array" -> new JsArray();
            case "Date" -> new JsDate();
            case "Error" -> new JsError("Error");
            case "Infinity" -> Double.POSITIVE_INFINITY;
            case "Java" -> new JsJava(javaBridge);
            case "JSON" -> new JsJson();
            case "Math" -> new JsMath();
            case "NaN" -> Double.NaN;
            case "Number" -> new JsNumber();
            case "Boolean" -> new JsBoolean();
            case "Object" -> new JsObject();
            case "RegExp" -> new JsRegex();
            case "String" -> new JsString();
            case "TypeError" -> new JsError("TypeError");
            case "TextDecoder" -> new JsTextDecoder();
            case "TextEncoder" -> new JsTextEncoder();
            default -> null;
        };
    }

    private ObjectLike createConsole() {
        return (SimpleObject) name -> {
            if ("log".equals(name)) {
                return (Invokable) args -> {
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < args.length; i++) {
                        Object arg = args[i];
                        if (i > 0) {
                            sb.append(' ');
                        }
                        JsCallable callable = null;
                        if (arg instanceof ObjectLike objectLike) {
                            callable = Terms.toCallable(objectLike.get("toString"));
                        }
                        if (callable != null) {
                            sb.append(callable.call(null, arg));
                        } else {
                            sb.append(Terms.TO_STRING(arg));
                        }
                    }
                    if (onConsoleLog != null) {
                        onConsoleLog.accept(sb.toString());
                    } else {
                        System.out.println(sb);
                    }
                    return null;
                };
            }
            return null;
        };
    }

}
