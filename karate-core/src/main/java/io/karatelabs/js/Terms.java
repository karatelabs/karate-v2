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

import java.math.BigDecimal;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

public class Terms {

    public static final Object UNDEFINED = new Object() {
        @Override
        public String toString() {
            return "undefined";
        }
    };

    static final Number POSITIVE_INFINITY = Double.POSITIVE_INFINITY;
    static final Number NEGATIVE_INFINITY = Double.NEGATIVE_INFINITY;

    static final Number POSITIVE_ZERO = 0;
    static final Number NEGATIVE_ZERO = -0.0;

    static final Object NAN = Double.NaN;

    final Number lhs;
    final Number rhs;

    Terms(Object lhsObject, Object rhsObject) {
        lhs = toNumber(lhsObject);
        rhs = toNumber(rhsObject);
    }

    Terms(Context context, List<Node> children) {
        this(Interpreter.eval(children.get(0), context), Interpreter.eval(children.get(2), context));
    }

    public static Number toNumber(Object value) {
        switch (value) {
            case null -> {
                return 0;
            }
            case Number number -> {
                return number;
            }
            case Boolean b -> {
                return b ? 1 : 0;
            }
            case JsDate keyValues -> {
                return keyValues.getTime();
            }
            default -> {
            }
        }
        String text = value.toString().trim();
        if (text.isEmpty()) {
            return 0;
        }
        try {
            return narrow(Double.parseDouble(text));
        } catch (Exception e) {
            if (text.charAt(0) == '0') {
                char second = text.charAt(1);
                if (second == 'x' || second == 'X') { // hex
                    long longValue = Long.parseLong(text.substring(2), 16);
                    return narrow(longValue);
                }
            }
            return Double.NaN;
        }
    }

    static boolean eq(Object lhs, Object rhs, boolean strict) {
        if (lhs == null) {
            return rhs == null || !strict && rhs == UNDEFINED;
        }
        if (lhs == UNDEFINED) {
            return rhs == UNDEFINED || !strict && rhs == null;
        }
        if (lhs == rhs) { // instance equality !
            return true;
        }
        if (lhs instanceof List || lhs instanceof Map) {
            return false;
        }
        if (lhs.equals(rhs)) {
            return true;
        }
        if (strict) {
            if (lhs instanceof Number && rhs instanceof Number) {
                return ((Number) lhs).doubleValue() == ((Number) rhs).doubleValue();
            }
            return false;
        }
        if (lhs instanceof Number || rhs instanceof Number) { // coerce to number
            Terms terms = new Terms(lhs, rhs);
            return terms.lhs.equals(terms.rhs);
        }
        return false;
    }

    static boolean lt(Object lhs, Object rhs) {
        Terms terms = new Terms(lhs, rhs);
        return terms.lhs.doubleValue() < terms.rhs.doubleValue();
    }

    static boolean gt(Object lhs, Object rhs) {
        Terms terms = new Terms(lhs, rhs);
        return terms.lhs.doubleValue() > terms.rhs.doubleValue();
    }

    static boolean ltEq(Object lhs, Object rhs) {
        Terms terms = new Terms(lhs, rhs);
        return terms.lhs.doubleValue() <= terms.rhs.doubleValue();
    }

    static boolean gtEq(Object lhs, Object rhs) {
        Terms terms = new Terms(lhs, rhs);
        return terms.lhs.doubleValue() >= terms.rhs.doubleValue();
    }

    Object bitAnd() {
        return lhs.intValue() & rhs.intValue();
    }

    Object bitOr() {
        return lhs.intValue() | rhs.intValue();
    }

    Object bitXor() {
        return lhs.intValue() ^ rhs.intValue();
    }

    Object bitShiftRight() {
        return lhs.intValue() >> rhs.intValue();
    }

    Object bitShiftLeft() {
        return lhs.intValue() << rhs.intValue();
    }

    Object bitShiftRightUnsigned() {
        return narrow((lhs.intValue() & 0xFFFFFFFFL) >>> rhs.intValue());
    }

    static Object bitNot(Object value) {
        Number number = toNumber(value);
        return ~number.intValue();
    }

    Object mul() {
        double result = lhs.doubleValue() * rhs.doubleValue();
        return narrow(result);
    }

    Object div() {
        if (rhs.equals(POSITIVE_ZERO)) {
            return lhs.doubleValue() > 0 ? POSITIVE_INFINITY : NEGATIVE_INFINITY;
        }
        if (rhs.equals(NEGATIVE_ZERO)) {
            return lhs.doubleValue() < 0 ? POSITIVE_INFINITY : NEGATIVE_INFINITY;
        }
        if (rhs.equals(POSITIVE_INFINITY)) {
            return lhs.doubleValue() > 0 ? POSITIVE_ZERO : NEGATIVE_ZERO;
        }
        if (rhs.equals(NEGATIVE_INFINITY)) {
            return lhs.doubleValue() < 0 ? POSITIVE_ZERO : NEGATIVE_ZERO;
        }
        double result = lhs.doubleValue() / rhs.doubleValue();
        return narrow(result);
    }

