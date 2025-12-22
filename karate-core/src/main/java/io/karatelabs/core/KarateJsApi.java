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
package io.karatelabs.core;

import io.karatelabs.common.DataUtils;
import io.karatelabs.common.Json;
import io.karatelabs.common.StringUtils;
import io.karatelabs.common.Xml;
import io.karatelabs.js.Invokable;
import io.karatelabs.js.JsCallable;
import io.karatelabs.js.SimpleObject;
import org.w3c.dom.Node;

import com.jayway.jsonpath.JsonPath;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Stateless utility methods for the karate.* API.
 * These methods don't require engine state or providers.
 */
public class KarateJsApi implements SimpleObject {

    @Override
    public Object jsGet(String key) {
        return switch (key) {
            case "append" -> append();
            case "appendTo" -> appendTo();
            case "distinct" -> distinct();
            case "extract" -> extract();
            case "extractAll" -> extractAll();
            case "filter" -> filter();
            case "filterKeys" -> filterKeys();
            case "forEach" -> forEach();
            case "fromJson" -> fromJson();
            case "jsonPath" -> jsonPath();
            case "keysOf" -> keysOf();
            case "lowerCase" -> lowerCase();
            case "map" -> map();
            case "mapWithKey" -> mapWithKey();
            case "merge" -> merge();
            case "pretty" -> pretty();
            case "pause" -> pause();
            case "prettyXml" -> prettyXml();
            case "range" -> range();
            case "repeat" -> repeat();
            case "sizeOf" -> sizeOf();
            case "sort" -> sort();
            case "toBean" -> toBean();
            case "toBytes" -> toBytes();
            case "toCsv" -> toCsv();
            case "toJson" -> toJson();
            case "toString" -> toStringValue();
            case "typeOf" -> typeOf();
            case "urlDecode" -> urlDecode();
            case "urlEncode" -> urlEncode();
            case "valuesOf" -> valuesOf();
            default -> null;
        };
    }

    // ========== Collection Utilities ==========

    @SuppressWarnings("unchecked")
    private Invokable append() {
        return args -> {
            if (args.length < 2) {
                throw new RuntimeException("append() needs at least two arguments");
            }
            List<Object> result = new ArrayList<>();
            Object first = args[0];
            if (first instanceof List) {
                result.addAll((List<?>) first);
            } else {
                result.add(first);
            }
            for (int i = 1; i < args.length; i++) {
                Object item = args[i];
                if (item instanceof List) {
                    result.addAll((List<?>) item);
                } else {
                    result.add(item);
                }
            }
            return result;
        };
    }

    @SuppressWarnings("unchecked")
    private Invokable appendTo() {
        return args -> {
            if (args.length < 2) {
                throw new RuntimeException("appendTo() needs at least two arguments: list and item(s)");
            }
            List<Object> list = (List<Object>) args[0];
            for (int i = 1; i < args.length; i++) {
                Object item = args[i];
                if (item instanceof List) {
                    list.addAll((List<?>) item);
                } else {
                    list.add(item);
                }
            }
            return list;
        };
    }

    /**
     * Remove duplicates from a list while preserving order.
     * Usage: karate.distinct([1, 2, 2, 3, 1]) => [1, 2, 3]
     */
    private Invokable distinct() {
        return args -> {
            if (args.length == 0 || args[0] == null) {
                return new ArrayList<>();
            }
            if (!(args[0] instanceof List)) {
                return new ArrayList<>();
            }
            List<?> list = (List<?>) args[0];
            Set<Object> seen = new LinkedHashSet<>(list);
            return new ArrayList<>(seen);
        };
    }

    private Invokable extract() {
        return args -> {
            if (args.length < 3) {
                throw new RuntimeException("extract() needs three arguments: text, regex, group");
            }
            String text = args[0].toString();
            String regex = args[1].toString();
            int group = ((Number) args[2]).intValue();
            Pattern pattern = Pattern.compile(regex);
            Matcher matcher = pattern.matcher(text);
            if (!matcher.find()) {
                return null;
            }
            return matcher.group(group);
        };
    }

    private Invokable extractAll() {
        return args -> {
            if (args.length < 3) {
                throw new RuntimeException("extractAll() needs three arguments: text, regex, group");
            }
            String text = args[0].toString();
            String regex = args[1].toString();
            int group = ((Number) args[2]).intValue();
            Pattern pattern = Pattern.compile(regex);
            Matcher matcher = pattern.matcher(text);
            List<String> list = new ArrayList<>();
            while (matcher.find()) {
                list.add(matcher.group(group));
            }
            return list;
        };
    }

