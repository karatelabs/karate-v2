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

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static io.karatelabs.core.TestUtils.*;
import static org.junit.jupiter.api.Assertions.*;

class StepDefTest {

    @Test
    void testDefNumber() {
        ScenarioRuntime sr = run("""
            * def a = 1 + 2
            """);
        assertPassed(sr);
        assertEquals(3, get(sr, "a"));
    }

    @Test
    void testDefString() {
        ScenarioRuntime sr = run("""
            * def name = 'hello'
            """);
        assertPassed(sr);
        assertEquals("hello", get(sr, "name"));
    }

    @Test
    void testDefJson() {
        ScenarioRuntime sr = run("""
            * def foo = { name: 'bar' }
            """);
        assertPassed(sr);
        matchVar(sr, "foo", Map.of("name", "bar"));
    }

    @Test
    void testDefArray() {
        ScenarioRuntime sr = run("""
            * def arr = [1, 2, 3]
            """);
        assertPassed(sr);
        matchVar(sr, "arr", List.of(1, 2, 3));
    }

    @Test
    void testDefNestedJson() {
        ScenarioRuntime sr = run("""
            * def data = { user: { name: 'john', age: 30 } }
            """);
        assertPassed(sr);
        Object data = get(sr, "data");
        assertInstanceOf(Map.class, data);
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) data;
        @SuppressWarnings("unchecked")
        Map<String, Object> user = (Map<String, Object>) map.get("user");
        assertEquals("john", user.get("name"));
        assertEquals(30, user.get("age"));
    }

    @Test
    void testDefWithExpression() {
        ScenarioRuntime sr = run("""
            * def x = 10
            * def y = x * 2
            """);
        assertPassed(sr);
        assertEquals(20, get(sr, "y"));
    }

    @Test
    void testSetNested() {
        ScenarioRuntime sr = run("""
            * def foo = { a: 1 }
            * set foo.b = 2
            """);
        assertPassed(sr);
        matchVar(sr, "foo", Map.of("a", 1, "b", 2));
    }

    @Test
    void testSetArrayIndex() {
        ScenarioRuntime sr = run("""
            * def arr = [1, 2, 3]
            * set arr[1] = 99
            """);
        assertPassed(sr);
        matchVar(sr, "arr", List.of(1, 99, 3));
    }

    @Test
    void testRemove() {
        ScenarioRuntime sr = run("""
            * def foo = { a: 1, b: 2 }
            * remove foo.b
            """);
        assertPassed(sr);
        matchVar(sr, "foo", Map.of("a", 1));
    }

    @Test
    void testCopy() {
        ScenarioRuntime sr = run("""
            * def original = { a: 1, b: { c: 2 } }
            * copy clone = original
            * set clone.b.c = 99
            """);
        assertPassed(sr);
        // Original should be unchanged
        @SuppressWarnings("unchecked")
        Map<String, Object> original = (Map<String, Object>) get(sr, "original");
        @SuppressWarnings("unchecked")
        Map<String, Object> originalB = (Map<String, Object>) original.get("b");
        assertEquals(2, originalB.get("c"));

        // Clone should have new value
        @SuppressWarnings("unchecked")
        Map<String, Object> clone = (Map<String, Object>) get(sr, "clone");
        @SuppressWarnings("unchecked")
        Map<String, Object> cloneB = (Map<String, Object>) clone.get("b");
        assertEquals(99, cloneB.get("c"));
    }

    @Test
    void testText() {
        ScenarioRuntime sr = run("""
            * text myText =
            \"\"\"
            hello world
            this is multi-line
            \"\"\"
            """);
        assertPassed(sr);
        String text = (String) get(sr, "myText");
        assertTrue(text.contains("hello world"));
        assertTrue(text.contains("this is multi-line"));
    }

    @Test
    void testJson() {
        ScenarioRuntime sr = run("""
            * json myJson = { name: 'test', value: 123 }
            """);
        assertPassed(sr);
        matchVar(sr, "myJson", Map.of("name", "test", "value", 123));
    }

    @Test
    void testDefNull() {
        ScenarioRuntime sr = run("""
            * def x = null
            """);
        assertPassed(sr);
        assertNull(get(sr, "x"));
    }

    @Test
    void testDefBoolean() {
        ScenarioRuntime sr = run("""
            * def t = true
            * def f = false
            """);
        assertPassed(sr);
        assertEquals(true, get(sr, "t"));
        assertEquals(false, get(sr, "f"));
    }

    @Test
    void testReplace() {
        // Replace <token> with value in a string variable
        ScenarioRuntime sr = run("""
            * def text = 'hello <name> world'
            * replace text.name = 'foo'
            * match text == 'hello foo world'
            """);
        assertPassed(sr);
        assertEquals("hello foo world", get(sr, "text"));
    }

    @Test
    void testReplaceMultipleTokens() {
        ScenarioRuntime sr = run("""
            * def text = '<greeting> <name>!'
            * replace text.greeting = 'Hello'
            * replace text.name = 'World'
            * match text == 'Hello World!'
            """);
        assertPassed(sr);
    }

    @Test
    void testEvalWithDocString() {
        // eval keyword with docstring - multi-line JS with karate.set()
        ScenarioRuntime sr = run("""
            * eval
            \"\"\"
            var foo = function(v){ return v * v };
            var nums = [0, 1, 2, 3, 4];
            var squares = [];
            for (var n in nums) {
              squares.push(foo(n));
            }
            karate.set('temp', squares);
            \"\"\"
            * match temp == [0, 1, 4, 9, 16]
            """);
        assertPassed(sr);
        matchVar(sr, "temp", List.of(0, 1, 4, 9, 16));
    }

    @Test
    void testEvalInline() {
        // eval with inline expression (no docstring)
        ScenarioRuntime sr = run("""
            * eval karate.set('x', 42)
            """);
        assertPassed(sr);
        assertEquals(42, get(sr, "x"));
    }

    @Test
    void testPropertyAssignmentWithDocString() {
        // Property assignment with docstring value (e.g., foo.bar = """json""")
        ScenarioRuntime sr = run("""
            * def foo = { bar: 'one' }
            * foo.bar =
            \"\"\"
            {
              some: 'big',
              message: 'content'
            }
            \"\"\"
            * match foo == { bar: { some: 'big', message: 'content' } }
            """);
        assertPassed(sr);
    }

}
