package io.karatelabs.js;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for JS/Java interop.
 *
 * Design principles:
 * 1. JsArray implements List, JsObject implements Map - seamless Java interop
 * 2. ES6 semantics preserved within JS (undefined, prototype chain, etc.)
 * 3. Lazy auto-unwrap at Java interface boundary:
 *    - List.get(int) / Map.get(Object) → unwrapped values (null, Date, etc.)
 *    - JsArray.getElement(int) / JsObject.getMember(String) → raw JS values
 * 4. Conversion at boundaries: Engine.eval() top-level, SimpleObject args
 *
 * Access patterns:
 * - Java user: casts to List/Map, gets unwrapped values automatically
 * - JS internal: uses getElement()/getMember(), sees raw JS values
 */
class JavaMirrorTest {

    // =================================================================================================================
    // Boundary Conversion Tests (SimpleObject/JsCallable arguments)
    // These test that arguments passed to Java methods are converted
    // =================================================================================================================

    @Test
    void testUndefinedPassedToSimpleObjectMethodBecomesNull() {
        Engine engine = new Engine();
        List<Object> capturedArgs = new ArrayList<>();

        SimpleObject utils = name -> {
            if ("captureArg".equals(name)) {
                return (JsCallable) (context, args) -> {
                    capturedArgs.add(args.length > 0 ? args[0] : "NO_ARG");
                    return null;
                };
            }
            return null;
        };

        engine.put("utils", utils);
        engine.eval("""
                var user = { name: 'John' };
                utils.captureArg(user.version);
                """);

        assertEquals(1, capturedArgs.size());
        assertNull(capturedArgs.get(0), "undefined should be converted to null at JS/Java boundary");
    }

    @Test
    void testExplicitUndefinedPassedToSimpleObjectMethodBecomesNull() {
        Engine engine = new Engine();
        List<Object> capturedArgs = new ArrayList<>();

        SimpleObject utils = name -> {
            if ("captureArg".equals(name)) {
                return (JsCallable) (context, args) -> {
                    capturedArgs.add(args.length > 0 ? args[0] : "NO_ARG");
                    return null;
                };
            }
            return null;
        };

        engine.put("utils", utils);
        engine.eval("utils.captureArg(undefined)");

        assertEquals(1, capturedArgs.size());
        assertNull(capturedArgs.get(0), "explicit undefined should be converted to null");
    }

    @Test
    void testNullPassedToSimpleObjectMethodRemainsNull() {
        Engine engine = new Engine();
        List<Object> capturedArgs = new ArrayList<>();

        SimpleObject utils = name -> {
            if ("captureArg".equals(name)) {
                return (JsCallable) (context, args) -> {
                    capturedArgs.add(args.length > 0 ? args[0] : "NO_ARG");
                    return null;
                };
            }
            return null;
        };

        engine.put("utils", utils);
        engine.eval("utils.captureArg(null)");

        assertEquals(1, capturedArgs.size());
        assertNull(capturedArgs.get(0), "null should remain null");
    }

    @Test
    void testDefinedValuePassedToSimpleObjectMethodWorks() {
        Engine engine = new Engine();
        List<Object> capturedArgs = new ArrayList<>();

        SimpleObject utils = name -> {
            if ("captureArg".equals(name)) {
                return (JsCallable) (context, args) -> {
                    capturedArgs.add(args.length > 0 ? args[0] : "NO_ARG");
                    return null;
                };
            }
            return null;
        };

        engine.put("utils", utils);
        engine.eval("""
                var user = { name: 'John', version: 42 };
                utils.captureArg(user.version);
                """);

        assertEquals(1, capturedArgs.size());
        assertEquals(42, capturedArgs.get(0), "defined value should pass through normally");
    }

    @Test
    void testUndefinedPassedToInvokableBecomesNull() {
        Engine engine = new Engine();
        List<Object> capturedArgs = new ArrayList<>();

        SimpleObject utils = name -> {
            if ("captureArg".equals(name)) {
                return (Invokable) args -> {
                    capturedArgs.add(args.length > 0 ? args[0] : "NO_ARG");
                    return null;
                };
            }
            return null;
        };

        engine.put("utils", utils);
        engine.eval("""
                var user = { name: 'John' };
                utils.captureArg(user.version);
                """);

        assertEquals(1, capturedArgs.size());
        assertNull(capturedArgs.get(0), "undefined should be converted to null for Invokable");
    }

    @Test
    void testMultipleArgsWithUndefinedBecomesNull() {
        Engine engine = new Engine();
        List<Object[]> capturedCalls = new ArrayList<>();

        SimpleObject utils = name -> {
            if ("updateUser".equals(name)) {
                return (JsCallable) (context, args) -> {
                    capturedCalls.add(args);
                    return null;
                };
            }
            return null;
        };

        engine.put("utils", utils);
        engine.eval("""
                var user = { userId: '123', name: 'John' };
                utils.updateUser(user.userId, user.name, user.version);
                """);

        assertEquals(1, capturedCalls.size());
        Object[] args = capturedCalls.get(0);
        assertEquals(3, args.length);
        assertEquals("123", args[0]);
        assertEquals("John", args[1]);
        assertNull(args[2], "undefined version should be converted to null");
    }