    @SuppressWarnings("unchecked")
    private Invokable filter() {
        return args -> {
            if (args.length < 2) {
                throw new RuntimeException("filter() needs two arguments: list and function");
            }
            List<?> list = (List<?>) args[0];
            JsCallable fn = (JsCallable) args[1];
            List<Object> result = new ArrayList<>();
            for (int i = 0; i < list.size(); i++) {
                Object item = list.get(i);
                Object keep = fn.call(null, new Object[]{item, i});
                if (Boolean.TRUE.equals(keep)) {
                    result.add(item);
                }
            }
            return result;
        };
    }

    @SuppressWarnings("unchecked")
    private Invokable filterKeys() {
        return args -> {
            if (args.length < 2) {
                throw new RuntimeException("filterKeys() needs at least two arguments");
            }
            Map<String, Object> source = (Map<String, Object>) args[0];
            Map<String, Object> result = new LinkedHashMap<>();

            if (args[1] instanceof Map) {
                // filterKeys(source, keysFromMap) - filter by keys present in the map
                Map<String, Object> keysMap = (Map<String, Object>) args[1];
                for (String key : keysMap.keySet()) {
                    if (source.containsKey(key)) {
                        result.put(key, source.get(key));
                    }
                }
            } else if (args[1] instanceof List) {
                // filterKeys(source, [key1, key2, ...])
                List<String> keys = (List<String>) args[1];
                for (String key : keys) {
                    if (source.containsKey(key)) {
                        result.put(key, source.get(key));
                    }
                }
            } else {
                // filterKeys(source, key1, key2, ...)
                for (int i = 1; i < args.length; i++) {
                    String key = args[i].toString();
                    if (source.containsKey(key)) {
                        result.put(key, source.get(key));
                    }
                }
            }
            return result;
        };
    }

    @SuppressWarnings("unchecked")
    private Invokable forEach() {
        return args -> {
            if (args.length < 2) {
                throw new RuntimeException("forEach() needs two arguments: collection and function");
            }
            Object collection = args[0];
            JsCallable fn = (JsCallable) args[1];
            if (collection instanceof List) {
                List<?> list = (List<?>) collection;
                for (int i = 0; i < list.size(); i++) {
                    fn.call(null, new Object[]{list.get(i), i});
                }
            } else if (collection instanceof Map) {
                Map<String, Object> map = (Map<String, Object>) collection;
                int i = 0;
                for (Map.Entry<String, Object> entry : map.entrySet()) {
                    fn.call(null, new Object[]{entry.getKey(), entry.getValue(), i++});
                }
            }
            return null;
        };
    }

    private Invokable fromJson() {
        return args -> {
            if (args.length < 1) {
                throw new RuntimeException("fromJson() needs one argument: a JSON string");
            }
            Object arg = args[0];
            if (arg instanceof String str) {
                return Json.of(str).value();
            } else {
                // Already parsed, just return it
                return arg;
            }
        };
    }

    private Invokable jsonPath() {
        return args -> {
            if (args.length < 2) {
                throw new RuntimeException("jsonPath() needs two arguments: object and path");
            }
            Object json = args[0];
            String path = args[1].toString();
            return JsonPath.read(json, path);
        };
    }

    @SuppressWarnings("unchecked")
    private Invokable keysOf() {
        return args -> {
            if (args.length == 0) {
                throw new RuntimeException("keysOf() needs one argument");
            }
            Map<String, Object> map = (Map<String, Object>) args[0];
            return new ArrayList<>(map.keySet());
        };
    }

    private Invokable lowerCase() {
        return args -> {
            if (args.length == 0) {
                throw new RuntimeException("lowerCase() needs one argument");
            }
            Object obj = args[0];
            if (obj instanceof String) {
                return ((String) obj).toLowerCase();
            } else if (obj instanceof Map) {
                // Convert to JSON string, lowercase, parse back
                String json = Json.stringifyStrict(obj).toLowerCase();
                return Json.of(json).value();
            } else if (obj instanceof List) {
                String json = Json.stringifyStrict(obj).toLowerCase();
                return Json.of(json).value();
            } else if (obj instanceof Node) {
                String xml = Xml.toString((Node) obj, false).toLowerCase();
                return Xml.toXmlDoc(xml);
            }
            return obj;
        };
    }

    @SuppressWarnings("unchecked")
    private Invokable map() {
        return args -> {
            if (args.length < 2) {
                throw new RuntimeException("map() needs two arguments: list and function");
            }
            List<?> list = (List<?>) args[0];
            JsCallable fn = (JsCallable) args[1];
            List<Object> result = new ArrayList<>();
            for (int i = 0; i < list.size(); i++) {
                result.add(fn.call(null, new Object[]{list.get(i), i}));
            }
            return result;
        };
    }

