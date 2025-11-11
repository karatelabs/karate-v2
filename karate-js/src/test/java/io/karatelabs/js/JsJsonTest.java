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

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JsJsonTest extends EvalBase {

    @Test
    void testStringifyBasic() {
        assertEquals("{\"a\":\"b\"}", eval("JSON.stringify({a:'b'})"));
    }

    @Test
    void testStringifyWithReplacerArray() {
        assertEquals("{\"a\":\"b\"}", eval("JSON.stringify({a:'b',c:'d'}, ['a'])"));
    }

    @Test
    void testStringifyWithNullReplacerAndSpace() {
        String result = (String) eval("JSON.stringify({a:'b',c:'d'}, null, 2)");
        String expected = "{\n  \"a\": \"b\",\n  \"c\": \"d\"\n}";
        assertEquals(expected, result);
    }

    @Test
    void testStringifyWithSpaceString() {
        String result = (String) eval("JSON.stringify({a:'b'}, null, '  ')");
        String expected = "{\n  \"a\": \"b\"\n}";
        assertEquals(expected, result);
    }

    @Test
    void testStringifyWithSpaceNumber() {
        String result = (String) eval("JSON.stringify({a:'b',c:{d:'e'}}, null, 4)");
        String expected = "{\n    \"a\": \"b\",\n    \"c\": {\n        \"d\": \"e\"\n    }\n}";
        assertEquals(expected, result);
    }

    @Test
    void testStringifyNestedObject() {
        String result = (String) eval("JSON.stringify({a:{b:{c:'d'}}}, null, 2)");
        String expected = "{\n  \"a\": {\n    \"b\": {\n      \"c\": \"d\"\n    }\n  }\n}";
        assertEquals(expected, result);
    }

    @Test
    void testStringifyWithArray() {
        String result = (String) eval("JSON.stringify({a:[1,2,3]}, null, 2)");
        String expected = "{\n  \"a\": [\n    1,\n    2,\n    3\n  ]\n}";
        assertEquals(expected, result);
    }

    @Test
    void testParse() {
        assertEquals(Map.of("a", "b"), eval("JSON.parse('{\"a\":\"b\"}')"));
    }

    @SuppressWarnings("unchecked")
    @Test
    void testParseComplex() {
        Map<String, Object> result = (Map<String, Object>) eval("JSON.parse('{\"a\":\"b\",\"c\":{\"d\":\"e\"}}')");
        assertEquals("b", result.get("a"));
        Map<String, Object> nested = (Map<String, Object>) result.get("c");
        assertEquals("e", nested.get("d"));
    }
}