    @Test
    void testJsDatePassedToSimpleObjectBecomesDate() {
        Engine engine = new Engine();
        List<Object> capturedArgs = new ArrayList<>();

        SimpleObject utils = name -> {
            if ("captureArg".equals(name)) {
                return (JsCallable) (context, args) -> {
                    capturedArgs.add(args.length > 0 ? args[0] : "NO_ARG");
                    return null;
                };
            }
            return null;
        };

        engine.put("utils", utils);
        engine.eval("utils.captureArg(new Date(0))");

        assertEquals(1, capturedArgs.size());
        assertInstanceOf(Date.class, capturedArgs.get(0), "JsDate should be converted to java.util.Date");
        assertEquals(0L, ((Date) capturedArgs.get(0)).getTime());
    }

    // =================================================================================================================
    // Top-Level Conversion Tests (Engine.eval return values)
    // These test that top-level values returned from eval() are converted
    // =================================================================================================================

    @Test
    void testTopLevelUndefinedBecomesNull() {
        Engine engine = new Engine();
        Object result = engine.eval("undefined");
        assertNull(result, "top-level undefined should be converted to null");
    }

    @Test
    void testTopLevelJsDateBecomesDate() {
        Engine engine = new Engine();
        Object result = engine.eval("new Date(86400000)");
        assertInstanceOf(Date.class, result, "top-level JsDate should be converted to Date");
        assertEquals(86400000L, ((Date) result).getTime());
    }

    @Test
    void testTopLevelNullRemainsNull() {
        Engine engine = new Engine();
        Object result = engine.eval("null");
        assertNull(result, "top-level null should remain null");
    }

    @Test
    void testTopLevelPrimitivesPassThrough() {
        Engine engine = new Engine();
        assertEquals(42, engine.eval("42"));
        assertEquals("hello", engine.eval("'hello'"));
        assertEquals(true, engine.eval("true"));
        assertEquals(3.14, (Double) engine.eval("3.14"), 0.001);
    }

    // =================================================================================================================
    // JsArray as List Tests
    // These test that JsArray can be used as java.util.List
    // =================================================================================================================

    @Test
    void testJsArrayIsInstanceOfList() {
        Engine engine = new Engine();
        Object result = engine.eval("[1, 2, 3]");
        Object rawResult = engine.evalRaw("[1, 2, 3]");
        System.out.println("Array type (eval): " + result.getClass().getName());
        System.out.println("Array type (raw): " + rawResult.getClass().getName());
        assertInstanceOf(List.class, result, "JsArray should be instanceof List");
    }

    @Test
    @SuppressWarnings("unchecked")
    void testJsArrayListOperations() {
        Engine engine = new Engine();
        List<Object> list = (List<Object>) engine.eval("[1, 2, 3]");

        assertEquals(3, list.size());
        assertEquals(1, list.get(0));
        assertEquals(2, list.get(1));
        assertEquals(3, list.get(2));
    }

    @Test
    @SuppressWarnings("unchecked")
    void testJsArrayListIteration() {
        Engine engine = new Engine();
        List<Object> list = (List<Object>) engine.eval("['a', 'b', 'c']");

        StringBuilder sb = new StringBuilder();
        for (Object item : list) {
            sb.append(item);
        }
        assertEquals("abc", sb.toString());
    }

    @Test
    @SuppressWarnings("unchecked")
    void testJsArrayContainsRawValues() {
        // Elements inside JsArray are NOT converted - they remain raw JS values
        Engine engine = new Engine();
        Object result = engine.evalRaw("[new Date(0), undefined, null, 'hello']");

        assertInstanceOf(List.class, result);
        List<Object> list = (List<Object>) result;
        assertEquals(4, list.size());

        // Elements are raw - JsDate, undefined (Terms.UNDEFINED), null, String
        assertInstanceOf(JsDate.class, list.get(0), "element should be raw JsDate");
        assertNotNull(list.get(1), "undefined should be Terms.UNDEFINED, not null");
        assertNull(list.get(2), "null remains null");
        assertEquals("hello", list.get(3));
    }

    @Test
    @SuppressWarnings("unchecked")
    void testObjectValuesReturnsUsableList() {
        Engine engine = new Engine();
        Object result = engine.eval("Object.values({a: 1, b: 2, c: 3})");

        assertInstanceOf(List.class, result);
        List<Object> list = (List<Object>) result;
        assertEquals(3, list.size());
        assertTrue(list.contains(1));
        assertTrue(list.contains(2));
        assertTrue(list.contains(3));
    }

