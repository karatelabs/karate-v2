/*
 * The MIT License
 *
 * Copyright 2024 Karate Labs Inc.
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.function.Consumer;

public class Context {

    static final Logger logger = LoggerFactory.getLogger(Context.class);

    static final Context EMPTY = new Context(null, Collections.emptyMap());

    static final Object NAN = Double.NaN;

    public final Context parent;
    Node node;
    final Map<String, Object> bindings;

    Object thisObject = Terms.UNDEFINED;
    Context child;
    Consumer<String> onConsoleLog;
    ContextListener listener;

    Context(Context parent, Map<String, Object> bindings) {
        this.parent = parent;
        this.bindings = bindings;
        if (parent != null) {
            listener = parent.listener;
            parent.child = this;
        }
    }

    static Context root() {
        return new Context(null, new HashMap<>());
    }

    Context(Context parent, Node node) {
        this(parent, new HashMap<>());
        this.node = node;
    }

    public void setOnConsoleLog(Consumer<String> onConsoleLog) {
        this.onConsoleLog = onConsoleLog;
        bindings.put("console", createConsole());
    }

    public void setListener(ContextListener listener) {
        this.listener = listener;
    }

    public Node getNode() {
        return node;
    }

    public Object get(String name) {
        if ("this".equals(name)) {
            return thisObject;
        }
        if (bindings.containsKey(name)) {
            return bindings.get(name);
        }
        if (parent != null && parent.hasKey(name)) {
            return parent.get(name);
        }
        Object global = getGlobal(name);
        if (global != null) {
            bindings.put(name, global);
            return global;
        }
        return Terms.UNDEFINED;
    }

    void put(String name, Object value) {
        if (value instanceof JsFunction) {
            ((JsFunction) value).name = name;
        }
        bindings.put(name, value);
    }

    void update(String name, Object value) {
        if (bindings.containsKey(name)) {
            bindings.put(name, value);
        } else if (parent != null && parent.hasKey(name)) {
            parent.update(name, value);
        } else {
            bindings.put(name, value);
            if (listener != null) {
                listener.onVariableWrite(this, name, value);
            }
        }
    }

    void remove(String name) {
        bindings.remove(name);
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
                        if (arg instanceof ObjectLike) {
                            Object toString = ((ObjectLike) arg).get("toString");
                            if (toString instanceof Invokable) {
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

    boolean hasKey(String name) {
        if (bindings.containsKey(name)) {
            return true;
        }
        if (parent != null && parent.hasKey(name)) {
            return true;
        }
        switch (name) {
            case "console":
            case "parseInt":
            case "undefined":
            case "Array":
            case "Date":
            case "Error":
            case "Infinity":
            case "Java":
            case "JSON":
            case "Math":
            case "NaN":
            case "Number":
            case "Object":
            case "RegExp":
            case "String":
            case "TypeError":
                return true;
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private Object getGlobal(String key) {
        switch (key) {
            case "console":
                return createConsole();
            case "parseInt":
                return (Invokable) args -> Terms.toNumber(args[0]);
            case "undefined":
                return Terms.UNDEFINED;
            case "Array":
                return new JsArray();
            case "Date":
                return new JsDate();
            case "Error":
                return new JsError("Error");
            case "Infinity":
                return Terms.POSITIVE_INFINITY;
            case "Java":
                return (SimpleObject) name -> {
                    if ("type".equals(name)) {
                        return (Invokable) args -> new JavaClass((String) args[0]);
                    }
                    return null;
                };
            case "JSON":
                return (SimpleObject) name -> {
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
            case "Math":
                return new JsMath();
            case "NaN":
                return Double.NaN;
            case "Number":
                return new JsNumber();
            case "Object":
                return new JsObject();
            case "RegExp":
                return new JsRegex();
            case "String":
                return new JsString();
            case "TypeError":
                return new JsError("TypeError");
        }
        return null;
    }

    //==================================================================================================================
    //
    boolean construct;
    int iterationIndex = -1;

    public int getIterationIndex() {
        return iterationIndex;
    }

    private ExitType exitType;
    private Object returnValue;
    private Object errorThrown;

    public Object stopAndBreak() {
        exitType = ExitType.BREAK;
        returnValue = null;
        errorThrown = null;
        return null;
    }

    Object stopAndThrow(Object error) {
        exitType = ExitType.THROW;
        returnValue = null;
        errorThrown = error;
        return error;
    }

    Object stopAndReturn(Object value) {
        exitType = ExitType.RETURN;
        returnValue = value;
        errorThrown = null;
        return value;
    }

    public Object stopAndContinue() {
        exitType = ExitType.CONTINUE;
        returnValue = null;
        errorThrown = null;
        return null;
    }

    boolean isStopped() {
        return exitType != null;
    }

    public ExitType getExitType() {
        return exitType;
    }

    boolean isContinuing() {
        return exitType == ExitType.CONTINUE;
    }

    public void reset() {
        exitType = null;
        returnValue = null;
        errorThrown = null;
    }

    boolean isError() {
        return exitType == ExitType.THROW;
    }

    public Object getReturnValue() {
        return returnValue;
    }

    public Object getErrorThrown() {
        return errorThrown;
    }

    void updateFrom(Context childContext) {
        exitType = childContext.exitType;
        errorThrown = childContext.errorThrown;
        returnValue = childContext.returnValue;
    }

    public String getPath() {
        String parentPath = parent == null ? null : parent.getPath();
        return parentPath == null ? node.type.toString() : parentPath + ":" + node.type;
    }

    @Override
    public String toString() {
        return getPath();
    }

}
