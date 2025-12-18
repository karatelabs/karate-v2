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
 * Tests for JavaScript integration with Gherkin steps.
 * Based on V1's js-arrays.feature.
 */
class StepJsTest {

    // ========== JS Arrays in Match ==========

    @Test
    void testArraysReturnedFromJsCanBeUsedInMatch() {
        ScenarioRuntime sr = run("""
            * def fun = function(){ return ['foo', 'bar', 'baz'] }
            * def json = ['foo', 'bar', 'baz']
            * match json == fun()
            * def expected = fun()
            * match json == expected
            """);
        assertPassed(sr);
    }

    @Test
    void testArraysReturnedFromJsCanBeModifiedUsingSet() {
        ScenarioRuntime sr = run("""
            * def fun = function(){ return [{a: 1}, {a: 2}, {b: 3}] }
            * def json = fun()
            * set json[1].a = 5
            * match json == [{a: 1}, {a: 5}, {b: 3}]
            """);
        assertPassed(sr);
    }

    @Test
    void testNestedArraysConvertCorrectly() {
        ScenarioRuntime sr = run("""
            * def actual = ({ a: [1, 2, 3] })
            * match actual == { a: [1, 2, 3] }
            """);
        assertPassed(sr);
    }

    // ========== Object Comparison ==========

    @Test
    void testComparingTwoPayloads() {
        ScenarioRuntime sr = run("""
            * def foo = { hello: 'world', baz: 'ban' }
            * def bar = { baz: 'ban', hello: 'world' }
            * match foo == bar
            """);
        assertPassed(sr);
    }

    @Test
    void testContainsDeepNestedJson() {
        ScenarioRuntime sr = run("""
            * def original = { a: 1, b: 2, c: 3, d: { a: 1, b: 2 } }
            * def expected = { a: 1, c: 3, d: { b: 2 } }
            * match original !contains expected
            * match original contains deep expected
            """);
        assertPassed(sr);
    }

    @Test
    void testContainsDeepNestedArray() {
        ScenarioRuntime sr = run("""
            * def original = { a: 1, arr: [ { b: 2, c: 3 }, { b: 3, c: 4 } ] }
            * def expected = { a: 1, arr: [ { b: 2 }, { c: 4 } ] }
            * match original !contains expected
            * match original contains deep expected
            """);
        assertPassed(sr);
    }

    // ========== Optional JSON Values ==========

    @Test
    void testOptionalJsonValues() {
        ScenarioRuntime sr = run("""
            * def response = [{a: 'one', b: 'two'}, { a: 'one' }]
            * match each response contains { a: 'one', b: '##string' }
            """);
        assertPassed(sr);
    }

    // ========== Null / NotPresent Checks ==========

    @Test
    void testNullAndNotPresentChecks() {
        ScenarioRuntime sr = run("""
            * def foo = { }
            * match foo != { a: '#present' }
            * match foo == { a: '#notpresent' }
            * match foo == { a: '#ignore' }
            * match foo == { a: '##null' }
            * match foo != { a: '#null' }
            * match foo != { a: '#notnull' }
            * match foo == { a: '##notnull' }
            * match foo != { a: null }
            """);
        assertPassed(sr);
    }

    @Test
    void testNullValueChecks() {
        ScenarioRuntime sr = run("""
            * def foo = { a: null }
            * match foo == { a: null }
            * match foo == { a: '#null' }
            * match foo == { a: '##null' }
            * match foo != { a: '#notnull' }
            * match foo == { a: '##notnull' }
            * match foo == { a: '#present' }
            * match foo == { a: '#ignore' }
            * match foo != { a: '#notpresent' }
            """);
        assertPassed(sr);
    }

