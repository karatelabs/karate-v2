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

import net.minidev.json.JSONValue;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

class ContextRoot extends Context {

    private Map<String, Object> _globals;
    private Consumer<String> onConsoleLog;

    Event.Listener listener;
    JavaBridge javaBridge;

    ContextRoot() {
        super(null, null, -1, null, null);
    }

    void setOnConsoleLog(Consumer<String> onConsoleLog) {
        this.onConsoleLog = onConsoleLog;
        put("console", createConsole());
    }

    @Override
    Object get(String name) {
        if (_globals != null && _globals.containsKey(name)) {
            return _globals.get(name);
        }
        Object global = initGlobal(name);
        if (global != null) {
            put(name, global);
            return global;
        }
        return Terms.UNDEFINED;
    }

    @Override
    void put(String name, Object value) {
        if (_globals == null) {
            _globals = new HashMap<>();
        }
        _globals.put(name, value);
    }

    @Override
    void update(String name, Object value) {
        put(name, value);
    }

    @Override
    boolean hasKey(String name) {
        if (_globals != null && _globals.containsKey(name)) {
            return true;
        }
        return switch (name) {
            case "console", "parseInt", "parseFloat", "undefined", "Array", "Date", "Error", "Infinity", "Java", "JSON",
                 "Math",
                 "NaN", "Number", "Boolean", "Object", "RegExp", "String", "TypeError" -> true;
            default -> false;
        };
    }

    @Override
    public String getPath() {
        return "ROOT";
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
            case "Infinity" -> Terms.POSITIVE_INFINITY;
            case "Java" -> (SimpleObject) name -> {
                if (javaBridge == null) {
                    throw new RuntimeException("java interop not enabled");
                }
                if ("type".equals(name)) {
                    return (Invokable) args -> new JavaClass(javaBridge, (String) args[0]);
                }
                return null;
            };
            case "JSON" -> (SimpleObject) name -> {
                if ("stringify".equals(name)) {
                    return (Invokable) args -> {
                        String json = JSONValue.toJSONString(args[0]);
                        if (args.length == 1) {
                            return json;
                        }
                        List<String> list = (List<String>) args[1];
                        Map<String, Object> map = (Map<String, Object>) JSONValue.parse(json);
                        Map<String, Object> result = new LinkedHashMap<>();
                        for (String k : list) {
                            result.put(k, map.get(k));
                        }
                        return JSONValue.toJSONString(result);
                    };
                } else if ("parse".equals(name)) {
                    return (Invokable) args -> JSONValue.parse((String) args[0]);
                }
                return null;
            };
            case "Math" -> new JsMath();
            case "NaN" -> Double.NaN;
            case "Number" -> new JsNumber();
            case "Boolean" -> new JsBoolean();
            case "Object" -> new JsObject();
            case "RegExp" -> new JsRegex();
            case "String" -> new JsString();
            case "TypeError" -> new JsError("TypeError");
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
                        if (arg instanceof ObjectLike objectLike) {
                            Object toString = objectLike.get("toString");
                            if (toString instanceof Invokable invokable) {
                                sb.append(((Invokable) toString).invoke(arg));
                            } else {
                                sb.append(Terms.TO_STRING(arg));
                            }
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
