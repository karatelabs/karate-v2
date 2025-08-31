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

class JsObject implements ObjectLike, Invokable, Iterable<KeyValue> {

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
                    case "toString" -> (JsCallable) (context, args) -> Terms.TO_STRING(context.thisObject);
                    case "valueOf" -> (JsCallable) (context, args) -> context.thisObject;
                    case "hasOwnProperty" -> (JsCallable) (context, args) -> {
                        if (args.length == 0 || args[0] == null) {
                            return false;
                        }
                        String prop = args[0].toString();
                        if (context.thisObject instanceof ObjectLike objectLike) {
                            Map<String, Object> map = objectLike.toMap();
                            return map != null && map.containsKey(prop);
                        } else if (context.thisObject instanceof Map) {
                            Map<String, Object> map = (Map<String, Object>) context.thisObject;
                            return map.containsKey(prop);
                        }
                        return false;
                    };
                    // static ==========================================================================================
                    case "keys" -> (Invokable) args -> {
                        List<Object> result = new ArrayList<>();
                        for (KeyValue kv : toIterable(args[0])) {
                            result.add(kv.key);
                        }
                        return result;
                    };
                    case "values" -> (Invokable) args -> {
                        List<Object> result = new ArrayList<>();
                        for (KeyValue kv : toIterable(args[0])) {
                            result.add(kv.value);
                        }
                        return result;
                    };
                    case "entries" -> (Invokable) args -> {
                        List<Object> result = new ArrayList<>();
                        for (KeyValue kv : toIterable(args[0])) {
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
                        Iterable<KeyValue> iterable = toIterable(args[0]);
                        Map<String, Object> result = new LinkedHashMap<>();
                        for (KeyValue kv : iterable) {
                            result.put(kv.key, kv.value);
                        }
                        for (int i = 1; i < args.length; i++) {
                            for (KeyValue kv : toIterable(args[i])) {
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
                        for (KeyValue kv : toIterable(args[0])) {
                            result.put(kv.key, kv.value);
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
    public Object invoke(Object... args) {
        return new JsObject(); // todo string, number, date
    }

    @Override
    public Iterator<KeyValue> iterator() {
        return toIterable(this).iterator();
    }

    @SuppressWarnings("unchecked")
    static Iterable<KeyValue> toIterable(Object object) {
        if (object instanceof List) {
            object = new JsArray((List<Object>) object);
        }
        if (object instanceof JsArray array) {
            return () -> {
                final int size = array.size();
                return new Iterator<>() {
                    int index = 0;

                    @Override
                    public boolean hasNext() {
                        return index < size;
                    }

                    @Override
                    public KeyValue next() {
                        int i = index++;
                        Object value = array.get(i);
                        if (value instanceof List) {
                            List<Object> list = (List<Object>) value;
                            String k = null;
                            Object v = null;
                            if (!list.isEmpty()) {
                                k = list.getFirst() == null ? null : list.getFirst().toString();
                            }
                            if (list.size() > 1) {
                                v = list.get(1);
                            }
                            return new KeyValue(array, i, k, v);
                        } else {
                            return new KeyValue(array, i, i + "", array.get(i));
                        }
                    }
                };
            };
        }
        if (object instanceof Map) {
            object = new JsObject((Map<String, Object>) object);
        }
        if (!(object instanceof Iterable)) {
            object = new JsObject();
        }
        final ObjectLike objectLike = (ObjectLike) object;
        return () -> {
            final Iterator<Map.Entry<String, Object>> entries = objectLike.toMap().entrySet().iterator();
            return new Iterator<>() {
                int index = 0;

                @Override
                public boolean hasNext() {
                    return entries.hasNext();
                }

                @Override
                public KeyValue next() {
                    Map.Entry<String, Object> entry = entries.next();
                    return new KeyValue(objectLike, index++, entry.getKey(), entry.getValue());
                }
            };
        };
    }

    JsCallable toCallable(Object[] args) {
        if (args.length == 0) {
            throw new RuntimeException("function expected");
        }
        if (args[0] instanceof JsCallable) {
            return (JsCallable) args[0];
        }
        if (args[0] instanceof Invokable) {
            return (context, callArgs) -> ((Invokable) args[0]).invoke(callArgs);
        }
        throw new RuntimeException("function expected");
    }

}