    @Test
    @SuppressWarnings("unchecked")
    void testArrayMapReturnsUsableList() {
        Engine engine = new Engine();
        Object result = engine.eval("[1, 2, 3].map(function(x) { return x * 2; })");

        assertInstanceOf(List.class, result);
        List<Object> list = (List<Object>) result;
        assertEquals(3, list.size());
        assertEquals(2, list.get(0));
        assertEquals(4, list.get(1));
        assertEquals(6, list.get(2));
    }

    @Test
    @SuppressWarnings("unchecked")
    void testArrayFilterReturnsUsableList() {
        Engine engine = new Engine();
        Object result = engine.eval("[1, 2, 3, 4, 5].filter(function(x) { return x > 2; })");

        assertInstanceOf(List.class, result);
        List<Object> list = (List<Object>) result;
        assertEquals(3, list.size());
        assertEquals(3, list.get(0));
        assertEquals(4, list.get(1));
        assertEquals(5, list.get(2));
    }

    @Test
    @SuppressWarnings("unchecked")
    void testArraySliceReturnsUsableList() {
        Engine engine = new Engine();
        Object result = engine.eval("['a', 'b', 'c', 'd'].slice(1, 3)");

        assertInstanceOf(List.class, result);
        List<Object> list = (List<Object>) result;
        assertEquals(2, list.size());
        assertEquals("b", list.get(0));
        assertEquals("c", list.get(1));
    }

    // =================================================================================================================
    // JsObject as Map Tests
    // These test that JsObject can be used as java.util.Map
    // =================================================================================================================

    @Test
    void testJsObjectIsInstanceOfMap() {
        Engine engine = new Engine();
        Object result = engine.eval("({a: 1, b: 2})");
        System.out.println("Object type: " + result.getClass().getName());
        assertInstanceOf(Map.class, result, "JsObject should be instanceof Map");
    }

