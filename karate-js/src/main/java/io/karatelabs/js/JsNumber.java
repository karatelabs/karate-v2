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

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.util.Map;

class JsNumber extends JsObject implements JsPrimitive {

    final Number value;

    private static final long MAX_SAFE_INTEGER = 9007199254740991L;
    private static final long MIN_SAFE_INTEGER = -9007199254740991L;

    JsNumber() {
        this(0);
    }

    JsNumber(Number value) {
        this.value = value;
    }

    @Override
    public String toString() {
        return value.toString();
    }

    @Override
    public Object toJava() {
        return value;
    }


    @Override
    Prototype initPrototype() {
        Prototype wrapped = super.initPrototype();
        return new Prototype(wrapped) {
            @Override
            public Object getProperty(String propName) {
                return switch (propName) {
                    case "toFixed" -> (Invokable) args -> {
                        int digits = 0;
                        if (args.length > 0) {
                            digits = Terms.objectToNumber(args[0]).intValue();
                        }
                        double doubleValue = value.doubleValue();
                        BigDecimal bd = BigDecimal.valueOf(doubleValue);
                        bd = bd.setScale(digits, RoundingMode.HALF_UP);
                        StringBuilder pattern = new StringBuilder("0");
                        if (digits > 0) {
                            pattern.append(".");
                            pattern.append("0".repeat(digits));
                        }
                        DecimalFormat df = new DecimalFormat(pattern.toString());
                        return df.format(bd.doubleValue());
                    };
                    case "toPrecision" -> (Invokable) args -> {
                        double d = value.doubleValue();
                        if (args.length == 0) {
                            // same as toString()
                            return Double.toString(d);
                        }
                        int precision = Terms.objectToNumber(args[0]).intValue();
                        if (precision < 1 || precision > 100) {
                            throw new RuntimeException("RangeError: precision must be between 1 and 100");
                        }
                        // Use BigDecimal for rounding
                        BigDecimal bd = new BigDecimal(d);
                        bd = bd.round(new java.math.MathContext(precision, RoundingMode.HALF_UP));
                        String result = bd.toString();
                        // Ensure formatting matches JS behavior
                        // JS switches between plain and exponential depending on magnitude
                        if (result.contains("E") || result.contains("e")) {
                            // Already in scientific notation, return as-is
                            return result.replace('E', 'e');
                        } else {
                            // If scientific notation is needed for very large/small values
                            if ((d != 0.0 && (Math.abs(d) < 1e-6 || Math.abs(d) >= 1e21))) {
                                return formatPrecision(d, precision);
                            }
                            return result;
                        }
                    };
                    case "toLocaleString" -> (Invokable) args -> {
                        double d = value.doubleValue();
                        DecimalFormat df = (DecimalFormat) DecimalFormat.getInstance();
                        int optionsIndex = args.length > 1 ? 1 : 0;
                        if (args.length > optionsIndex && args[optionsIndex] instanceof Map<?, ?> options) {
                            Object minFractionDigits = options.get("minimumFractionDigits");
                            Object maxFractionDigits = options.get("maximumFractionDigits");
                            if (minFractionDigits instanceof Number n) {
                                df.setMinimumFractionDigits(n.intValue());
                            }
                            if (maxFractionDigits instanceof Number n) {
                                df.setMaximumFractionDigits(n.intValue());
                            }
                        }
                        return df.format(d);
                    };
                    // static ==========================================================================================
                    case "valueOf" -> (Invokable) args -> value;
                    case "isFinite" -> (Invokable) args -> {
                        if (args.length > 0 && args[0] instanceof Number n) {
                            return Double.isFinite(n.doubleValue());
                        }
                        return false;
                    };
                    case "isInteger" -> (Invokable) args -> {
                        if (args.length > 0 && args[0] instanceof Number n) {
                            double d = n.doubleValue();
                            return Double.isFinite(d) && Math.floor(d) == d;
                        }
                        return false;
                    };
                    case "isNaN" -> (Invokable) args -> {
                        if (args.length > 0 && args[0] instanceof Number n) {
                            return Double.isNaN(n.doubleValue());
                        }
                        return false;
                    };
                    case "isSafeInteger" -> (Invokable) args -> {
                        if (args.length > 0 && args[0] instanceof Number n) {
                            double d = n.doubleValue();
                            if (!Double.isFinite(d)) {
                                return false;
                            }
                            long l = (long) d;
                            return (d == l) && (l >= MIN_SAFE_INTEGER) && (l <= MAX_SAFE_INTEGER);
                        }
                        return false;
                    };
                    case "EPSILON" -> Math.ulp(1.0);
                    case "MAX_VALUE" -> Double.MAX_VALUE;
                    case "MIN_VALUE" -> Double.MIN_VALUE;
                    case "MAX_SAFE_INTEGER" -> MAX_SAFE_INTEGER;
                    case "MIN_SAFE_INTEGER" -> MIN_SAFE_INTEGER;
                    case "POSITIVE_INFINITY" -> Double.POSITIVE_INFINITY;
                    case "NEGATIVE_INFINITY" -> Double.NEGATIVE_INFINITY;
                    case "NaN" -> Double.NaN;
                    default -> null;
                };
            }
        };
    }

    @SuppressWarnings("MalformedFormatString")
    private static String formatPrecision(double value, int precision) {
        return String.format("%." + (precision - 1) + "e", value);
    }

    @Override
    public Object call(Context context, Object... args) {
        Number temp = 0;
        if (args.length > 0) {
            temp = Terms.objectToNumber(args[0]);
        }
        CallInfo callInfo = context.getCallInfo();
        if (callInfo != null && callInfo.constructor) {
            return new JsNumber(temp);
        }
        return temp;
    }

}
