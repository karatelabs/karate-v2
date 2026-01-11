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

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;

class JsString extends JsObject implements JsPrimitive {

    final String text;

    JsString() {
        this("");
    }

    JsString(String text) {
        this.text = text;
    }

    @Override
    public String toString() {
        return text;
    }

    @Override
    public Object getJavaValue() {
        return text;
    }

    @Override
    Prototype initPrototype() {
        Prototype wrapped = super.initPrototype();
        return new Prototype(wrapped) {
            @Override
            public Object getProperty(String propName) {
                return switch (propName) {
                    case "indexOf" -> (JsCallable) (context, args) -> {
                        String s = asString(context);
                        if (args.length > 1) {
                            return s.indexOf((String) args[0], ((Number) args[1]).intValue());
                        }
                        return s.indexOf((String) args[0]);
                    };
                    case "startsWith" -> (JsCallable) (context, args) -> {
                        String s = asString(context);
                        if (args.length > 1) {
                            return s.startsWith((String) args[0], ((Number) args[1]).intValue());
                        }
                        return s.startsWith((String) args[0]);
                    };
                    case "length" -> text.length();
                    // intentional deviation from js spec, to enable common string java api interop
                    case "getBytes" -> (JsCallable) (context, args) -> {
                        String s = asString(context);
                        return s.getBytes(StandardCharsets.UTF_8);
                    };
                    case "split" -> (JsCallable) (context, args) -> {
                        String s = asString(context);
                        return Arrays.asList(s.split((String) args[0]));
                    };
                    case "charAt" -> (JsCallable) (context, args) -> {
                        String s = asString(context);
                        int index = ((Number) args[0]).intValue();
                        if (index < 0 || index >= s.length()) {
                            return "";
                        }
                        return String.valueOf(s.charAt(index));
                    };
                    case "charCodeAt" -> (JsCallable) (context, args) -> {
                        String s = asString(context);
                        int index = ((Number) args[0]).intValue();
                        if (index < 0 || index >= s.length()) {
                            return Double.NaN;
                        }
                        return (int) s.charAt(index);
                    };
                    case "codePointAt" -> (JsCallable) (context, args) -> {
                        String s = asString(context);
                        int index = ((Number) args[0]).intValue();
                        if (index < 0 || index >= s.length()) {
                            return Terms.UNDEFINED;
                        }
                        return s.codePointAt(index);
                    };
                    case "concat" -> (JsCallable) (context, args) -> {
                        String s = asString(context);
                        StringBuilder sb = new StringBuilder(s);
                        for (Object arg : args) {
                            sb.append(arg);
                        }
                        return sb.toString();
                    };
                    case "endsWith" -> (JsCallable) (context, args) -> {
                        String s = asString(context);
                        if (args.length > 1) {
                            int endPosition = ((Number) args[1]).intValue();
                            return s.substring(0, Math.min(endPosition, s.length())).endsWith((String) args[0]);
                        }
                        return s.endsWith((String) args[0]);
                    };
                    case "includes" -> (JsCallable) (context, args) -> {
                        String s = asString(context);
                        String searchString = (String) args[0];
                        if (args.length > 1) {
                            int position = ((Number) args[1]).intValue();
                            return s.indexOf(searchString, position) >= 0;
                        }
                        return s.contains(searchString);
                    };
                    case "lastIndexOf" -> (JsCallable) (context, args) -> {
                        String s = asString(context);
                        if (args.length > 1) {
                            return s.lastIndexOf((String) args[0], ((Number) args[1]).intValue());
                        }
                        return s.lastIndexOf((String) args[0]);
                    };
                    case "padEnd" -> (JsCallable) (context, args) -> {
                        String s = asString(context);
                        int targetLength = ((Number) args[0]).intValue();
                        String padString = args.length > 1 ? (String) args[1] : " ";
                        if (padString.isEmpty()) {
                            padString = " ";
                        }
                        if (s.length() >= targetLength) {
                            return s;
                        }
                        int padLength = targetLength - s.length();
                        StringBuilder sb = new StringBuilder(s);
                        for (int i = 0; i < padLength; i++) {
                            sb.append(padString.charAt(i % padString.length()));
                        }
                        return sb.toString();
                    };
                    case "padStart" -> (JsCallable) (context, args) -> {
                        String s = asString(context);
                        int targetLength = ((Number) args[0]).intValue();
                        String padString = args.length > 1 ? (String) args[1] : " ";
                        if (padString.isEmpty()) {
                            padString = " ";
                        }
                        if (s.length() >= targetLength) {
                            return s;
                        }
                        int padLength = targetLength - s.length();
                        StringBuilder sb = new StringBuilder();
                        for (int i = 0; i < padLength; i++) {
                            sb.append(padString.charAt(i % padString.length()));
                        }
                        sb.append(s);
                        return sb.toString();
                    };
                    case "repeat" -> (JsCallable) (context, args) -> {
                        String s = asString(context);
                        int count = ((Number) args[0]).intValue();
                        if (count < 0) {
                            throw new RuntimeException("invalid count value");
                        }
                        return s.repeat(count);
                    };
                    case "slice" -> (JsCallable) (context, args) -> {
                        String s = asString(context);
                        int beginIndex = ((Number) args[0]).intValue();
                        int endIndex = args.length > 1 ? ((Number) args[1]).intValue() : s.length();
                        // handle negative indices
                        if (beginIndex < 0) beginIndex = Math.max(s.length() + beginIndex, 0);
                        if (endIndex < 0) endIndex = Math.max(s.length() + endIndex, 0);
                        // ensure proper range
                        beginIndex = Math.min(beginIndex, s.length());
                        endIndex = Math.min(endIndex, s.length());
                        if (beginIndex >= endIndex) return "";
                        return s.substring(beginIndex, endIndex);
                    };
                    case "substring" -> (JsCallable) (context, args) -> {
                        String s = asString(context);
                        int beginIndex = ((Number) args[0]).intValue();
                        int endIndex = args.length > 1 ? ((Number) args[1]).intValue() : s.length();
                        // ensure indices within bounds
                        beginIndex = Math.min(Math.max(beginIndex, 0), s.length());
                        endIndex = Math.min(Math.max(endIndex, 0), s.length());
                        // swap if beginIndex > endIndex (per JS spec)
                        if (beginIndex > endIndex) {
                            int temp = beginIndex;
                            beginIndex = endIndex;
                            endIndex = temp;
                        }
                        return s.substring(beginIndex, endIndex);
                    };
                    case "toLowerCase" -> (JsCallable) (context, args) -> asString(context).toLowerCase();
                    case "toUpperCase" -> (JsCallable) (context, args) -> asString(context).toUpperCase();
                    case "trim" -> (JsCallable) (context, args) -> asString(context).trim();
                    case "trimStart", "trimLeft" ->
                            (JsCallable) (context, args) -> asString(context).replaceAll("^\\s+", "");
                    case "trimEnd", "trimRight" ->
                            (JsCallable) (context, args) -> asString(context).replaceAll("\\s+$", "");
                    // regex
                    case "replace" -> (JsCallable) (context, args) -> {
                        String s = asString(context);
                        if (args[0] instanceof JsRegex regex) {
                            return regex.replace(s, (String) args[1]);
                        }
                        return s.replace((String) args[0], (String) args[1]);
                    };
                    case "replaceAll" -> (JsCallable) (context, args) -> {
                        String s = asString(context);
                        if (args[0] instanceof JsRegex regex) {
                            if (!regex.global) {
                                throw new RuntimeException("replaceAll requires regex with global flag");
                            }
                            return regex.replace(s, (String) args[1]);
                        }
                        return s.replaceAll((String) args[0], (String) args[1]);
                    };
                    case "match" -> (JsCallable) (context, args) -> {
                        String s = asString(context);
                        if (args.length == 0) {
                            return List.of("");
                        }
                        JsRegex regex;
                        if (args[0] instanceof JsRegex) {
                            regex = (JsRegex) args[0];
                        } else {
                            regex = new JsRegex(args[0].toString());
                        }
                        return regex.match(s);
                    };
                    case "search" -> (JsCallable) (context, args) -> {
                        String s = asString(context);
                        if (args.length == 0) {
                            return 0;
                        }
                        JsRegex regex;
                        if (args[0] instanceof JsRegex) {
                            regex = (JsRegex) args[0];
                        } else {
                            regex = new JsRegex(args[0].toString());
                        }
                        return regex.search(s);
                    };
                    case "valueOf" -> (JsCallable) (context, args) -> asString(context);
                    // static ==========================================================================================
                    case "fromCharCode" -> (JsInvokable) args -> {
                        StringBuilder sb = new StringBuilder();
                        for (Object arg : args) {
                            if (arg instanceof Number num) {
                                sb.append((char) num.intValue());
                            }
                        }
                        return sb.toString();
                    };
                    case "fromCodePoint" -> (JsInvokable) args -> {
                        StringBuilder sb = new StringBuilder();
                        for (Object arg : args) {
                            if (arg instanceof Number num) {
                                int n = num.intValue();
                                if (n < 0 || n > 0x10FFFF) {
                                    throw new RuntimeException("invalid code point: " + num);
                                }
                                sb.appendCodePoint(n);
                            }
                        }
                        return sb.toString();
                    };
                    default -> null;
                };
            }
        };
    }

    @Override
    public Iterable<KeyValue> jsEntries() {
        return () -> new Iterator<>() {
            int index = 0;

            @Override
            public boolean hasNext() {
                return index < text.length();
            }

            @Override
            public KeyValue next() {
                int i = index++;
                String c = String.valueOf(text.charAt(i));
                return new KeyValue(_this, i, i + "", c);
            }
        };
    }

    @Override
    JsString fromThis(Context context) {
        Object thisObject = context.getThisObject();
        if (thisObject instanceof JsString js) {
            return js;
        }
        if (thisObject instanceof String s) {
            return new JsString(s);
        }
        return this;
    }

    public String asString(Context context) {
        return fromThis(context).text;
    }

    @Override
    public Object call(Context context, Object... args) {
        String temp = "";
        if (args.length > 0 && args[0] != null) {
            temp = args[0].toString();
        }
        CallInfo callInfo = context.getCallInfo();
        if (callInfo != null && callInfo.constructor) {
            return new JsString(temp);
        }
        return temp;
    }

}
