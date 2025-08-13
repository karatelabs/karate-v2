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
package io.karatelabs.match;

import io.karatelabs.common.Json;
import io.karatelabs.common.Xml;
import org.w3c.dom.Node;

import java.lang.reflect.Array;
import java.util.*;

public class Value {

    public enum Type {
        NULL,
        BOOLEAN,
        NUMBER,
        STRING,
        BYTES,
        LIST,
        MAP,
        XML,
        OTHER
    }

    final Type type;
    final boolean exceptionOnMatchFailure;

    private final Object value;

    Value(Object value) {
        this(value, false);
    }

    Value(Object value, boolean exceptionOnMatchFailure) {
        if (value instanceof Set<?> set) {
            value = new ArrayList<Object>(set);
        } else if (value != null && value.getClass().isArray()) {
            int length = Array.getLength(value);
            List<Object> list = new ArrayList<>(length);
            for (int i = 0; i < length; i++) {
                list.add(Array.get(value, i));
            }
            value = list;
        }
        this.value = value;
        this.exceptionOnMatchFailure = exceptionOnMatchFailure;
        if (value == null) {
            type = Type.NULL;
        } else if (value instanceof Node) {
            type = Type.XML;
        } else if (value instanceof List) {
            type = Type.LIST;
        } else if (value instanceof Map) {
            type = Type.MAP;
        } else if (value instanceof String) {
            type = Type.STRING;
        } else if (Number.class.isAssignableFrom(value.getClass())) {
            type = Type.NUMBER;
        } else if (Boolean.class.equals(value.getClass())) {
            type = Type.BOOLEAN;
        } else if (value instanceof byte[]) {
            type = Type.BYTES;
        } else {
            type = Type.OTHER;
        }
    }

    public boolean isBoolean() {
        return type == Type.BOOLEAN;
    }

    public boolean isNumber() {
        return type == Type.NUMBER;
    }

    public boolean isString() {
        return type == Type.STRING;
    }

    public boolean isNull() {
        return type == Type.NULL;
    }

    public boolean isMap() {
        return type == Type.MAP;
    }

    public boolean isList() {
        return type == Type.LIST;
    }

    public boolean isXml() {
        return type == Type.XML;
    }

    boolean isNotPresent() {
        return "#notpresent".equals(value);
    }

    boolean isArrayObjectOrReference() {
        String temp = value.toString();
        return temp.startsWith("#[")
                || temp.startsWith("##[")
                || temp.startsWith("#(")
                || temp.startsWith("##(")
                || "#array".equals(temp)
                || "##array".equals(temp)
                || "#object".equals(temp)
                || "##object".equals(temp);
    }

    boolean isMapOrListOrXml() {
        switch (type) {
            case MAP:
            case LIST:
            case XML:
                return true;
            default:
                return false;
        }
    }

    @SuppressWarnings("unchecked")
    public <T> T getValue() {
        return (T) value;
    }

    String getWithinSingleQuotesIfString() {
        if (type == Type.STRING) {
            return "'" + value + "'";
        } else {
            return getAsString();
        }
    }

    public String getAsString() {
        switch (type) {
            case LIST:
            case MAP:
                return Json.toJson(value);
            case XML:
                return Xml.toString(getValue());
            default:
                return value + "";
        }
    }

    String getAsXmlString() {
        if (type == Type.MAP) {
            Node node = Xml.fromMap(getValue());
            return Xml.toString(node);
        } else {
            return getAsString();
        }
    }

    Value getSortedLike(Value other) {
        if (isMap() && other.isMap()) {
            Map<String, Object> reference = other.getValue();
            Map<String, Object> source = getValue();
            Set<String> remainder = new LinkedHashSet<>(source.keySet());
            Map<String, Object> result = new LinkedHashMap<>(source.size());
            reference.keySet().forEach(key -> {
                if (source.containsKey(key)) {
                    result.put(key, source.get(key));
                    remainder.remove(key);
                }
            });
            for (String key : remainder) {
                result.put(key, source.get(key));
            }
            return new Value(result, other.exceptionOnMatchFailure);
        } else {
            return this;
        }
    }

    @Override
    public String toString() {
        return "[type: " + type + ", value: " + value + "]";
    }

    public Result is(Match.Type matchType, Object expected) {
        Operation op = new Operation(matchType, this, new Value(parseIfJsonOrXmlString(expected), exceptionOnMatchFailure), false);
        op.execute();
        if (op.pass) {
            return Result.PASS;
        } else {
            if (exceptionOnMatchFailure) {
                throw new RuntimeException(op.getFailureReasons());
            }
            return Result.fail(op.getFailureReasons());
        }
    }

    static Object parseIfJsonOrXmlString(Object o) {
        if (o instanceof String s) {
            if (s.isEmpty()) {
                return o;
            } else if (Json.isJson(s)) {
                return Json.of(s).value();
            } else if (Xml.isXml(s)) {
                return Xml.toXmlDoc(s);
            } else {
                if (s.charAt(0) == '\\') {
                    return s.substring(1);
                }
            }
        }
        return o;
    }

    //======================================================================
    //
    public Result isEqualTo(Object expected) {
        return is(Match.Type.EQUALS, expected);
    }

    public Result contains(Object expected) {
        return is(Match.Type.CONTAINS, expected);
    }

    public Result containsDeep(Object expected) {
        return is(Match.Type.CONTAINS_DEEP, expected);
    }

    public Result containsOnly(Object expected) {
        return is(Match.Type.CONTAINS_ONLY, expected);
    }

    public Result containsOnlyDeep(Object expected) {
        return is(Match.Type.CONTAINS_ONLY_DEEP, expected);
    }

    public Result containsAny(Object expected) {
        return is(Match.Type.CONTAINS_ANY, expected);
    }

    public Result isNotEqualTo(Object expected) {
        return is(Match.Type.NOT_EQUALS, expected);
    }

    public Result isNotContaining(Object expected) {
        return is(Match.Type.NOT_CONTAINS, expected);
    }

    public Result isEachEqualTo(Object expected) {
        return is(Match.Type.EACH_EQUALS, expected);
    }

    public Result isEachNotEqualTo(Object expected) {
        return is(Match.Type.EACH_NOT_EQUALS, expected);
    }

    public Result isEachContaining(Object expected) {
        return is(Match.Type.EACH_CONTAINS, expected);
    }

    public Result isEachNotContaining(Object expected) {
        return is(Match.Type.EACH_NOT_CONTAINS, expected);
    }

    public Result isEachContainingDeep(Object expected) {
        return is(Match.Type.EACH_CONTAINS_DEEP, expected);
    }

    public Result isEachContainingOnly(Object expected) {
        return is(Match.Type.EACH_CONTAINS_ONLY, expected);
    }

    public Result isEachContainingAny(Object expected) {
        return is(Match.Type.EACH_CONTAINS_ANY, expected);
    }

}
