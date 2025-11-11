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

import io.karatelabs.common.StringUtils;
import net.minidev.json.JSONValue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class JsJson implements SimpleObject {

    @Override
    public Object jsGet(String name) {
        return switch (name) {
            case "stringify" -> stringify();
            case "parse" -> parse();
            default -> throw new RuntimeException("no such api on JSON: " + name);
        };
    }

    @SuppressWarnings("unchecked")
    Invokable stringify() {
        return args -> {
            Object value = args[0];
            Object replacer = args.length > 1 ? args[1] : null;
            Object space = args.length > 2 ? args[2] : null;

            // Handle replacer (array of keys to include)
            if (replacer instanceof List) {
                List<String> list = (List<String>) replacer;
                if (value instanceof Map) {
                    Map<String, Object> map = (Map<String, Object>) value;
                    Map<String, Object> result = new LinkedHashMap<>();
                    for (String k : list) {
                        if (map.containsKey(k)) {
                            result.put(k, map.get(k));
                        }
                    }
                    value = result;
                }
            }

            // Handle space parameter for pretty printing
            boolean pretty = false;
            String indentStr = "  ";

            if (space != null) {
                if (space instanceof Number) {
                    int indent = Math.min(((Number) space).intValue(), 10);
                    if (indent > 0) {
                        pretty = true;
                        indentStr = " ".repeat(indent);
                    }
                } else if (space instanceof String spaceStr) {
                    if (!spaceStr.isEmpty()) {
                        pretty = true;
                        indentStr = spaceStr.substring(0, Math.min(spaceStr.length(), 10));
                    }
                }
            }

            // For compact output (no space parameter), use JSONValue for strict compact formatting
            if (!pretty) {
                return JSONValue.toJSONString(value);
            }

            // Use centralized StringUtils.formatJson for pretty printing
            // lenient=false for strict JSON (double quotes), sort=false to preserve order
            return StringUtils.formatJson(value, true, false, false, indentStr);
        };
    }

    Invokable parse() {
        return args -> JSONValue.parse((String) args[0]);
    }

}
