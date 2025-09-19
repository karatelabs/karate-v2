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

import java.util.*;

class JsObject implements ObjectLike, JsCallable, Iterable<KeyValue> {

    final JsObject _this = this;

    private Map<String, Object> _map;

    JsObject(Map<String, Object> map) {
        this._map = map;
    }

    JsObject() {
        this(null);
    }

    private Prototype _prototype;

    final Prototype getPrototype() {
        if (_prototype == null) {
            _prototype = initPrototype();
        }
        return _prototype;
    }

    Prototype initPrototype() {
        return new Prototype(null) {
            @SuppressWarnings("unchecked")
            @Override
            public Object getProperty(String propName) {
                return switch (propName) {
                    case "toString" -> (JsCallable) (context, args) -> Terms.TO_STRING(context.getThisObject());
                    case "valueOf" -> (JsCallable) (context, args) -> context.getThisObject();
                    case "hasOwnProperty" -> (JsCallable) (context, args) -> {
                        if (args.length == 0 || args[0] == null) {
                            return false;
                        }
                        String prop = args[0].toString();
                        Map<String, Object> props = asMap(context);
                        return props.containsKey(prop);
                    };
                    // static ==========================================================================================
                    case "keys" -> (Invokable) args -> {
                        List<Object> result = new ArrayList<>();
                        for (KeyValue kv : Terms.toIterable(args[0])) {
                            result.add(kv.key);
                        }
                        return result;
                    };
                    case "values" -> (Invokable) args -> {
                        List<Object> result = new ArrayList<>();
                        for (KeyValue kv : Terms.toIterable(args[0])) {
                            result.add(kv.value);
                        }
                        return result;
                    };
                    case "entries" -> (Invokable) args -> {
                        List<Object> result = new ArrayList<>();
                        for (KeyValue kv : Terms.toIterable(args[0])) {
                            List<Object> entry = new ArrayList<>();
                            entry.add(kv.key);
                            entry.add(kv.value);
                            result.add(entry);
                        }
                        return result;
                    };
                    case "assign" -> (Invokable) args -> {
                        if (args.length == 0) {
                            return new LinkedHashMap<>();
                        }
                        if (args[0] == null || args[0] == Terms.UNDEFINED) {
                            throw new RuntimeException("assign() requires valid first argument");
                        }
                        Map<String, Object> result = new LinkedHashMap<>();
                        for (KeyValue kv : Terms.toIterable(args[0])) {
                            result.put(kv.key, kv.value);
                        }
                        for (int i = 1; i < args.length; i++) {
                            for (KeyValue kv : Terms.toIterable(args[i])) {
                                result.put(kv.key, kv.value);
                            }
                        }
                        return result;
                    };
                    case "fromEntries" -> (Invokable) args -> {
                        if (args.length == 0 || args[0] == null || args[0] == Terms.UNDEFINED) {
                            throw new RuntimeException("fromEntries() requires valid argument(s)");
                        }
                        Map<String, Object> result = new LinkedHashMap<>();
                        for (KeyValue kv : Terms.toIterable(args[0])) {
                            if (kv.value instanceof List) {
                                List<Object> list = (List<Object>) kv.value;
                                if (!list.isEmpty()) {
                                    Object key = list.getFirst();
                                    if (key != null) {
                                        Object value = null;
                                        if (list.size() > 1) {
                                            value = list.get(1);
                                        }
                                        result.put(key.toString(), value);
                                    }
                                }
                            }
                        }
                        return result;
                    };
                    case "is" -> (Invokable) args -> {
                        if (args.length < 2) {
                            return false;
                        }
                        return Terms.eq(args[0], args[1], true);
                    };
                    default -> null;
                };
            }
        };
    }

    @Override
    public Object get(String name) {
        if (_map != null && _map.containsKey(name)) {
            return _map.get(name);
        }
        if ("prototype".equals(name)) {
            return getPrototype();
        }
        return getPrototype().get(name);
    }

    @Override
    public void put(String name, Object value) {
        if (_map == null) {
            _map = new LinkedHashMap<>();
        }
        _map.put(name, value);
    }

    @Override
    public void remove(String name) {
        if (_map != null) {
            _map.remove(name);
        }
    }

    @Override
    public Map<String, Object> toMap() {
        return _map == null ? Collections.emptyMap() : _map;
    }

    @Override
    public Iterator<KeyValue> iterator() {
        return new Iterator<>() {
            final Iterator<Map.Entry<String, Object>> entries = toMap().entrySet().iterator();
            int index = 0;

            @Override
            public boolean hasNext() {
                return entries.hasNext();
            }

            @Override
            public KeyValue next() {
                Map.Entry<String, Object> entry = entries.next();
                return new KeyValue(_this, index++, entry.getKey(), entry.getValue());
            }
        };
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> asMap(Context context) {
        Object thisObject = context.getThisObject();
        if (thisObject instanceof Map) {
            return (Map<String, Object>) thisObject;
        }
        return toMap();
    }

    JsCallable toCallable(Object[] args) {
        if (args.length == 0) {
            throw new RuntimeException("function expected");
        }
        return Terms.toCallable(args[0]);
    }

    @Override
    public Object call(Context context, Object... args) {
        return new JsObject();
    }

}
