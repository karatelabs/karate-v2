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

import io.karatelabs.js.JsCallable;
import io.karatelabs.js.ObjectLike;
import io.karatelabs.js.SimpleObject;
import net.minidev.json.JSONStyle;
import net.minidev.json.JSONValue;

import java.io.*;
import java.security.SecureRandom;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class StringUtils {

    private StringUtils() {
        // only static methods
    }

    public static final String EMPTY = "";

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

    public static boolean isXml(String s) {
        if (s == null || s.isEmpty()) {
            return false;
        }
        if (s.charAt(0) == ' ') {
            s = s.trim();
            if (s.isEmpty()) {
                return false;
            }
        }
        return s.charAt(0) == '<';
    }

    public static String truncate(String s, int length, boolean addDots) {
        if (s == null) {
            return EMPTY;
        }
        if (s.length() > length) {
            return addDots ? s.substring(0, length) + " ..." : s.substring(0, length);
        }
        return s;
    }

    public static String trimToEmpty(String s) {
        if (s == null) {
            return EMPTY;
        } else {
            return s.trim();
        }
    }

    public static String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String temp = trimToEmpty(s);
        return EMPTY.equals(temp) ? null : temp;
    }

    public static String repeat(char c, int count) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count; i++) {
            sb.append(c);
        }
        return sb.toString();
    }

    public static String join(Object[] a, char delimiter) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < a.length; i++) {
            sb.append(a[i]);
            if (i != a.length - 1) {
                sb.append(delimiter);
            }
        }
        return sb.toString();
    }

    public static String join(Collection<String> c, String delimiter) {
        if (c == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        Iterator<String> iterator = c.iterator();
        while (iterator.hasNext()) {
            sb.append(iterator.next());
            if (iterator.hasNext()) {
                sb.append(delimiter);
            }
        }
        return sb.toString();
    }

    public static List<String> split(String s, char delimiter, boolean skipBackSlash) {
        int pos = s.indexOf(delimiter);
        if (pos == -1) {
            return Collections.singletonList(s);
        }
        List<String> list = new ArrayList<>();
        int startPos = 0;
        int searchPos = 0;
        while (pos != -1) {
            if (skipBackSlash && pos > 0 && s.charAt(pos - 1) == '\\') {
                s = s.substring(0, pos - 1) + s.substring(pos);
                searchPos = pos;
            } else {
                String temp = s.substring(startPos, pos);
                if (!EMPTY.equals(temp)) {
                    list.add(temp);
                }
                startPos = pos + 1;
                searchPos = startPos;
            }
            pos = s.indexOf(delimiter, searchPos);
        }
        if (startPos != s.length()) {
            String temp = s.substring(startPos);
            list.add(temp);
        }
        return list;
    }

    public static boolean isBlank(String s) {
        return trimToNull(s) == null;
    }

    public static String toIdString(String name) {
        if (name == null) {
            return "";
        }
        return name.replaceAll("[\\s_\\\\/:<>\"\\|\\?\\*]", "-").toLowerCase();
    }

    public static Pair<String> splitByFirstLineFeed(String text) {
        String left = "";
        String right = "";
        if (text != null) {
            int pos = text.indexOf('\n');
            if (pos != -1) {
                left = text.substring(0, pos).trim();
                right = text.substring(pos).trim();
            } else {
                left = text.trim();
            }
        }
        return Pair.of(left, right);
    }

    public static List<String> toStringLines(String text) {
        return new BufferedReader(new StringReader(text)).lines().collect(Collectors.toList());
    }

    public static int countLineFeeds(String text) {
        int count = 0;
        for (char c : text.toCharArray()) {
            if (c == '\n') {
                count++;
            }
        }
        return count;
    }

    public static int wrappedLinesEstimate(String text, int colWidth) {
        List<String> lines = toStringLines(text);
        int estimate = 0;
        for (String s : lines) {
            int wrapEstimate = (int) Math.ceil((double) s.length() / colWidth);
            if (wrapEstimate == 0) {
                estimate++;
            } else {
                estimate += wrapEstimate;
            }
        }
        return estimate;
    }

    public static boolean containsIgnoreCase(List<String> list, String str) {
        for (String i : list) {
            if (i.equalsIgnoreCase(str)) {
                return true;
            }
        }
        return false;
    }

    public static <T> T getIgnoreKeyCase(Map<String, T> map, String name) {
        if (map == null || name == null) {
            return null;
        }
        for (Map.Entry<String, T> entry : map.entrySet()) {
            String key = entry.getKey();
            if (name.equalsIgnoreCase(key)) {
                return entry.getValue();
            }
        }
        return null;
    }

    public static void removeIgnoreKeyCase(Map<String, ?> map, String name) {
        if (map == null || name == null) {
            return;
        }
        for (String key : map.keySet()) {
            if (name.equalsIgnoreCase(key)) {
                map.remove(key);
                return;
            }
        }
    }

    public static Map<String, Object> simplify(Map<String, List<String>> map, boolean always) {
        if (map == null) {
            return Collections.emptyMap();
        }
        Map<String, Object> result = new LinkedHashMap<>(map.size());
        for (Map.Entry<String, List<String>> entry : map.entrySet()) {
            String key = entry.getKey();
            List<String> values = entry.getValue();
            if (values == null || values.isEmpty()) {
                continue;
            }
            if (values.size() > 1) {
                if (always) {
                    result.put(key, StringUtils.join(values, ","));
                } else {
                    result.put(key, values);
                }
            } else {
                Object value = values.getFirst();
                if (value != null) {
                    result.put(key, value + "");
                }
            }
        }
        return result;
    }

    public static String throwableToString(Throwable t) {
        try (final StringWriter sw = new StringWriter();
             final PrintWriter pw = new PrintWriter(sw, true)) {
            t.printStackTrace(pw);
            return sw.toString();
        } catch (IOException e) {
            return null;
        }
    }

    private static final Pattern CLI_ARG = Pattern.compile("'([^']*)'[^\\S]|\"([^\"]*)\"[^\\S]|(\\S+)");

    public static String[] tokenizeCliCommand(String command) {
        List<String> args = new ArrayList<>();
        Matcher m = CLI_ARG.matcher(command + " ");
        while (m.find()) {
            if (m.group(1) != null) {
                args.add(m.group(1));
            } else if (m.group(2) != null) {
                args.add(m.group(2));
            } else {
                args.add(m.group(3));
            }
        }
        return args.toArray(new String[0]);
    }

    static final String CHARS = "abcdefghijklmnopqrstuvwxyz0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ";
    static final int CHAR_LENGTH = CHARS.length();
    static SecureRandom RANDOM = new SecureRandom();

    public static String randomAlphaNumeric(int len) {
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++)
            sb.append(CHARS.charAt(RANDOM.nextInt(CHAR_LENGTH)));
        return sb.toString();
    }

    public static String formatFileSize(int bytes) {
        if (bytes < 1024) {
            return bytes + " bytes";
        } else if (bytes < 1024 * 1024) {
            return String.format("%.1f KB", bytes / 1024.0);
        } else {
            return String.format("%.1f MB", bytes / (1024.0 * 1024));
        }
    }

    public static String formatJson(Object o) {
        return formatJson(o, true, false, false);
    }

    public static String formatJson(Object o, boolean pretty, boolean lenient, boolean sort) {
        if (o instanceof String ostring) {
            if (StringUtils.isJson(ostring)) {
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
                    String escaped = escapeJsonValue(key == null ? null : key.toString());
                    if (escaped != null && isValidJsonKey(escaped)) {
                        sb.append(key);
                    } else {
                        sb.append('\'').append(escaped).append('\'');
                    }
                } else {
                    sb.append('"').append(escapeJsonValue(key == null ? null : key.toString())).append('"');
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
        } else if (o instanceof SimpleObject so) {
            JsCallable jsc = so.jsToString();
            String s = (String) jsc.call(null);
            sb.append(s);
        } else {
            String value = o.toString();
            if (lenient) {
                sb.append('\'').append(escapeJsonValue(value)).append('\'');
            } else {
                sb.append('"').append(escapeJsonValue(value)).append('"');
            }
        }
    }

    private static void pad(StringBuilder sb, int depth) {
        for (int i = 0; i < depth; i++) {
            sb.append(' ').append(' ');
        }
    }

    private static final Pattern JS_IDENTIFIER_PATTERN = Pattern.compile("^[A-Za-z_$][A-Za-z0-9_$]*$");

    private static boolean isValidJsonKey(String key) {
        return JS_IDENTIFIER_PATTERN.matcher(key).matches();
    }

    private static String escapeJsonValue(String raw) {
        return JSONValue.escape(raw, JSONStyle.LT_COMPRESS);
    }

}