    @Test
    void testPresentValueChecks() {
        ScenarioRuntime sr = run("""
            * def foo = { a: 1 }
            * match foo == { a: 1 }
            * match foo == { a: '#number' }
            * match foo == { a: '#notnull' }
            * match foo == { a: '##notnull' }
            * match foo != { a: '#null' }
            * match foo != { a: '##null' }
            * match foo == { a: '#present' }
            * match foo == { a: '#ignore' }
            * match foo != { a: '#notpresent' }
            """);
        assertPassed(sr);
    }

    // ========== Table Keyword ==========

    @Test
    void testTableToJsonWithExpressions() {
        ScenarioRuntime sr = run("""
            * def one = 'hello'
            * def two = { baz: 'world' }
            * table json
                | foo     | bar |
                | one     | 1   |
                | two.baz | 2   |
            * match json == [{ foo: 'hello', bar: 1 }, { foo: 'world', bar: 2 }]
            """);
        assertPassed(sr);
    }

    // TODO: testTableToJsonWithEmptyAndNulls - needs table parsing for empty cells and (null)

    @Test
    void testTableToJsonWithNestedJson() {
        ScenarioRuntime sr = run("""
            * def one = 'hello'
            * def two = { baz: 'world' }
            * table json
                | foo     | bar            |
                | one     | { baz: 1 }     |
                | two.baz | ['baz', 'ban'] |
                | true    | one == 'hello' |
            * match json == [{ foo: 'hello', bar: { baz: 1 } }, { foo: 'world', bar: ['baz', 'ban'] }, { foo: true, bar: true }]
            """);
        assertPassed(sr);
    }

    // ========== JS Data Type Strictness ==========

    @Test
    void testJsMatchIsStrictForDataTypes() {
        ScenarioRuntime sr = run("""
            * def foo = { a: '5', b: 5, c: true, d: 'true' }
            * match foo !contains { a: 5 }
            * match foo !contains { b: '5' }
            * match foo !contains { c: 'true' }
            * match foo !contains { d: true }
            * match foo == { a: '5', b: 5, c: true, d: 'true' }
            """);
        assertPassed(sr);
    }

    // ========== JSON Keys with Special Characters ==========

    @Test
    void testJsonKeysWithSpecialCharacters() {
        ScenarioRuntime sr = run("""
            * def json = { 'hy-phen': 'bar', 'full.stop': 'baz' }
            * match json['hy-phen'] == 'bar'
            * match json['full.stop'] == 'baz'
            """);
        assertPassed(sr);
    }

    // TODO: testGetKeywordWithSpecialPath - needs 'get' keyword with $['key'] syntax
    // TODO: testStringTypeConversion - needs 'string' keyword for type conversion

    // ========== karate.match() API ==========

    @Test
    void testKarateMatchTwoArgs() {
        ScenarioRuntime sr = run("""
            * def foo = { hello: 'world' }
            * def res = karate.match(foo, { hello: '#string' })
            * match res.pass == true
            """);
        assertPassed(sr);
    }

    @Test
    void testKarateMatchStringExpression() {
        ScenarioRuntime sr = run("""
            * def foo = { a: 1, b: 'x' }
            * def res = karate.match("foo contains { a: '#number' }")
            * match res.pass == true
            """);
        assertPassed(sr);
    }

    @Test
    void testKarateMatchEachExpression() {
        ScenarioRuntime sr = run("""
            * def foo = [1, 2, 3]
            * def res = karate.match("each foo == '#number'")
            * match res.pass == true
            """);
        assertPassed(sr);
    }

    @Test
    void testKarateMatchQuotedOperator() {
        // Edge case: operator inside quoted string
        ScenarioRuntime sr = run("""
            * def foo = 'hello == world'
            * def res = karate.match(foo, 'hello == world')
            * match res.pass == true
            """);
        assertPassed(sr);
    }

    @Test
    void testGlobalMatchFluent() {
        // Global match() returns Value for fluent API
        ScenarioRuntime sr = run("""
            * def foo = { name: 'test' }
            * def res = match(foo).contains({ name: '#string' })
            * match res.pass == true
            """);
        assertPassed(sr);
    }

}
