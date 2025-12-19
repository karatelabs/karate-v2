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

    // ========== karate.jsonPath / karate.get ==========

    @Test
    void testKarateJsonPath() {
        ScenarioRuntime sr = run("""
            * def json = [{foo: 1}, {foo: 2}]
            * def fun = function(arg) { return karate.jsonPath(arg, '$[*].foo') }
            * def res = call fun json
            * match res == [1, 2]
            """);
        assertPassed(sr);
    }

    @Test
    void testKarateGetWithJsonPath() {
        ScenarioRuntime sr = run("""
            * def foo = { bar: [{baz: 1}, {baz: 2}, {baz: 3}]}
            * def fun = function(){ return karate.get('$foo.bar[*].baz') }
            * def res = call fun
            * match res == [1, 2, 3]
            """);
        assertPassed(sr);
    }

    @Test
    void testKarateSetWithJsonPath() {
        ScenarioRuntime sr = run("""
            * def json = { foo: [] }
            * karate.set('json', '$.foo[]', { bar: 'baz' })
            * match json == { foo: [{ bar: 'baz' }] }
            """);
        assertPassed(sr);
    }

    // ========== karate.forEach / map / filter ==========

    @Test
    void testKarateForEachOnList() {
        ScenarioRuntime sr = run("""
            * def res = []
            * def fun = function(x){ karate.appendTo(res, x * x) }
            * def list = [1, 2, 3]
            * karate.forEach(list, fun)
            * match res == [1, 4, 9]
            """);
        assertPassed(sr);
    }

    @Test
    void testKarateForEachOnMap() {
        ScenarioRuntime sr = run("""
            * def keys = []
            * def vals = []
            * def idxs = []
            * def fun =
            \"\"\"
            function(x, y, i) {
              karate.appendTo(keys, x);
              karate.appendTo(vals, y);
              karate.appendTo(idxs, i);
            }
            \"\"\"
            * def map = { a: 2, b: 4, c: 6 }
            * karate.forEach(map, fun)
            * match keys == ['a', 'b', 'c']
            * match vals == [2, 4, 6]
            * match idxs == [0, 1, 2]
            """);
        assertPassed(sr);
    }

    @Test
    void testKarateForEachWithArguments() {
        ScenarioRuntime sr = run("""
            * def vals = []
            * def fun = function(){ karate.forEach(arguments, function(x){ vals.push(x) }) }
            * fun('a', 'b', 'c')
            * match vals == ['a', 'b', 'c']
            """);
        assertPassed(sr);
    }

    @Test
    void testKarateMap() {
        ScenarioRuntime sr = run("""
            * def fun = function(x){ return x * x }
            * def list = [1, 2, 3]
            * def res = karate.map(list, fun)
            * match res == [1, 4, 9]
            """);
        assertPassed(sr);
    }

    @Test
    void testKarateMapTransformShape() {
        ScenarioRuntime sr = run("""
            * def before = [{ foo: 1 }, { foo: 2 }, { foo: 3 }]
            * def fun = function(x){ return { bar: x.foo } }
            * def after = karate.map(before, fun)
            * match after == [{ bar: 1 }, { bar: 2 }, { bar: 3 }]
            """);
        assertPassed(sr);
    }

    @Test
    void testKarateFilter() {
        ScenarioRuntime sr = run("""
            * def fun = function(x){ return x % 2 == 0 }
            * def list = [1, 2, 3, 4]
            * def res = karate.filter(list, fun)
            * match res == [2, 4]
            """);
        assertPassed(sr);
    }

    @Test
    void testKarateFilterWithIndex() {
        ScenarioRuntime sr = run("""
            * def fun = function(x, i){ return i % 2 == 0 }
            * def list = [1, 2, 3, 4]
            * def res = karate.filter(list, fun)
            * match res == [1, 3]
            """);
        assertPassed(sr);
    }

    @Test
    void testKarateMapWithKey() {
        ScenarioRuntime sr = run("""
            * def list = [ 'Bob', 'Wild', 'Nyan' ]
            * def data = karate.mapWithKey(list, 'name')
            * match data == [{ name: 'Bob' }, { name: 'Wild' }, { name: 'Nyan' }]

            * def list = [ 1, 2, 3]
            * def data = karate.mapWithKey(list, 'val')
            * match data == [{ val: 1 }, { val: 2 }, { val: 3 }]

            * def list = [{ a: 1 }, { b: 2 }]
            * def data = karate.mapWithKey(list, 'foo')
            * match data == [{ foo: { a: 1 } }, { foo: { b: 2 } }]

            * def list = null
            * def data = karate.mapWithKey(list, 'foo')
            * match data == []
            """);
        assertPassed(sr);
    }

    @Test
    void testKarateFilterKeys() {
        ScenarioRuntime sr = run("""
            * def schema = { a: '#string', b: '#number', c: '#boolean' }
            * def response = { a: 'x', c: true }
            * match response == karate.filterKeys(schema, response)
            * match karate.filterKeys(response, 'b', 'c') == { c: true }
            * match karate.filterKeys(response, ['a', 'b']) == { a: 'x' }
            """);
        assertPassed(sr);
    }

    // ========== karate.merge / append / sort ==========

    @Test
    void testKarateMerge() {
        ScenarioRuntime sr = run("""
            * def foo = { a: 1 }
            * def bar = karate.merge(foo, { b: 2 })
            * match bar == { a: 1, b: 2 }
            """);
        assertPassed(sr);
    }

    @Test
    void testKarateAppend() {
        ScenarioRuntime sr = run("""
            * def foo = [{ a: 1 }]
            * def bar = karate.append(foo, { b: 2 })
            * match bar == [{ a: 1 }, { b: 2 }]
            * def foo = { a: 1 }
            * def bar = karate.append(foo, { b: 2})
            * match bar == [{ a: 1 }, { b: 2 }]
            * def fun = function(){ var x = [1, 2]; return karate.append(x, 3, 4) }
            * match fun() == [1, 2, 3, 4]
            """);
        assertPassed(sr);
    }

    @Test
    void testKarateSort() {
        ScenarioRuntime sr = run("""
            * def foo = [{a: { b: 3 }}, {a: { b: 1 }}, {a: { b: 2 }}]
            * def fun = function(x){ return x.a.b }
            * def bar = karate.sort(foo, fun)
            * match bar == [{a: { b: 1 }}, {a: { b: 2 }}, {a: { b: 3 }}]
            * match bar.reverse() == [{a: { b: 3 }}, {a: { b: 2 }}, {a: { b: 1 }}]
            """);
        assertPassed(sr);
    }

    @Test
    void testKarateSizeOfKeysOfValuesOf() {
        ScenarioRuntime sr = run("""
            * def foo = [1, 2, 3]
            * match karate.sizeOf(foo) == 3
            * match karate.valuesOf(foo) == [1, 2, 3]
            * def bar = karate.appendTo(foo, 4)
            * match foo == [1, 2, 3, 4]
            * match bar == [1, 2, 3, 4]
            * def bar = karate.appendTo(foo, [5, 6])
            * match foo == [1, 2, 3, 4, 5, 6]

            * def baz = { a: 1, b: 2, c: 3 }
            * match karate.sizeOf(baz) == 3
            * match karate.keysOf(baz) == ['a', 'b', 'c']
            """);
        assertPassed(sr);
    }

    @Test
    void testKarateMapRepeatWithArrays() {
        ScenarioRuntime sr = run("""
            * def foo = [1, 2]
            * def fun = function(x){ return { x: x, bar: [1, 2] } }
            * def res = karate.map(foo, fun)
            * match res == [{ x: 1, bar: [1, 2]}, { x: 2, bar: [1, 2] }]

            * def fun = function(i){ return { foo: [1, 2]} }
            * def bar = karate.repeat(2, fun)
            * match bar == [{ foo: [1, 2] }, { foo: [1, 2] }]
            """);
        assertPassed(sr);
    }

    // ========== karate.eval ==========

    @Test
    void testKarateEval() {
        ScenarioRuntime sr = run("""
            * def temperature = { celsius: 100, fahrenheit: 212 }
            * string expression = 'temperature.celsius'
            * def celsius = karate.eval(expression)
            * assert celsius == 100
            * string expression = 'temperature.celsius * 1.8 + 32'
            * match temperature.fahrenheit == karate.eval(expression)
            """);
        assertPassed(sr);
    }

    // ========== String Keyword ==========

    @Test
    void testStringKeyword() {
        ScenarioRuntime sr = run("""
            * def json = { 'sp ace': 'foo', 'hy-phen': 'bar', 'full.stop': 'baz' }
            * string jsonString = json
            * match jsonString == '{"sp ace":"foo","hy-phen":"bar","full.stop":"baz"}'
            """);
        assertPassed(sr);
    }

    // ========== Get Keyword ==========

    @Test
    void testGetWithSpecialJsonPath() {
        ScenarioRuntime sr = run("""
            * def json = { 'sp ace': 'foo', 'hy-phen': 'bar', 'full.stop': 'baz' }
            * def val1 = get json $['sp ace']
            * match val1 == 'foo'
            * match json['hy-phen'] == 'bar'
            * match json['full.stop'] == 'baz'
            """);
        assertPassed(sr);
    }

    @Test
    void testGetLastArrayElement() {
        ScenarioRuntime sr = run("""
            * def list = [1, 2, 3, 4]
            * def last = list[list.length-1]
            * match last == 4
            """);
        assertPassed(sr);
    }

    @Test
    void testGetLastArrayElementJsonPath() {
        ScenarioRuntime sr = run("""
            * def list = [1, 2, 3, 4]
            * def last = get[0] list[-1:]
            * match last == 4
            """);
        assertPassed(sr);
    }

    @Test
    void testGetCombinedWithArrayIndex() {
        ScenarioRuntime sr = run("""
            * def foo = [{a: 1, b: 'x'}, {a: 2, b: 'y'}]
            * def first = get[0] foo[*].a
            * match first == 1
            * match first == get[0] foo[*].a
            """);
        assertPassed(sr);
    }

    @Test
    void testJsonPathInExpressions() {
        ScenarioRuntime sr = run("""
            * table data
                | a | b   |
                | 1 | 'x' |
                | 2 | 'y' |
            * def foo = [{a: 1, b: 'x'}, {a: 2, b: 'y'}]
            * match data == foo
            * match foo == data
            * match foo[*].a == [1, 2]
            * match foo[*].a == $data[*].a
            * match foo[*].b == $data[*].b
            """);
        assertPassed(sr);
    }

    // ========== Remove Keyword ==========

    @Test
    void testRemoveJsonKeyWithJsonPath() {
        ScenarioRuntime sr = run("""
            * def json = { foo: 'bar', hello: 'world' }
            * remove json $.foo
            * match json == { hello: 'world' }
            """);
        assertPassed(sr);
    }

    @Test
    void testRemoveJsonKeyFromJs() {
        ScenarioRuntime sr = run("""
            * def json = { foo: 'bar', hello: 'world' }
            * def fun = function(){ karate.remove('json', 'foo') }
            * call fun
            * match json == { hello: 'world' }
            """);
        assertPassed(sr);
    }

    // ========== Embedded Expressions ==========

    @Test
    void testEmbeddedExpressionsOptionalRemove() {
        // ##() removes key if expression evaluates to null
        ScenarioRuntime sr = run("""
            * def data = { a: 'hello', b: null, c: null }
            * def json = { foo: '#(data.a)', bar: '#(data.b)', baz: '##(data.c)' }
            * match json == { foo: 'hello', bar: null }
            """);
        assertPassed(sr);
    }

    // ========== Not Present Check ==========

    @Test
    void testAlternativeNotPresentCheck() {
        ScenarioRuntime sr = run("""
            * def foo = { a: 1 }
            * match foo.a == '#present'
            * match foo.nope == '#notpresent'
            """);
        assertPassed(sr);
    }

    @Test
    void testFuzzyMatchEdgeCase() {
        ScenarioRuntime sr = run("""
            * def answer = { foo: 'foo', bar: 'bar', baz: 'baz' }
            * match answer != { foo: '#string', foobar: '#notpresent', foobaz: '#notpresent' }
            * match answer != { foo: '#string', foobar: '##string', foobaz: '##string' }
            """);
        assertPassed(sr);
    }

    // ========== Contains with Embedded Expression ==========

    @Test
    void testContainsWithEmbeddedExpression() {
        ScenarioRuntime sr = run("""
            * def some = [1, 2]
            * def actual = [1, 2, 3]
            * def none = [4, 5]
            * match actual contains some
            * match actual == '#(^some)'
            * match actual !contains none
            * match actual == '#(!^none)'
            """);
        assertPassed(sr);
    }

    // ========== JS includes API ==========

    @Test
    void testJsIncludesApi() {
        ScenarioRuntime sr = run("""
            * def allowed = ['Music', 'Entertainment', 'Documentaries', 'Family']
            * def actual = ['Entertainment', 'Family']
            * match each actual == '#? allowed.includes(_)'
            """);
        assertPassed(sr);
    }

    @Test
    void testJavaIndexOf() {
        ScenarioRuntime sr = run("""
            * def response = [{ name: 'a' }, { name: 'b' }, { name: 'c' }]
            * def names = $[*].name
            * def index = names.indexOf('b')
            * match index == 1
            """);
        assertPassed(sr);
    }

    // ========== Contains Deep with DocString ==========

    @Test
    void testContainsDeepWithDocString() {
        ScenarioRuntime sr = run("""
            * def message =
              \"\"\"
              {
                  order_id: 5,
                  products: [
                    { product_id: 100, name: "bicycle" },
                    { product_id: 101, name: "car" }
                  ]
              }
              \"\"\"
            * match message contains deep
              \"\"\"
              {
                  order_id: 5,
                  products: [
                    { product_id: 101, name: "car" },
                    { product_id: 100, name: "bicycle" }
                  ]
              }
              \"\"\"
            """);
        assertPassed(sr);
    }

    // ========== Pass JSON to Function ==========

    @Test
    void testPassJsonToFunction() {
        ScenarioRuntime sr = run("""
            * def json = { foo: 'bar', hello: 'world' }
            * def fun = function(o){ return o }
            * def result = call fun json
            * match result == json
            """);
        assertPassed(sr);
    }

    // ========== Find Index Using forEach ==========

    @Test
    void testFindIndexPrimitive() {
        ScenarioRuntime sr = run("""
            * def list = [1, 2, 3, 4]
            * def searchFor = 3
            * def foundAt = []
            * def fun = function(x, i){ if (x == searchFor) karate.appendTo(foundAt, i) }
            * karate.forEach(list, fun)
            * match foundAt == [2]
            """);
        assertPassed(sr);
    }

    @Test
    void testFindIndexComplex() {
        ScenarioRuntime sr = run("""
            * def list = [{ a: 1, b: 'x'}, { a: 2, b: 'y'}, { a: 3, b: 'z'}]
            * def searchFor = { a: 2, b: '#string'}
            * def foundAt = []
            * def fun = function(x, i){ if (karate.match(x, searchFor).pass) karate.appendTo(foundAt, i) }
            * karate.forEach(list, fun)
            * match foundAt == [1]
            """);
        assertPassed(sr);
    }

    // ========== karate.lowerCase() ==========

    @Test
    void testLowerCaseJson() {
        ScenarioRuntime sr = run("""
            * def json = { FOO: 'BAR', Hello: 'World' }
            * def json = karate.lowerCase(json)
            * match json == { foo: 'bar', hello: 'world' }
            """);
        assertPassed(sr);
    }

    @Test
    void testLowerCaseString() {
        ScenarioRuntime sr = run("""
            * def str = 'HELLO World'
            * def result = karate.lowerCase(str)
            * match result == 'hello world'
            """);
        assertPassed(sr);
    }

    @Test
    void testLowerCaseList() {
        ScenarioRuntime sr = run("""
            * def list = ['FOO', 'BAR']
            * def result = karate.lowerCase(list)
            * match result == ['foo', 'bar']
            """);
        assertPassed(sr);
    }

    // ========== karate.pretty() ==========

    @Test
    void testPrettyJson() {
        ScenarioRuntime sr = run("""
            * def json = { foo: 'bar' }
            * def result = karate.pretty(json)
            * assert result.indexOf('foo') >= 0
            * assert result.indexOf('bar') >= 0
            """);
        assertPassed(sr);
    }

}
