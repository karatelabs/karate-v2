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
package io.karatelabs.common;

import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.PathNotFoundException;
import net.minidev.json.JSONStyle;
import net.minidev.json.JSONValue;
import net.minidev.json.parser.JSONParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Node;

import java.util.*;
import java.util.regex.Pattern;

import static net.minidev.json.JSONValue.defaultReader;

public class Json {

    private static final Logger logger = LoggerFactory.getLogger(Json.class);

    private final DocumentContext doc;
    private final boolean array;
    private final boolean object;
    private final String prefix;

    private String prefix(String path) {
        return path.charAt(0) == '$' ? path : prefix + path;
    }

    public static Json object() {
        return Json.of("{}");
    }

    public static Json array() {
        return Json.of("[]");
    }

    public static Json of(Object any) {
        if (any instanceof String) {
            return new Json(JsonPath.parse((String) any));
        } else if (any instanceof List || any instanceof Map) {
            return new Json(JsonPath.parse(any));
        } else {
            String json = JSONValue.toJSONString(any);
            return new Json(JsonPath.parse(json));
        }
    }

    private Json(DocumentContext doc) {
        this.doc = doc;
        array = (doc.json() instanceof List);
        object = (doc.json() instanceof Map);
        prefix = array ? "$" : "$.";
    }

    public Json getJson(String path) {
        return Json.of(get(path, Object.class));
    }

    public <T> List<T> getAll(String prefix, List<String> paths) {
        List<T> res = new ArrayList<>();
        for (String path : paths) {
            res.add(doc.read(prefix(prefix + path)));
        }
        return res;
    }

    public <T> List<T> getAll(List<String> paths) {
        return Json.this.getAll("", paths);
    }

    public <T> T get(String path) {
        return doc.read(prefix(path));
    }

    @SuppressWarnings("unchecked")
    public <T> T get(String path, T defaultValue) {
        return (T) getOptional(path).orElse(defaultValue);
    }

