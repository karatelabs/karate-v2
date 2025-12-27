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

import static io.karatelabs.core.TestUtils.*;

/**
 * Tests for match keyword.
 */
class StepMatchTest {

    // ========== Basic Equality ==========

    @Test
    void testMatchEquals() {
        ScenarioRuntime sr = run("""
            * def foo = { name: 'bar' }
            * match foo == { name: 'bar' }
            """);
        assertPassed(sr);
    }

    @Test
    void testMatchEqualsFailure() {
        ScenarioRuntime sr = run("""
            * def foo = { name: 'bar' }
            * match foo == { name: 'baz' }
            """);
        assertFailed(sr);
    }

    @Test
    void testMatchNotEquals() {
        ScenarioRuntime sr = run("""
            * def foo = { name: 'bar' }
            * match foo != { name: 'baz' }
            """);
        assertPassed(sr);
    }

    @Test
    void testMatchNotEqualsFailure() {
        ScenarioRuntime sr = run("""
            * def foo = { name: 'bar' }
            * match foo != { name: 'bar' }
            """);
        assertFailed(sr);
    }

    // ========== Match with Variable Reference ==========

    @Test
    void testMatchWithVariableReference() {
        ScenarioRuntime sr = run("""
            * def expected = { name: 'test' }
            * def actual = { name: 'test' }
            * match actual == expected
            """);
        assertPassed(sr);
    }

    // ========== Match Primitives ==========

    @Test
    void testMatchNumber() {
        ScenarioRuntime sr = run("""
            * def x = 42
            * match x == 42
            """);
        assertPassed(sr);
    }

    @Test
    void testMatchString() {
        ScenarioRuntime sr = run("""
            * def s = 'hello'
            * match s == 'hello'
            """);
        assertPassed(sr);
    }

    @Test
    void testMatchBoolean() {
        ScenarioRuntime sr = run("""
            * def b = true
            * match b == true
            """);
        assertPassed(sr);
    }

    @Test
    void testMatchNull() {
        ScenarioRuntime sr = run("""
            * def n = null
            * match n == null
            """);
        assertPassed(sr);
    }

    // ========== Match with Path ==========

    @Test
    void testMatchWithPath() {
        ScenarioRuntime sr = run("""
            * def data = { user: { name: 'john' } }
            * match data.user.name == 'john'
            """);
        assertPassed(sr);
    }

    // ========== Match Arrays ==========

    @Test
    void testMatchArray() {
        ScenarioRuntime sr = run("""
            * def arr = [1, 2, 3]
            * match arr == [1, 2, 3]
            """);
        assertPassed(sr);
    }

    @Test
    void testMatchNestedJson() {
        ScenarioRuntime sr = run("""
            * def data = { items: [{ id: 1 }, { id: 2 }] }
            * match data == { items: [{ id: 1 }, { id: 2 }] }
            """);
        assertPassed(sr);
    }

    // ========== Match with Expressions ==========

    @Test
    void testMatchWithExpression() {
        ScenarioRuntime sr = run("""
            * def x = 5
            * def y = 10
            * match x + y == 15
            """);
        assertPassed(sr);
    }

    // ========== Contains ==========

    @Test
    void testMatchContains() {
        ScenarioRuntime sr = run("""
            * def foo = { name: 'bar', age: 30 }
            * match foo contains { name: 'bar' }
            """);
        assertPassed(sr);
    }

    @Test
    void testMatchContainsFailure() {
        ScenarioRuntime sr = run("""
            * def foo = { name: 'bar' }
            * match foo contains { name: 'baz' }
            """);
        assertFailed(sr);
    }

    @Test
    void testMatchNotContains() {
        ScenarioRuntime sr = run("""
            * def foo = { name: 'bar' }
            * match foo !contains { name: 'baz' }
            """);
        assertPassed(sr);
    }

    @Test
    void testMatchNotContainsFailure() {
        ScenarioRuntime sr = run("""
            * def foo = { name: 'bar' }
            * match foo !contains { name: 'bar' }
            """);
        assertFailed(sr);
    }

    @Test
    void testMatchContainsArray() {
        ScenarioRuntime sr = run("""
            * def arr = [1, 2, 3]
            * match arr contains 2
            """);
        assertPassed(sr);
    }

    @Test
    void testMatchContainsOnly() {
        ScenarioRuntime sr = run("""
            * def arr = [1, 2, 3]
            * match arr contains only [3, 2, 1]
            """);
        assertPassed(sr);
    }

    @Test
    void testMatchContainsAny() {
        ScenarioRuntime sr = run("""
            * def arr = [1, 2, 3]
            * match arr contains any [5, 2, 7]
            """);
        assertPassed(sr);
    }

    // ========== Match Each ==========

    @Test
    void testMatchEach() {
        ScenarioRuntime sr = run("""
            * def arr = [{ id: 1 }, { id: 2 }]
            * match each arr contains { id: '#number' }
            """);
        assertPassed(sr);
    }

    // ========== Within ==========

    @Test
    void testMatchWithinArray() {
        ScenarioRuntime sr = run("""
            * def subset = [1, 2]
            * def superset = [1, 2, 3, 4, 5]
            * match subset within superset
            """);
        assertPassed(sr);
    }

    @Test
    void testMatchWithinArrayFailure() {
        ScenarioRuntime sr = run("""
            * def subset = [1, 6]
            * def superset = [1, 2, 3, 4, 5]
            * match subset within superset
            """);
        assertFailed(sr);
    }

    @Test
    void testMatchWithinMap() {
        ScenarioRuntime sr = run("""
            * def subset = { name: 'bar' }
            * def superset = { name: 'bar', age: 30, city: 'NYC' }
            * match subset within superset
            """);
        assertPassed(sr);
    }

    @Test
    void testMatchWithinMapFailure() {
        ScenarioRuntime sr = run("""
            * def subset = { name: 'baz' }
            * def superset = { name: 'bar', age: 30 }
            * match subset within superset
            """);
        assertFailed(sr);
    }

    @Test
    void testMatchNotWithin() {
        ScenarioRuntime sr = run("""
            * def subset = [7, 8]
            * def superset = [1, 2, 3]
            * match subset !within superset
            """);
        assertPassed(sr);
    }

    @Test
    void testMatchNotWithinFailure() {
        ScenarioRuntime sr = run("""
            * def subset = [1, 2]
            * def superset = [1, 2, 3]
            * match subset !within superset
            """);
        assertFailed(sr);
    }

    // ========== Match Each Empty Allowed ==========

    @Test
    void testMatchEachEmptyArrayFails() {
        ScenarioRuntime sr = run("""
            * def items = []
            * match each items == '#string'
            """);
        assertFailed(sr);
    }

    @Test
    void testMatchEachEmptyArrayPassesWithConfig() {
        ScenarioRuntime sr = run("""
            * configure matchEachEmptyAllowed = true
            * def items = []
            * match each items == '#string'
            """);
        assertPassed(sr);
    }

    @Test
    void testMatchEachEmptyAllowedWithSchema() {
        ScenarioRuntime sr = run("""
            * configure matchEachEmptyAllowed = true
            * def schema = { id: '#number' }
            * def response = []
            * match each response == schema
            """);
        assertPassed(sr);
    }

}