    private Invokable mapWithKey() {
        return args -> {
            if (args.length < 2) {
                throw new RuntimeException("mapWithKey() needs two arguments: list and key name");
            }
            Object listArg = args[0];
            if (listArg == null) {
                return new ArrayList<>();
            }
            List<?> list = (List<?>) listArg;
            String key = args[1].toString();
            List<Map<String, Object>> result = new ArrayList<>();
            for (Object item : list) {
                Map<String, Object> map = new LinkedHashMap<>();
                map.put(key, item);
                result.add(map);
            }
            return result;
        };
    }

    @SuppressWarnings("unchecked")
    private Invokable merge() {
        return args -> {
            Map<String, Object> result = new LinkedHashMap<>();
            for (Object arg : args) {
                if (arg instanceof Map) {
                    result.putAll((Map<String, Object>) arg);
                }
            }
            return result;
        };
    }

    private Invokable pause() {
        return args -> {
            if (args.length == 0 || args[0] == null) {
                return null;
            }
            long millis = ((Number) args[0]).longValue();
            try {
                Thread.sleep(millis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return null;
        };
    }

    private Invokable pretty() {
        return args -> {
            if (args.length == 0) {
                throw new RuntimeException("pretty() needs one argument");
            }
            Object obj = args[0];
            if (obj instanceof Map || obj instanceof List) {
                return StringUtils.formatJson(obj);
            } else if (obj instanceof Node) {
                return Xml.toString((Node) obj, true);
            } else {
                return obj != null ? obj.toString() : "null";
            }
        };
    }

    /**
     * Generate a range of integers.
     * Usage: karate.range(0, 5) => [0, 1, 2, 3, 4]
     *        karate.range(0, 10, 2) => [0, 2, 4, 6, 8]
     *        karate.range(5, 0, -1) => [5, 4, 3, 2, 1]
     */
    private Invokable range() {
        return args -> {
            if (args.length < 2) {
                throw new RuntimeException("range() needs at least two arguments: start and end");
            }
            int start = ((Number) args[0]).intValue();
            int end = ((Number) args[1]).intValue();
            int step = args.length > 2 ? ((Number) args[2]).intValue() : 1;
            List<Integer> result = new ArrayList<>();
            if (step > 0) {
                for (int i = start; i < end; i += step) {
                    result.add(i);
                }
            } else if (step < 0) {
                for (int i = start; i > end; i += step) {
                    result.add(i);
                }
            }
            return result;
        };
    }

    private Invokable prettyXml() {
        return args -> {
            if (args.length == 0) {
                throw new RuntimeException("prettyXml() needs one argument");
            }
            Object obj = args[0];
            if (obj instanceof Node) {
                return Xml.toString((Node) obj, true);
            } else if (obj instanceof String) {
                return Xml.toString(Xml.toXmlDoc((String) obj), true);
            } else {
                throw new RuntimeException("prettyXml() argument must be XML node or string");
            }
        };
    }

    private Invokable repeat() {
        return args -> {
            if (args.length < 2) {
                throw new RuntimeException("repeat() needs two arguments: count and function");
            }
            int count = ((Number) args[0]).intValue();
            JsCallable fn = (JsCallable) args[1];
            List<Object> result = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                result.add(fn.call(null, new Object[]{i}));
            }
            return result;
        };
    }

    private Invokable sizeOf() {
        return args -> {
            if (args.length == 0) {
                throw new RuntimeException("sizeOf() needs one argument");
            }
            Object obj = args[0];
            if (obj instanceof List) {
                return ((List<?>) obj).size();
            } else if (obj instanceof Map) {
                return ((Map<?, ?>) obj).size();
            } else if (obj instanceof String) {
                return ((String) obj).length();
            }
            return 0;
        };
    }

    @SuppressWarnings("unchecked")
    private Invokable sort() {
        return args -> {
            if (args.length < 2) {
                throw new RuntimeException("sort() needs two arguments: list and key function");
            }
            List<?> list = (List<?>) args[0];
            JsCallable fn = (JsCallable) args[1];
            List<Object> result = new ArrayList<>(list);
            result.sort((a, b) -> {
                Object keyA = fn.call(null, new Object[]{a});
                Object keyB = fn.call(null, new Object[]{b});
                if (keyA instanceof Comparable && keyB instanceof Comparable) {
                    return ((Comparable<Object>) keyA).compareTo(keyB);
                }
                return 0;
            });
            return result;
        };
    }

    private Invokable toBean() {
        return args -> {
            if (args.length < 2) {
                throw new RuntimeException("toBean() needs two arguments: object and class name");
            }
            Object obj = args[0];
            String className = args[1].toString();
            // Convert to JSON string and deserialize to the target class
            String jsonString = Json.of(obj).toString();
            return Json.fromJson(jsonString, className);
        };
    }

    /**
     * Convert a list of maps to CSV string.
     * Usage: karate.toCsv([{a:1,b:2},{a:3,b:4}]) => "a,b\n1,2\n3,4\n"
     */
    @SuppressWarnings("unchecked")
    private Invokable toCsv() {
        return args -> {
            if (args.length == 0 || args[0] == null) {
                return "";
            }
            if (!(args[0] instanceof List)) {
                throw new RuntimeException("toCsv() argument must be a list of maps, got: " + args[0].getClass().getName());
            }
            List<Map<String, Object>> list = (List<Map<String, Object>>) args[0];
            if (list.isEmpty()) {
                return "";
            }
            return DataUtils.toCsv(list);
        };
    }

    private Invokable toBytes() {
        return args -> {
            if (args.length < 1) {
                throw new RuntimeException("toBytes() needs one argument: a list of numbers");
            }
            Object arg = args[0];
            if (arg instanceof byte[]) {
                return arg; // already bytes
            }
            if (!(arg instanceof List)) {
                throw new RuntimeException("toBytes() argument must be a list of numbers, got: " + arg.getClass().getName());
            }
            List<Object> list = (List<Object>) arg;
            byte[] bytes = new byte[list.size()];
            for (int i = 0; i < list.size(); i++) {
                Object item = list.get(i);
                if (item instanceof Number num) {
                    bytes[i] = num.byteValue();
                } else {
                    throw new RuntimeException("toBytes() list must contain only numbers, got: " + item.getClass().getName() + " at index " + i);
                }
            }
            return bytes;
        };
    }

    private Invokable toJson() {
        return args -> {
            if (args.length < 1) {
                throw new RuntimeException("toJson() needs at least one argument");
            }
            Object obj = args[0];
            boolean removeNulls = args.length > 1 && Boolean.TRUE.equals(args[1]);
            Object result = Json.of(obj).value();
            if (removeNulls) {
                removeNullValues(result);
            }
            return result;
        };
    }

    @SuppressWarnings("unchecked")
    private void removeNullValues(Object obj) {
        if (obj instanceof Map) {
            Map<String, Object> map = (Map<String, Object>) obj;
            map.entrySet().removeIf(e -> e.getValue() == null);
            map.values().forEach(this::removeNullValues);
        } else if (obj instanceof List) {
            ((List<?>) obj).forEach(this::removeNullValues);
        }
    }

    /**
     * Convert value to its string representation.
     * - JSON (Map/List) -> compact JSON string
     * - XML (Node) -> XML string
     * - byte[] -> string from bytes
     * - Others -> toString()
     */
    private JsCallable toStringValue() {
        return (thisRef, args) -> {
            if (args.length == 0 || args[0] == null) {
                return null;
            }
            Object value = args[0];
            if (value instanceof Map || value instanceof List) {
                return Json.of(value).toString();
            } else if (value instanceof Node) {
                return Xml.toString((Node) value, false);
            } else if (value instanceof byte[] bytes) {
                return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
            }
            return value.toString();
        };
    }

    /**
     * Returns the Karate type of a value.
     */
    private Invokable typeOf() {
        return args -> {
            if (args.length == 0 || args[0] == null) {
                return "null";
            }
            Object value = args[0];
            if (value instanceof Boolean) {
                return "boolean";
            } else if (value instanceof Number) {
                return "number";
            } else if (value instanceof String) {
                return "string";
            } else if (value instanceof byte[]) {
                return "bytes";
            } else if (value instanceof List) {
                return "list";
            } else if (value instanceof Map) {
                return "map";
            } else if (value instanceof Node) {
                return "xml";
            } else if (value instanceof JsCallable || value instanceof Invokable) {
                return "function";
            }
            return "object";
        };
    }

    private Invokable urlEncode() {
        return args -> {
            if (args.length == 0 || args[0] == null) {
                return "";
            }
            return URLEncoder.encode(args[0].toString(), StandardCharsets.UTF_8);
        };
    }

    private Invokable urlDecode() {
        return args -> {
            if (args.length == 0 || args[0] == null) {
                return "";
            }
            return URLDecoder.decode(args[0].toString(), StandardCharsets.UTF_8);
        };
    }

    @SuppressWarnings("unchecked")
    private Invokable valuesOf() {
        return args -> {
            if (args.length == 0) {
                throw new RuntimeException("valuesOf() needs one argument");
            }
            Object obj = args[0];
            if (obj instanceof Map) {
                return new ArrayList<>(((Map<String, Object>) obj).values());
            } else if (obj instanceof List) {
                return new ArrayList<>((List<?>) obj);
            }
            return new ArrayList<>();
        };
    }

}
