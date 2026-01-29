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

import java.util.*;

/**
 * Auto-unwrapping Map wrapper for JS engine bindings.
 * <p>
 * This wrapper ensures that Java code accessing bindings always gets
 * idiomatic Java types (Date instead of JsDate, null instead of undefined, etc.)
 * while the underlying map stores raw JS values for engine use.
 * <p>
 * Key behaviors:
 * <ul>
 *   <li>{@link #get(Object)} - Auto-unwraps JS types via {@link Engine#toJava(Object)}</li>
 *   <li>{@link #values()} - Returns auto-unwrapped values</li>
 *   <li>{@link #entrySet()} - Returns entries with auto-unwrapped values</li>
 *   <li>{@link #put(String, Object)} - Stores values directly (no conversion)</li>
 *   <li>Changes to the underlying map are visible through this wrapper</li>
 *   <li>Changes via this wrapper are visible to code holding the underlying map</li>
 * </ul>
 */
public class Bindings implements Map<String, Object> {

    private final Map<String, Object> delegate;

    /**
     * Creates a Bindings wrapper around the given map.
     * Changes to the underlying map will be visible through this wrapper.
     */
    public Bindings(Map<String, Object> map) {
        this.delegate = map != null ? map : new HashMap<>();
    }

    /**
     * Creates a Bindings wrapper with a new empty HashMap.
     */
    public Bindings() {
        this(new HashMap<>());
    }

    /**
     * Returns the underlying raw map (for internal engine use).
     */
    public Map<String, Object> getRawMap() {
        return delegate;
    }

    @Override
    public int size() {
        return delegate.size();
    }

    @Override
    public boolean isEmpty() {
        return delegate.isEmpty();
    }

    @Override
    public boolean containsKey(Object key) {
        return delegate.containsKey(key);
    }

    @Override
    public boolean containsValue(Object value) {
        // Check for unwrapped equivalence
        for (Object v : delegate.values()) {
            Object unwrapped = Engine.toJava(v);
            if (Objects.equals(unwrapped, value)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public Object get(Object key) {
        // Auto-unwrap JS types for Java consumers
        return Engine.toJava(delegate.get(key));
    }

    /**
     * Returns the raw (non-unwrapped) value for internal engine use.
     */
    public Object getRaw(String key) {
        return delegate.get(key);
    }

    @Override
    public Object put(String key, Object value) {
        Object previous = delegate.put(key, value);
        return Engine.toJava(previous);
    }

    @Override
    public Object remove(Object key) {
        Object previous = delegate.remove(key);
        return Engine.toJava(previous);
    }

    @Override
    public void putAll(Map<? extends String, ?> m) {
        delegate.putAll(m);
    }

    @Override
    public void clear() {
        delegate.clear();
    }

    @Override
    public Set<String> keySet() {
        return delegate.keySet();
    }

    @Override
    public Collection<Object> values() {
        // Return auto-unwrapped values
        List<Object> unwrapped = new ArrayList<>(delegate.size());
        for (Object v : delegate.values()) {
            unwrapped.add(Engine.toJava(v));
        }
        return unwrapped;
    }

    @Override
    public Set<Entry<String, Object>> entrySet() {
        // Return entries with auto-unwrapped values
        Set<Entry<String, Object>> unwrapped = new LinkedHashSet<>();
        for (Entry<String, Object> entry : delegate.entrySet()) {
            unwrapped.add(new AbstractMap.SimpleEntry<>(entry.getKey(), Engine.toJava(entry.getValue())));
        }
        return unwrapped;
    }

    // Use identity-based hashCode/equals to avoid infinite recursion
    // when bindings contain objects with circular references
    @Override
    public boolean equals(Object o) {
        return this == o;
    }

    @Override
    public int hashCode() {
        return System.identityHashCode(this);
    }

    @Override
    public String toString() {
        return delegate.toString();
    }

}
