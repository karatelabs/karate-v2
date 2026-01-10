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

class JsObject implements ObjectLike, Map<String, Object>, Invokable {

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
    public Object getMember(String name) {
        if (_map != null && _map.containsKey(name)) {
            return _map.get(name);
        }
        if ("prototype".equals(name)) {
            return getPrototype();
        }
        return getPrototype().getMember(name);
    }

    @Override
    public void putMember(String name, Object value) {
        if (_map == null) {
            _map = new LinkedHashMap<>();
        }
        _map.put(name, value);
    }

    @Override
    public void removeMember(String name) {
        if (_map != null) {
            _map.remove(name);
        }
    }

    @Override
    public Map<String, Object> toMap() {
        return _map == null ? Collections.emptyMap() : _map;
    }

    // =================================================================================================
    // Map<String, Object> interface - auto-unwraps values for Java consumers
    // =================================================================================================

    @Override
    public int size() {
        return _map == null ? 0 : _map.size();
    }

    @Override
    public boolean isEmpty() {
        return _map == null || _map.isEmpty();
    }

    @Override
    public boolean containsKey(Object key) {
        return _map != null && _map.containsKey(key);
    }

    @Override
    public boolean containsValue(Object value) {
        if (_map == null) return false;
        // Check for unwrapped equivalence
        for (Object v : _map.values()) {
            Object unwrapped = Engine.toJava(v);
            if (Objects.equals(unwrapped, value)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public Object get(Object key) {
        // Map.get() - auto-unwrap, own properties only (no prototype chain)
        Object raw = _map != null ? _map.get(key) : null;
        return Engine.toJava(raw);
    }

    @Override
    public Object put(String key, Object value) {
        if (_map == null) {
            _map = new LinkedHashMap<>();
        }
        Object previous = _map.put(key, value);
        return Engine.toJava(previous);
    }

    @Override
    public Object remove(Object key) {
        if (_map == null) return null;
        Object previous = _map.remove(key);
        return Engine.toJava(previous);
    }

    @Override
    public void putAll(Map<? extends String, ?> m) {
        if (_map == null) {
            _map = new LinkedHashMap<>();
        }
        _map.putAll(m);
    }

    @Override
    public void clear() {
        if (_map != null) {
            _map.clear();
        }
    }

    @Override
    public Set<String> keySet() {
        return _map == null ? Collections.emptySet() : _map.keySet();
    }

    @Override
    public Collection<Object> values() {
        if (_map == null) return Collections.emptyList();
        // Return unwrapped values
        List<Object> unwrapped = new ArrayList<>(_map.size());
        for (Object v : _map.values()) {
            unwrapped.add(Engine.toJava(v));
        }
        return unwrapped;
    }

    @Override
    public Set<Entry<String, Object>> entrySet() {
        if (_map == null) return Collections.emptySet();
        // Return entries with unwrapped values
        Set<Entry<String, Object>> unwrapped = new LinkedHashSet<>();
        for (Entry<String, Object> entry : _map.entrySet()) {
            unwrapped.add(new AbstractMap.SimpleEntry<>(entry.getKey(), Engine.toJava(entry.getValue())));
        }
        return unwrapped;
    }

    // =================================================================================================

    /**
     * Returns an iterable for JS for-in/for-of iteration with KeyValue pairs.
     * This is used internally by JS iteration constructs.
     */
    public Iterable<KeyValue> jsEntries() {
        return () -> new Iterator<>() {
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
    JsObject fromThis(Context context) {
        Object thisObject = context.getThisObject();
        if (thisObject instanceof JsObject jo) {
            return jo;
        }
        if (thisObject instanceof Map<?, ?> map) {
            return new JsObject((Map<String, Object>) map);
        }
        return this;
    }

    Map<String, Object> asMap(Context context) {
        return fromThis(context).toMap();
    }

    JsCallable toCallable(Object[] args) {
        if (args.length == 0) {
            throw new RuntimeException("function expected");
        }
        return Terms.toCallable(args[0]);
    }

    @Override
    public Object invoke(Object... args) {
        return new JsObject();
    }

}
