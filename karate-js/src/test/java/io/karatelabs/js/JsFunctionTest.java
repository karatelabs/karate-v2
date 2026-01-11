package io.karatelabs.js;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class JsFunctionTest extends EvalBase {

    @Test
    void testDev() {

    }

    @Test
    void testFunction() {
        assertEquals(true, eval("var a = function(){ return true }; a()"));
        assertEquals(2, eval("var a = 2; var b = function(){ return a }; b()"));
        assertEquals(5, eval("var fn = function(x, y){ return x + y }; fn(2, 3)"));
        assertEquals(5, eval("function add(x, y){ return x + y }; add(2, 3)"));
    }

    @Test
    void testFunctionNested() {
        assertEquals(true, eval("var a = {}; a.b = function(){ return true }; a.b()"));
        assertEquals(true, eval("var a = {}; a.b = function(){ return true }; a['b']()"));
        assertEquals(2, eval("var a = function(){}; a.b = [1, 2, 3]; a['b'][1]"));
    }

    @Test
    void testArrowFunction() {
        assertEquals(true, eval("var a = () => true; a()"));
        assertEquals(true, eval("var a = x => true; a()"));
        assertEquals(2, eval("var a = x => x; a(2)"));
        assertEquals(2, eval("var a = (x) => x; a(2)"));
        assertEquals(5, eval("var fn = (x, y) => x + y; fn(2, 3)"));
    }

    @Test
    void testArrowFunctionUndefinedArg() {
        // Single-param arrow function should preserve undefined
        assertEquals(true, eval("var a = x => x === undefined; a(undefined)"));
        assertEquals(true, eval("var a = (x) => x === undefined; a(undefined)"));
        // Multi-param arrow function
        assertEquals(true, eval("var a = (x, y) => x === undefined; a(undefined, 1)"));
        // Regular function for comparison
        assertEquals(true, eval("var a = function(x) { return x === undefined }; a(undefined)"));
        // Arrow with implicit undefined (missing arg)
        assertEquals(true, eval("var a = x => x === undefined; a()"));
        assertEquals(true, eval("var a = (x) => x === undefined; a()"));
    }

    @Test
    void testArrowFunctionPassThroughUndefined() {
        // Arrow function identity should preserve undefined
        assertEquals(true, eval("var identity = x => x; identity(undefined) === undefined"));
        assertEquals(true, eval("var identity = (x) => x; identity(undefined) === undefined"));
        // Map with arrow function preserving undefined
        assertEquals(true, eval("[undefined].map(x => x)[0] === undefined"));
        assertEquals(true, eval("[undefined].map(function(x) { return x })[0] === undefined"));
    }

    @Test
    void testFunctionBlocksAndReturn() {
        assertNull(eval("var a = function(){ }; a()"));
        assertEquals(true, eval("var a = function(){ return true; 'foo' }; a()"));
        assertEquals("foo", eval("var a = function(){ if (true) return 'foo'; return 'bar' }; a()"));
        assertEquals("foo", eval("var a = function(){ for (var i = 0; i < 2; i++) { return 'foo' }; return 'bar' }; a()"));
        assertNull(eval("var a = () => {}; a()"));
        assertNull(eval("var a = () => { true }; a()"));
        assertEquals(true, eval("var a = () => { return true }; a()"));
        assertEquals(true, eval("var a = () => { return true; 'foo' }; a()"));
    }

    @Test
    void testFunctionArgsMissing() {
        assertEquals(true, eval("var a = function(b){ return b }; a() === undefined"));
    }

    @Test
    void testFunctionNew() {
        assertEquals("foo", eval("var a = function(x){ this.b = x }; c = new a('foo'); c.b"));
    }

    @Test
    void testFunctionNewNoBrackets() {
        assertEquals("foo", eval("var a = function(){ this.b = 'foo' }; c = new a; c.b"));
    }

    @Test
    void testFunctionArguments() {
        assertEquals(List.of(1, 2), eval("var a = function(){ return arguments }; a(1, 2)"));
    }

    @Test
    void testFunctionCallSpread() {
        assertEquals(List.of(1, 2), eval("var a = function(){ return arguments }; var b = [1, 2]; a(...b)"));
    }

    @Test
    void testFunctionPrototypeToString() {
        // ES6: Function.prototype.toString returns actual source for user-defined functions
        assertEquals("function(){ }", eval("var a = function(){ }; a.toString()"));
        assertEquals("function a(){ }", eval("function a(){ }; a.toString()"));
        assertEquals("a", eval("var a = function(){ }; a.constructor.name"));
        assertEquals("a", eval("function a(){ }; a.constructor.name"));
        // ES6: a.prototype.toString does NOT affect a.toString() - prototype is only for instances
        assertEquals("function(){ }", eval("var a = function(){ }; a.prototype.toString = function(){ return 'foo' }; a.toString()"));
    }

    @Test
    void testFunctionToStringReflection() {
        // Function source should be available for reflection
        // Regular function expression
        assertEquals("function(a, b){ return a + b }", eval("var add = function(a, b){ return a + b }; add.toString()"));
        // Named function declaration
        assertEquals("function multiply(x, y){ return x * y }", eval("function multiply(x, y){ return x * y }; multiply.toString()"));
        // Arrow function
        assertEquals("x => x * 2", eval("var double = x => x * 2; double.toString()"));
        // Arrow function with block body
        assertEquals("(a, b) => { return a + b }", eval("var add = (a, b) => { return a + b }; add.toString()"));
    }

    @Test
    void testFunctionDeclarationRest() {
        assertEquals(List.of(1, 2, 3), eval("function sum(...args) { return args }; sum(1, 2, 3)"));
        assertEquals(6, eval("function sum(...numbers) { return numbers.reduce((a, b) => a + b, 0) }; sum(1,2,3)"));
        assertEquals("hello world", eval("function concat(first, ...rest) { return first + ' ' + rest.join(' ') }; concat('hello', 'world')"));
        assertEquals("hello world and more", eval("function concat(first, ...rest) { return first + ' ' + rest.join(' ') }; concat('hello', 'world', 'and', 'more')"));
    }

    @Test
    void testArrowFunctionRest() {
        assertEquals("[1,2,3]", eval("var sum = (...args) => args; JSON.stringify(sum(1,2,3))"));
        assertEquals(6, eval("var sum = (...numbers) => numbers.reduce((a, b) => a + b, 0); sum(1,2,3)"));
        assertEquals("hello world", eval("var concat = (first, ...rest) => first + ' ' + rest.join(' '); concat('hello', 'world')"));
    }

    @Test
    void testFunctionDeclarationDefault() {
        assertEquals(3, eval("function foo(a, b = 2) { return a + b }; foo(1)"));
        assertEquals(2, eval("function foo(a, b = 2) { return a + b }; foo(1, 1)"));
        assertEquals(1, eval("function foo(a, b = 2) { return a + b }; foo(1, null)"));
        assertEquals(3, eval("function foo(a, b = 2) { return a + b }; foo(1, undefined)"));
        assertEquals(2, eval("function foo(a, b = a + 1) { return b }; foo(1)"));
    }

    @Test
    void testFunctionDeclarationDestructuring() {
        assertEquals(5, eval("function foo([a, b]) { return a + b }; foo([2, 3])"));
        assertEquals(5, eval("function foo({ a, b }) { return a + b }; foo({ a: 2, b: 3 })"));
    }

    @Test
    void testCurrying() {
        matchEval("function multiply(a) { return function(b) { return a * b } }; multiply(4)(7)", "28");
    }

    @Test
    void testIife() {
        matchEval("(function(){ return 'hello' })()", "'hello'");
    }

    @Test
    void testCallAndApply() {
        matchEval("function sum(a, b, c){ return a + b + c }; sum(1, 2, 3)", "6");
        matchEval("function sum(a, b, c){ return a + b + c }; sum.call(null, 1, 2, 3)", "6");
        matchEval("function sum(a, b, c){ return a + b + c }; sum.apply(null, [1, 2, 3])", "6");
        matchEval("function greet(pre){ return pre + this.name }; var p = { name: 'john' }; greet.call(p, 'hi ')", "hi john");
    }

    @Test
    void testGetReferenceAndInvoke() {
        eval("var a = function(name){ return 'hello ' + name }");
        JsCallable fn = (JsCallable) get("a");
        Object result = fn.call(null, "world");
        assertEquals("hello world", result);
    }

}