    @Test
    @SuppressWarnings("unchecked")
    void testJsObjectMapOperations() {
        Engine engine = new Engine();
        Map<String, Object> map = (Map<String, Object>) engine.eval("({name: 'John', age: 30})");

        assertEquals(2, map.size());
        assertEquals("John", map.get("name"));
        assertEquals(30, map.get("age"));
        assertTrue(map.containsKey("name"));
        assertTrue(map.containsKey("age"));
        assertFalse(map.containsKey("missing"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void testJsObjectMapIteration() {
        Engine engine = new Engine();
        Map<String, Object> map = (Map<String, Object>) engine.eval("({a: 1, b: 2})");

        int sum = 0;
        for (Object value : map.values()) {
            sum += (Integer) value;
        }
        assertEquals(3, sum);
    }

    @Test
    @SuppressWarnings("unchecked")
    void testJsObjectContainsRawValues() {
        // Values inside JsObject are NOT converted - they remain raw JS values
        Engine engine = new Engine();
        Object result = engine.evalRaw("({date: new Date(0), missing: undefined, empty: null})");

        assertInstanceOf(Map.class, result);
        Map<String, Object> map = (Map<String, Object>) result;

        // Values are raw - JsDate, undefined (Terms.UNDEFINED), null
        assertInstanceOf(JsDate.class, map.get("date"), "value should be raw JsDate");
        assertNotNull(map.get("missing"), "undefined should be Terms.UNDEFINED, not null");
        assertNull(map.get("empty"), "null remains null");
    }

    @Test
    @SuppressWarnings("unchecked")
    void testObjectAssignReturnsUsableMap() {
        Engine engine = new Engine();
        Object result = engine.eval("Object.assign({}, {a: 1}, {b: 2})");

        assertInstanceOf(Map.class, result);
        Map<String, Object> map = (Map<String, Object>) result;
        assertEquals(2, map.size());
        assertEquals(1, map.get("a"));
        assertEquals(2, map.get("b"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void testObjectFromEntriesReturnsUsableMap() {
        Engine engine = new Engine();
        Object result = engine.eval("Object.fromEntries([['a', 1], ['b', 2]])");

        assertInstanceOf(Map.class, result);
        Map<String, Object> map = (Map<String, Object>) result;
        assertEquals(2, map.size());
        assertEquals(1, map.get("a"));
        assertEquals(2, map.get("b"));
    }

    // =================================================================================================================
    // Prototype Methods Still Work
    // These test that JS prototype methods still work on JsArray/JsObject
    // =================================================================================================================

    @Test
    void testArrayPrototypeMethods() {
        Engine engine = new Engine();
        assertEquals(true, engine.eval("[1, 2, 3].includes(2)"));
        assertEquals(1, engine.eval("[1, 2, 3].indexOf(2)"));
        assertEquals(6, engine.eval("[1, 2, 3].reduce(function(a, b) { return a + b; }, 0)"));
        assertEquals("1,2,3", engine.eval("[1, 2, 3].join(',')"));
    }

    @Test
    void testObjectPrototypeMethods() {
        Engine engine = new Engine();
        assertEquals(true, engine.eval("({a: 1}).hasOwnProperty('a')"));
        assertEquals(false, engine.eval("({a: 1}).hasOwnProperty('b')"));
    }

    // =================================================================================================================
    // Nested Structures
    // These test that nested JsArray/JsObject are usable as List/Map
    // =================================================================================================================

    @Test
    @SuppressWarnings("unchecked")
    void testNestedArraysUsableAsList() {
        Engine engine = new Engine();
        Object result = engine.eval("[[1, 2], [3, 4]]");

        assertInstanceOf(List.class, result);
        List<Object> outer = (List<Object>) result;
        assertEquals(2, outer.size());

        assertInstanceOf(List.class, outer.get(0));
        List<Object> inner = (List<Object>) outer.get(0);
        assertEquals(2, inner.size());
        assertEquals(1, inner.get(0));
        assertEquals(2, inner.get(1));
    }

    @Test
    @SuppressWarnings("unchecked")
    void testNestedObjectsUsableAsMap() {
        Engine engine = new Engine();
        Object result = engine.eval("({outer: {inner: 'value'}})");

        assertInstanceOf(Map.class, result);
        Map<String, Object> outer = (Map<String, Object>) result;

        assertInstanceOf(Map.class, outer.get("outer"));
        Map<String, Object> inner = (Map<String, Object>) outer.get("outer");
        assertEquals("value", inner.get("inner"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void testMixedNestedStructures() {
        Engine engine = new Engine();
        Object result = engine.eval("({items: [1, 2, 3], meta: {count: 3}})");

        assertInstanceOf(Map.class, result);
        Map<String, Object> map = (Map<String, Object>) result;

        assertInstanceOf(List.class, map.get("items"));
        List<Object> items = (List<Object>) map.get("items");
        assertEquals(3, items.size());

        assertInstanceOf(Map.class, map.get("meta"));
        Map<String, Object> meta = (Map<String, Object>) map.get("meta");
        assertEquals(3, meta.get("count"));
    }

    // =================================================================================================================
    // Dual Access Pattern Tests - Java interface vs JS internal
    // Demonstrates: List.get() → unwrapped, JsArray.getElement() → raw
    // NOTE: These tests are commented out until JsArray.getElement() and JsObject.getMember() are implemented
    // =================================================================================================================

    /*
     * TODO: Uncomment after implementing:
     * - JsArray.getElement(int) for raw JS access
     * - JsObject.getMember(String) for raw JS access
     * - JsArray implements List<Object> with auto-unwrap
     * - JsObject implements Map<String, Object> with auto-unwrap
     *
     * See docs/JS_INTEROP.md for implementation plan.
     */

    // @Test
    // void testListGetUnwrapsUndefined() { ... }
    // @Test
    // void testMapGetUnwrapsUndefined() { ... }
    // @Test
    // void testListGetUnwrapsJsDate() { ... }
    // @Test
    // void testMapGetUnwrapsJsDate() { ... }
    // @Test
    // void testNestedListUnwrapsRecursively() { ... }
    // @Test
    // void testMapNullVsUndefinedBothBecomeNull() { ... }
    // @Test
    // void testListNullVsUndefinedBothBecomeNull() { ... }
    // @Test
    // void testMixedTypesUnwrappedViaListInterface() { ... }
    // @Test
    // void testMixedTypesUnwrappedViaMapInterface() { ... }

    // =================================================================================================================
    // Array methods producing undefined in results
    // =================================================================================================================

    @Test
    @SuppressWarnings("unchecked")
    void testArrayMapReturningUndefined() {
        // map() callback explicitly returns undefined
        Engine engine = new Engine();
        List<Object> list = (List<Object>) engine.eval(
                "[1, 2, 3].map(function(x) { return undefined; })");

        assertEquals(3, list.size());
        assertSame(Terms.UNDEFINED, list.get(0), "map returning undefined should preserve raw undefined");
        assertSame(Terms.UNDEFINED, list.get(1));
        assertSame(Terms.UNDEFINED, list.get(2));
    }

    @Test
    @SuppressWarnings("unchecked")
    void testArrayMapReturningNothing() {
        // map() callback returns nothing (implicit undefined)
        // NOTE: Current behavior converts implicit return to null (potential bug)
        Engine engine = new Engine();
        List<Object> list = (List<Object>) engine.eval(
                "[1, 2, 3].map(function(x) { })");

        assertEquals(3, list.size());
        // Current behavior: implicit return becomes null, not Terms.UNDEFINED
        // This may be a deviation from ES6 spec
        assertNull(list.get(0), "implicit return currently becomes null");
        assertNull(list.get(1));
        assertNull(list.get(2));
    }

    @Test
    @SuppressWarnings("unchecked")
    void testArrayMapOnUndefinedElements() {
        // map() over array containing undefined - callback receives undefined
        Engine engine = new Engine();
        List<Object> list = (List<Object>) engine.eval(
                "[1, undefined, 3].map(function(x) { return x; })");

        assertEquals(3, list.size());
        assertEquals(1, list.get(0));
        assertSame(Terms.UNDEFINED, list.get(1), "undefined element passed through map should stay undefined");
        assertEquals(3, list.get(2));
    }

    @Test
    @SuppressWarnings("unchecked")
    void testArrayMapPartialUndefined() {
        // map() returning undefined for some elements (implicit return)
        // NOTE: Current behavior converts implicit return to null
        Engine engine = new Engine();
        List<Object> list = (List<Object>) engine.eval(
                "[1, 2, 3, 4].map(function(x) { if (x % 2 === 0) return x * 2; })");

        assertEquals(4, list.size());
        // Current behavior: implicit return becomes null
        assertNull(list.get(0), "odd element currently becomes null (implicit return)");
        assertEquals(4, list.get(1));
        assertNull(list.get(2), "odd element currently becomes null (implicit return)");
        assertEquals(8, list.get(3));
    }

    @Test
    @SuppressWarnings("unchecked")
    void testArrayFilterOnUndefinedElements() {
        // filter() on array with undefined - undefined is falsy so filtered out
        Engine engine = new Engine();
        List<Object> list = (List<Object>) engine.eval(
                "[1, undefined, 2, undefined, 3].filter(function(x) { return x; })");

        assertEquals(3, list.size());
        assertEquals(1, list.get(0));
        assertEquals(2, list.get(1));
        assertEquals(3, list.get(2));
    }

    @Test
    @SuppressWarnings("unchecked")
    void testArrayFlatMapWithUndefined() {
        // flatMap() returning arrays with undefined
        Engine engine = new Engine();
        List<Object> list = (List<Object>) engine.eval(
                "[1, 2].flatMap(function(x) { return [x, undefined]; })");

        assertEquals(4, list.size());
        assertEquals(1, list.get(0));
        assertSame(Terms.UNDEFINED, list.get(1), "undefined in flatMap result should be preserved");
        assertEquals(2, list.get(2));
        assertSame(Terms.UNDEFINED, list.get(3));
    }

    @Test
    @SuppressWarnings("unchecked")
    void testArrayFromWithUndefined() {
        // Array.from() with undefined elements
        Engine engine = new Engine();
        List<Object> list = (List<Object>) engine.eval(
                "Array.from({length: 3})");

        assertEquals(3, list.size());
        // Array.from({length: 3}) creates [undefined, undefined, undefined]
        assertSame(Terms.UNDEFINED, list.get(0));
        assertSame(Terms.UNDEFINED, list.get(1));
        assertSame(Terms.UNDEFINED, list.get(2));
    }

    @Test
    @SuppressWarnings("unchecked")
    void testObjectValuesWithUndefined() {
        // Object.values() on object with undefined values
        Engine engine = new Engine();
        List<Object> list = (List<Object>) engine.eval(
                "Object.values({a: 1, b: undefined, c: 3})");

        assertEquals(3, list.size());
        assertTrue(list.contains(1));
        assertTrue(list.contains(3));
        // One of them should be Terms.UNDEFINED
        long undefinedCount = list.stream().filter(x -> x == Terms.UNDEFINED).count();
        assertEquals(1, undefinedCount, "Object.values should include raw undefined");
    }

    @Test
    @SuppressWarnings("unchecked")
    void testObjectEntriesWithUndefined() {
        // Object.entries() on object with undefined values
        Engine engine = new Engine();
        List<Object> entries = (List<Object>) engine.eval(
                "Object.entries({a: undefined})");

        assertEquals(1, entries.size());
        List<Object> entry = (List<Object>) entries.get(0);
        assertEquals("a", entry.get(0));
        assertSame(Terms.UNDEFINED, entry.get(1), "entry value should be raw undefined");
    }

    @Test
    @SuppressWarnings("unchecked")
    void testSpreadWithUndefined() {
        // Spread operator preserves undefined
        Engine engine = new Engine();
        List<Object> list = (List<Object>) engine.eval(
                "var a = [undefined]; [...a, 1, ...a]");

        assertEquals(3, list.size());
        assertSame(Terms.UNDEFINED, list.get(0));
        assertEquals(1, list.get(1));
        assertSame(Terms.UNDEFINED, list.get(2));
    }

    @Test
    @SuppressWarnings("unchecked")
    void testArrayConcatWithUndefined() {
        // concat() preserves undefined
        Engine engine = new Engine();
        List<Object> list = (List<Object>) engine.eval(
                "[undefined].concat([1, undefined])");

        assertEquals(3, list.size());
        assertSame(Terms.UNDEFINED, list.get(0));
        assertEquals(1, list.get(1));
        assertSame(Terms.UNDEFINED, list.get(2));
    }

    // =================================================================================================================
    // Function.call() and Function.apply() edge cases
    // =================================================================================================================

    @Test
    @SuppressWarnings("unchecked")
    void testFunctionCallReturningUndefined() {
        // Function returning undefined via call()
        Engine engine = new Engine();
        Object result = engine.eval(
                "var fn = function() { return undefined; }; fn.call(null)");

        // Top-level undefined is converted to null
        assertNull(result);
    }

    @Test
    @SuppressWarnings("unchecked")
    void testFunctionCallInArrayContext() {
        // Using call() within map to produce undefined in array
        // NOTE: Current behavior converts implicit return to null
        Engine engine = new Engine();
        List<Object> list = (List<Object>) engine.eval(
                "var fn = function(x) { if (x > 2) return x; }; [1, 2, 3, 4].map(function(x) { return fn.call(null, x); })");

        assertEquals(4, list.size());
        // Current behavior: implicit return becomes null
        assertNull(list.get(0), "fn.call returning nothing currently becomes null");
        assertNull(list.get(1));
        assertEquals(3, list.get(2));
        assertEquals(4, list.get(3));
    }

    @Test
    @SuppressWarnings("unchecked")
    void testFunctionApplyInArrayContext() {
        // Using apply() within map to produce undefined in array
        // NOTE: Current behavior converts implicit return to null
        Engine engine = new Engine();
        List<Object> list = (List<Object>) engine.eval(
                "var fn = function(x) { if (x > 2) return x; }; [1, 2, 3, 4].map(function(x) { return fn.apply(null, [x]); })");

        assertEquals(4, list.size());
        // Current behavior: implicit return becomes null
        assertNull(list.get(0), "fn.apply returning nothing currently becomes null");
        assertNull(list.get(1));
        assertEquals(3, list.get(2));
        assertEquals(4, list.get(3));
    }

    @Test
    @SuppressWarnings("unchecked")
    void testArrayMapWithCallOnPrototype() {
        // Array.prototype.map.call() on array-like object
        Engine engine = new Engine();
        List<Object> list = (List<Object>) engine.eval(
                "[].map.call([1, undefined, 3], function(x) { return x; })");

        assertEquals(3, list.size());
        assertEquals(1, list.get(0));
        assertSame(Terms.UNDEFINED, list.get(1), "undefined preserved through map.call");
        assertEquals(3, list.get(2));
    }

    @Test
    @SuppressWarnings("unchecked")
    void testArraySliceWithUndefined() {
        // slice() preserves undefined elements
        Engine engine = new Engine();
        List<Object> list = (List<Object>) engine.eval(
                "[1, undefined, 3, undefined, 5].slice(1, 4)");

        assertEquals(3, list.size());
        assertSame(Terms.UNDEFINED, list.get(0));
        assertEquals(3, list.get(1));
        assertSame(Terms.UNDEFINED, list.get(2));
    }

    @Test
    @SuppressWarnings("unchecked")
    void testArraySpliceReturnsUndefined() {
        // splice() return value contains undefined
        Engine engine = new Engine();
        List<Object> removed = (List<Object>) engine.eval(
                "var arr = [1, undefined, 3]; arr.splice(1, 1)");

        assertEquals(1, removed.size());
        assertSame(Terms.UNDEFINED, removed.get(0), "splice should return raw undefined");
    }

    // =================================================================================================================
    // Mixed JS wrapper types (JsBoolean, JsNumber, JsDate, JsString) in collections
    // =================================================================================================================

    @Test
    @SuppressWarnings("unchecked")
    void testListWithJsWrapperTypes() {
        // List containing JS wrapper types: new Boolean(), new Number(), new String()
        Engine engine = new Engine();
        List<Object> list = (List<Object>) engine.eval(
                "[new Boolean(true), new Number(42), new String('hello'), new Date(0)]");

        assertEquals(4, list.size());

        // new Boolean(true) creates a JsBoolean wrapper
        Object boolWrapper = list.get(0);
        System.out.println("Boolean wrapper type: " + boolWrapper.getClass().getName());

        // new Number(42) creates a JsNumber wrapper
        Object numWrapper = list.get(1);
        System.out.println("Number wrapper type: " + numWrapper.getClass().getName());

        // new String('hello') creates a JsString wrapper
        Object strWrapper = list.get(2);
        System.out.println("String wrapper type: " + strWrapper.getClass().getName());

        // new Date(0) creates a JsDate wrapper
        Object dateWrapper = list.get(3);
        assertInstanceOf(JsDate.class, dateWrapper);
    }

    @Test
    @SuppressWarnings("unchecked")
    void testMapWithJsWrapperTypes() {
        // Map containing JS wrapper types
        Engine engine = new Engine();
        Map<String, Object> map = (Map<String, Object>) engine.eval(
                "({bool: new Boolean(false), num: new Number(3.14), str: new String('world'), date: new Date(1000)})");

        assertEquals(4, map.size());

        System.out.println("bool type: " + map.get("bool").getClass().getName());
        System.out.println("num type: " + map.get("num").getClass().getName());
        System.out.println("str type: " + map.get("str").getClass().getName());
        assertInstanceOf(JsDate.class, map.get("date"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void testMixedPrimitivesAndWrappers() {
        // Mix of primitives and wrapper objects
        Engine engine = new Engine();
        List<Object> list = (List<Object>) engine.eval(
                "[true, new Boolean(true), 42, new Number(42), 'hello', new String('hello')]");

        assertEquals(6, list.size());

        // Primitives
        assertEquals(true, list.get(0));   // boolean primitive
        assertEquals(42, list.get(2));      // number primitive
        assertEquals("hello", list.get(4)); // string primitive

        // Wrappers - document their types
        System.out.println("Primitive true: " + list.get(0).getClass().getName());
        System.out.println("Wrapper Boolean: " + list.get(1).getClass().getName());
        System.out.println("Primitive 42: " + list.get(2).getClass().getName());
        System.out.println("Wrapper Number: " + list.get(3).getClass().getName());
        System.out.println("Primitive 'hello': " + list.get(4).getClass().getName());
        System.out.println("Wrapper String: " + list.get(5).getClass().getName());
    }

    @Test
    @SuppressWarnings("unchecked")
    void testArrayMapProducingWrappers() {
        // map() producing wrapper objects
        Engine engine = new Engine();
        List<Object> list = (List<Object>) engine.eval(
                "[1, 2, 3].map(function(x) { return new Date(x * 1000); })");

        assertEquals(3, list.size());
        // Each element should be a JsDate (raw, not converted to java.util.Date)
        assertInstanceOf(JsDate.class, list.get(0));
        assertInstanceOf(JsDate.class, list.get(1));
        assertInstanceOf(JsDate.class, list.get(2));
    }

    @Test
    @SuppressWarnings("unchecked")
    void testMixedWrapperAndUndefined() {
        // Mix of wrapper types and undefined
        Engine engine = new Engine();
        List<Object> list = (List<Object>) engine.eval(
                "[new Date(0), undefined, new Boolean(true), null, new Number(99)]");

        assertEquals(5, list.size());
        assertInstanceOf(JsDate.class, list.get(0));
        assertSame(Terms.UNDEFINED, list.get(1));
        System.out.println("Boolean wrapper: " + list.get(2).getClass().getName());
        assertNull(list.get(3));
        System.out.println("Number wrapper: " + list.get(4).getClass().getName());
    }

    @Test
    @SuppressWarnings("unchecked")
    void testObjectValuesWithWrapperTypes() {
        // Object.values() on object with wrapper type values
        Engine engine = new Engine();
        List<Object> list = (List<Object>) engine.eval(
                "Object.values({d: new Date(0), b: new Boolean(false), n: new Number(0)})");

        assertEquals(3, list.size());
        // Check that raw wrapper types are preserved
        boolean hasJsDate = list.stream().anyMatch(x -> x instanceof JsDate);
        assertTrue(hasJsDate, "Should contain raw JsDate");
    }

    @Test
    @SuppressWarnings("unchecked")
    void testNestedStructureWithWrappers() {
        // Nested arrays/objects containing wrapper types
        Engine engine = new Engine();
        Map<String, Object> map = (Map<String, Object>) engine.eval(
                "({dates: [new Date(0), new Date(1000)], numbers: [new Number(1), new Number(2)]})");

        assertEquals(2, map.size());

        List<Object> dates = (List<Object>) map.get("dates");
        assertEquals(2, dates.size());
        assertInstanceOf(JsDate.class, dates.get(0));
        assertInstanceOf(JsDate.class, dates.get(1));

        List<Object> numbers = (List<Object>) map.get("numbers");
        assertEquals(2, numbers.size());
        System.out.println("Nested Number wrapper: " + numbers.get(0).getClass().getName());
    }

    @Test
    @SuppressWarnings("unchecked")
    void testArrayConcatWithWrappers() {
        // concat() with wrapper types
        Engine engine = new Engine();
        List<Object> list = (List<Object>) engine.eval(
                "[new Date(0)].concat([new Boolean(true), new Number(42)])");

        assertEquals(3, list.size());
        assertInstanceOf(JsDate.class, list.get(0));
        System.out.println("concat Boolean: " + list.get(1).getClass().getName());
        System.out.println("concat Number: " + list.get(2).getClass().getName());
    }

    @Test
    @SuppressWarnings("unchecked")
    void testSpreadWithWrappers() {
        // Spread operator with wrapper types
        Engine engine = new Engine();
        List<Object> list = (List<Object>) engine.eval(
                "var a = [new Date(0)]; var b = [new Number(1)]; [...a, ...b]");

        assertEquals(2, list.size());
        assertInstanceOf(JsDate.class, list.get(0));
        System.out.println("spread Number: " + list.get(1).getClass().getName());
    }

    @Test
    @SuppressWarnings("unchecked")
    void testFilterWithWrappers() {
        // filter() preserving wrapper types
        Engine engine = new Engine();
        List<Object> list = (List<Object>) engine.eval(
                "[new Date(0), null, new Date(1000), undefined].filter(function(x) { return x instanceof Date; })");

        assertEquals(2, list.size());
        assertInstanceOf(JsDate.class, list.get(0));
        assertInstanceOf(JsDate.class, list.get(1));
    }

    @Test
    @SuppressWarnings("unchecked")
    void testComplexMixedTypes() {
        // Complex scenario with all types mixed
        Engine engine = new Engine();
        List<Object> list = (List<Object>) engine.eval("""
                [
                    1,                      // number primitive
                    'hello',                // string primitive
                    true,                   // boolean primitive
                    null,                   // null
                    undefined,              // undefined
                    new Date(0),            // Date wrapper
                    new Number(3.14),       // Number wrapper
                    new Boolean(false),     // Boolean wrapper
                    new String('world'),    // String wrapper
                    [1, 2],                 // nested array
                    {a: 1}                  // nested object
                ]
                """);

        assertEquals(11, list.size());
        assertEquals(1, list.get(0));
        assertEquals("hello", list.get(1));
        assertEquals(true, list.get(2));
        assertNull(list.get(3));
        assertSame(Terms.UNDEFINED, list.get(4));
        assertInstanceOf(JsDate.class, list.get(5));
        // Wrapper types
        System.out.println("Complex - Number wrapper: " + list.get(6).getClass().getName());
        System.out.println("Complex - Boolean wrapper: " + list.get(7).getClass().getName());
        System.out.println("Complex - String wrapper: " + list.get(8).getClass().getName());
        // Nested structures
        assertInstanceOf(List.class, list.get(9));
        assertInstanceOf(Map.class, list.get(10));
    }

    // =================================================================================================================
    // Other edge cases
    // =================================================================================================================

    @Test
    void testNumericStringIndexOnObject() {
        Engine engine = new Engine();
        Object result1 = engine.eval("var obj = {'0': 'a'}; obj[0]");
        Object result2 = engine.eval("var obj = {'0': 'a'}; obj['0']");

        assertEquals("a", result1, "numeric index should work on object with string key '0'");
        assertEquals("a", result2, "string index '0' should work on object");
    }

    @Test
    @SuppressWarnings("unchecked")
    void testEmptyArrayAndObject() {
        Engine engine = new Engine();

        List<Object> emptyArray = (List<Object>) engine.eval("[]");
        assertEquals(0, emptyArray.size());
        assertTrue(emptyArray.isEmpty());

        Map<String, Object> emptyObject = (Map<String, Object>) engine.eval("({})");
        assertEquals(0, emptyObject.size());
        assertTrue(emptyObject.isEmpty());
    }

    @Test
    @SuppressWarnings("unchecked")
    void testJsUint8ArrayConvertsToByteArray() {
        // Uint8Array is a JavaMirror, should be converted at boundary
        Engine engine = new Engine();
        Object result = engine.eval("new Uint8Array([1, 2, 3])");

        assertInstanceOf(byte[].class, result, "Uint8Array should be converted to byte[]");
        byte[] bytes = (byte[]) result;
        assertArrayEquals(new byte[]{1, 2, 3}, bytes);
    }

    @Test
    @SuppressWarnings("unchecked")
    void testMapNullValueVsMissingKey() {
        // Within JS, null value and missing key are different
        // At boundary, both become null but containsKey differs
        Engine engine = new Engine();
        Map<String, Object> map = (Map<String, Object>) engine.eval("({a: null, b: 'exists'})");

        assertTrue(map.containsKey("a"), "key 'a' should exist");
        assertNull(map.get("a"), "null value should be null");

        assertFalse(map.containsKey("missing"), "missing key should not exist");
        assertNull(map.get("missing"), "missing key returns null from Map.get");
    }

    @Test
    @SuppressWarnings("unchecked")
    void testListNullElementVsOutOfBounds() {
        Engine engine = new Engine();
        List<Object> list = (List<Object>) engine.eval("[null, 'exists']");

        assertEquals(2, list.size());
        assertNull(list.get(0), "null element should be null");
        assertEquals("exists", list.get(1));

        // Out of bounds throws IndexOutOfBoundsException (standard List behavior)
        assertThrows(IndexOutOfBoundsException.class, () -> list.get(99));
    }

}