    public <T> Optional<T> getOptional(String path) {
        try {
            return Optional.<T>of(get(path));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    public <T> T getFirst(String path) {
        List<T> list = get(path);
        if (list == null || list.isEmpty()) {
            return null;
        }
        return list.getFirst();
    }

    public <T> T getAs(String path, Class<T> clazz) {
        return doc.read(prefix(path), clazz);
    }

    @Override
    public String toString() {
        if (array || object) {
            return doc.jsonString();
        } else {
            return doc.json();
        }
    }

    public boolean isJson() {
        return array || object;
    }

    public boolean isArray() {
        return array;
    }

    public boolean isObject() {
        return object;
    }

    public <T> T value() {
        return doc.read("$");
    }

    public List<Object> asList() {
        return value();
    }

    public Map<String, Object> asMap() {
        return value();
    }

    public Json set(String path, Object value) {
        setInternal(path, value);
        return this;
    }

    public Json set(String path, String value) {
        if (value == null || value.isEmpty()) {
            setInternal(path, value);
        } else if (value.charAt(0) == '{' || value.charAt(0) == '[') {
            setInternal(path, Json.of(value).value());
        } else if (value.charAt(0) == '\\') {
            setInternal(path, value.substring(1));
        } else {
            setInternal(path, value);
        }
        return this;
    }

    public Json setAsString(String path, String value) {
        setInternal(path, value);
        return this;
    }

    public Json remove(String path) {
        doc.delete(prefix(path));
        return this;
    }

    private boolean isArrayPath(String s) {
        return s.endsWith("]") && !s.endsWith("']");
    }

    private String arrayKey(String s) {
        int pos = s.lastIndexOf('[');
        return s.substring(0, pos);
    }

    private int arrayIndex(String s) {
        int leftPos = s.lastIndexOf('[');
        if (leftPos == -1) {
            return -1;
        }
        int rightPos = s.indexOf(']', leftPos);
        String num = s.substring(leftPos + 1, rightPos);
        if (num.isEmpty()) {
            return -1;
        }
        try {
            return Integer.parseInt(num);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private void setInternal(String path, Object o) {
        path = prefix(path);
        if ("$".equals(path)) {
            throw new RuntimeException("cannot replace root path $");
        }
        boolean forArray = isArrayPath(path);
        if (!pathExists(path)) {
            createPath(path, forArray);
        }
        Pair<String> pair = toParentAndLeaf(path);
        if (forArray) {
            int index = arrayIndex(pair.right);
            if (index == -1) {
                doc.add(arrayKey(path), o);
            } else {
                doc.set(path, o);
            }
        } else {
            doc.put(pair.left, pair.right, o);
        }
    }

    public boolean pathExists(String path) {
        if (path.endsWith("[]")) {
            path = path.substring(0, path.length() - 2);
        }
        try {
            Object temp = doc.read(path);
            return temp != null;
        } catch (PathNotFoundException pnfe) {
            return false;
        }
    }

    private void createPath(String path, boolean array) {
        if (isArrayPath(path)) {
            String parentPath = arrayKey(path);
            if (!pathExists(parentPath)) {
                createPath(parentPath, true);
            }
            List<Object> list = get(parentPath);
            if (list == null) {
                list = new ArrayList<>();
                set(parentPath, list);
            }
            int index = arrayIndex(path);
            if (list.size() <= index) {
                for (int i = list.size(); i <= index; i++) {
                    list.add(null);
                }
            }
        } else {
            Pair<String> pair = toParentAndLeaf(path);
            if (!pathExists(pair.left)) {
                createPath(pair.left, false);
            }
            if (isArrayPath(pair.left)) {
                if (isArrayPath(pair.right)) {
                    doc.set(pair.left, new ArrayList<>());
                } else {
                    if (!pathExists(pair.left)) { // a necessary repetition
                        doc.set(pair.left, new LinkedHashMap<>());
                    }
                    doc.put(pair.left, pair.right, array ? new ArrayList<>() : new LinkedHashMap<>());
                }
            } else {
                doc.put(pair.left, pair.right, array ? new ArrayList<>() : new LinkedHashMap<>());
            }
        }
    }

    public static Pair<String> toParentAndLeaf(String path) {
        int pos = path.lastIndexOf('.');
        int temp = path.lastIndexOf("['");
        if (temp != -1 && temp > pos) {
            pos = temp - 1;
        }
        String right = path.substring(pos + 1);
        if (right.startsWith("[")) {
            pos = pos + 1;
        }
        String left = path.substring(0, pos == -1 ? 0 : pos);
        return Pair.of(left, right);
    }

    static final Pattern JS_IDENTIFIER_PATTERN = Pattern.compile("^[A-Za-z_$][A-Za-z0-9_$]*$");

    public static boolean isValidKey(String key) {
        return JS_IDENTIFIER_PATTERN.matcher(key).matches();
    }

    public static String escape(String raw) {
        return JSONValue.escape(raw, JSONStyle.LT_COMPRESS);
    }

    public static Json merge(Object... objects) {
        if (objects.length == 0) {
            return Json.object();
        }
        Json json = Json.of(objects[0]);
        if (objects.length == 1) {
            return json;
        }
        for (int i = 1; i < objects.length; i++) {
            json.merge(objects[i]);
        }
        return json;
    }

    public Json merge(Object other) {
        Json o = Json.of(other);
        if (isObject() && o.isObject()) {
            Map<String, Object> map = asMap();
            map.putAll(o.asMap());
        } else if (isArray() && o.isArray()) {
            List<Object> list = asList();
            list.addAll(o.asList());
        }
        return this;
    }

    public static String formatPretty(Object o) {
        return format(o, true, false, false);
    }

    public static String format(Object o, boolean pretty, boolean lenient, boolean sort) {
        if (o instanceof String ostring) {
            if (isJson(ostring)) {
                if (sort) { // dont care about order in first phase
                    o = JSONValue.parse(ostring);
                } else {
                    o = JSONValue.parseKeepingOrder(ostring);
                }
            } else {
                return ostring;
            }
        }
        if (o instanceof List || o instanceof Map) {
            StringBuilder sb = new StringBuilder();
            formatRecurse(o, pretty, lenient, sort, sb, 0);
            return sb.toString();
        } else {
            return o + "";
        }
    }

    public String toStringPretty() {
        return toString(true, false, false);
    }

    public String toString(boolean pretty, boolean lenient, boolean sort) {
        if (isJson()) {
            return format(value(), pretty, lenient, sort);
        } else {
            Object value = value();
            return value == null ? "" : value().toString();
        }
    }

    @SuppressWarnings("unchecked")
    private static void formatRecurse(Object o, boolean pretty, boolean lenient, boolean sort, StringBuilder sb, int depth) {
        if (o == null) {
            sb.append("null");
        } else if (o instanceof List<?> list) {
            sb.append('[');
            if (pretty) {
                sb.append('\n');
            } else if (!list.isEmpty()) {
                sb.append(' ');
            }
            Iterator<?> iterator = list.iterator();
            while (iterator.hasNext()) {
                Object child = iterator.next();
                if (pretty) {
                    pad(sb, depth + 1);
                }
                formatRecurse(child, pretty, lenient, sort, sb, depth + 1);
                if (iterator.hasNext()) {
                    sb.append(',');
                    if (!pretty) {
                        sb.append(' ');
                    }
                }
                if (pretty) {
                    sb.append('\n');
                }
            }
            if (pretty) {
                pad(sb, depth);
            }
            if (!pretty && !list.isEmpty()) {
                sb.append(' ');
            }
            sb.append(']');
        } else if (o instanceof Map) {
            // found a rare case where key was a boolean (not string)
            Map<Object, Object> map = (Map<Object, Object>) o;
            if (sort) {
                map = new TreeMap<>(map);
            }
            sb.append('{');
            if (pretty) {
                sb.append('\n');
            } else if (!map.isEmpty()) {
                sb.append(' ');
            }
            Iterator<Map.Entry<Object, Object>> iterator = map.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<Object, Object> entry = iterator.next();
                Object key = entry.getKey();
                if (pretty) {
                    pad(sb, depth + 1);
                }
                if (lenient) {
                    String escaped = escape(key == null ? null : key.toString());
                    if (escaped != null && isValidKey(escaped)) {
                        sb.append(key);
                    } else {
                        sb.append('\'').append(escaped).append('\'');
                    }
                } else {
                    sb.append('"').append(escape(key == null ? null : key.toString())).append('"');
                }
                sb.append(':').append(' ');
                formatRecurse(entry.getValue(), pretty, lenient, sort, sb, depth + 1);
                if (iterator.hasNext()) {
                    sb.append(',');
                    if (!pretty) {
                        sb.append(' ');
                    }
                }
                if (pretty) {
                    sb.append('\n');
                }
            }
            if (pretty) {
                pad(sb, depth);
            }
            if (!pretty && !map.isEmpty()) {
                sb.append(' ');
            }
            sb.append('}');
        } else if (o instanceof Number || o instanceof Boolean) {
            sb.append(o);
        } else {
            String value = o.toString();
            if (lenient) {
                sb.append('\'').append(escape(value)).append('\'');
            } else {
                sb.append('"').append(escape(value)).append('"');
            }
        }
    }

    private static void pad(StringBuilder sb, int depth) {
        for (int i = 0; i < depth; i++) {
            sb.append(' ').append(' ');
        }
    }

    public static boolean isJson(String s) {
        if (s == null || s.isEmpty()) {
            return false;
        }
        if (s.charAt(0) == ' ') {
            s = s.trim();
            if (s.isEmpty()) {
                return false;
            }
        }
        return s.charAt(0) == '{' || s.charAt(0) == '[';
    }

    @SuppressWarnings("unchecked")
    public static <T> T copy(T o, boolean deep) {
        if (!deep) {
            if (o instanceof List<?> list) {
                return (T) new ArrayList<>(list);
            } else if (o instanceof Map<?, ?> map) {
                return (T) new LinkedHashMap<>(map);
            } else {
                return o;
            }
        }
        // anti recursion / back-references
        Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        return (T) copyDeep(o, seen);
    }

    @SuppressWarnings("unchecked")
    private static Object copyDeep(Object o, Set<Object> seen) {
        if (o instanceof List) {
            List<Object> list = (List<Object>) o;
            if (seen.add(o)) {
                int count = list.size();
                List<Object> listCopy = new ArrayList<>(count);
                for (Object value : list) {
                    listCopy.add(copyDeep(value, seen));
                }
                return listCopy;
            } else {
                return o;
            }
        } else if (o instanceof Map) {
            if (seen.add(o)) {
                Map<String, Object> map = (Map<String, Object>) o;
                Map<String, Object> mapCopy = new LinkedHashMap<>(map.size());
                map.forEach((k, v) -> {
                    mapCopy.put(k, copyDeep(v, seen));
                });
                return mapCopy;
            } else {
                return o;
            }
        } else {
            return o;
        }
    }

    public static Object parseStrict(String json) {
        JSONParser parser = new JSONParser(JSONParser.MODE_RFC4627);
        try {
            return parser.parse(json.trim(), defaultReader.DEFAULT_ORDERED);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static byte[] toBytes(Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof Map || o instanceof List) {
            return FileUtils.toBytes(JSONValue.toJSONString(o));
        } else if (o instanceof Node) {
            return FileUtils.toBytes(Xml.toString((Node) o));
        } else if (o instanceof byte[]) {
            return (byte[]) o;
        } else {
            return FileUtils.toBytes(o.toString());
        }
    }

    public static String toJson(Object o) {
        if (o instanceof Map || o instanceof List) {
            return JSONValue.toJSONString(o);
        } else {
            return o == null ? "" : o.toString();
        }
    }

}