    Object min() {
        double result = lhs.doubleValue() - rhs.doubleValue();
        return narrow(result);
    }

    Object mod() {
        double result = lhs.doubleValue() % rhs.doubleValue();
        return narrow(result);
    }

    Object exp() {
        double result = Math.pow(lhs.doubleValue(), rhs.doubleValue());
        return narrow(result);
    }

    static Object add(Object lhs, Object rhs) {
        if (!(lhs instanceof Number lhsNum) || !(rhs instanceof Number rhsNum)) {
            return lhs + "" + rhs;
        }
        double result = lhsNum.doubleValue() + rhsNum.doubleValue();
        return narrow(result);
    }

    public static Number narrow(double d) {
        if (NEGATIVE_ZERO.equals(d)) {
            return d;
        }
        if (d % 1 != 0) {
            return d;
        }
        if (d <= Integer.MAX_VALUE) {
            return (int) d;
        }
        if (d <= Long.MAX_VALUE) {
            return (long) d;
        }
        return d;
    }

    static JavaMirror toJavaMirror(Object o) {
        return switch (o) {
            case String s -> new JsString(s);
            case Number n -> new JsNumber(n);
            case Boolean b -> new JsBoolean(b);
            case ZonedDateTime zdt -> new JsDate(zdt);
            case byte[] bytes -> new JsBytes(bytes);
            case null, default -> null;
        };
    }

    static JsCallable toCallable(Object o) {
        if (o instanceof JsCallable callable) {
            return callable;
        } else if (o instanceof Invokable invokable) {
            return (c, args) -> invokable.invoke(args);
        } else if (isJavaFunction(o)) {
            return new JavaFunction(o);
        } else {
            return null;
        }
    }

    private static boolean isJavaFunction(Object o) {
        return o instanceof Function
                || o instanceof Runnable
                || o instanceof JsCallable
                || o instanceof Consumer
                || o instanceof Supplier
                || o instanceof Predicate;
    }

    public static boolean isTruthy(Object value) {
        if (value == null || value.equals(UNDEFINED) || value.equals(Double.NaN)) {
            return false;
        }
        if (value instanceof JavaMirror mirror) {
            value = mirror.toJava();
        }
        return switch (value) {
            case Boolean b -> b;
            case Number number -> number.doubleValue() != 0;
            case String s -> !s.isEmpty();
            default -> true;
        };
    }

    static boolean isPrimitive(Object value) {
        if (value == null) {
            return true;
        }
        if (value instanceof String
                || (value instanceof Number && !(value instanceof BigDecimal))
                || value instanceof Boolean) {
            return true;
        }
        return value == UNDEFINED;
    }

    static String typeOf(Object value) {
        if (value instanceof String) {
            return "string";
        }
        if (value instanceof JsFunction) {
            return "function";
        }
        if (value instanceof Number) {
            return "number";
        }
        if (value instanceof Boolean) {
            return "boolean";
        }
        if (value == UNDEFINED) {
            return "undefined";
        }
        return "object";
    }

    static boolean instanceOf(Object lhs, Object rhs) {
        if (lhs instanceof JsObject objectLhs && rhs instanceof JsObject objectRhs) {
            if (lhs instanceof JavaMirror && rhs instanceof JavaMirror) {
                return lhs.getClass().equals(rhs.getClass());
            }
            Prototype prototypeLhs = objectLhs.getPrototype();
            if (prototypeLhs != null) {
                Object constructorLhs = prototypeLhs.get("constructor");
                if (constructorLhs != null) {
                    Object constructorRhs = objectRhs.get("constructor");
                    return constructorLhs == constructorRhs;
                }
            }
        }
        return false;
    }

    static String TO_STRING(Object o) {
        if (o == null) {
            return "[object Null]";
        }
        if (Terms.isPrimitive(o) || o instanceof JavaMirror) {
            return o.toString();
        }
        switch (o) {
            case JsArray keyValues -> {
                List<Object> list = keyValues.toList();
                return JSONValue.toJSONString(list);
            }
            case JsFunction ignored -> {
                return "[object Object]";
            }
            case ObjectLike objectLike -> {
                Map<String, Object> map = objectLike.toMap();
                if (map != null) {
                    return JSONValue.toJSONString(map);
                }
            }
            default -> {
            }
        }
        if (o instanceof Map || o instanceof List) {
            return JSONValue.toJSONString(o);
        }
        return "[object Object]";
    }

}
